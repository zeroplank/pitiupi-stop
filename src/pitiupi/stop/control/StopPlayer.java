package pitiupi.stop.control;

import pitiupi.stop.net.AnnounceMessage;
import pitiupi.stop.net.AnswersMessage;
import pitiupi.stop.net.EchoMessage;
import pitiupi.stop.net.EndMessage;
import pitiupi.stop.net.HaltMessage;
import pitiupi.stop.net.JoinAckMessage;
import pitiupi.stop.net.JoinMessage;
import pitiupi.stop.net.LeaveMessage;
import pitiupi.stop.net.PlayerScore;
import pitiupi.stop.net.ProbeMessage;
import pitiupi.stop.net.ReceiptMessage;
import pitiupi.stop.net.ResultMessage;
import pitiupi.stop.net.RoundMessage;
import pitiupi.stop.net.StartMessage;
import pitiupi.stop.net.StopCallMessage;
import pitiupi.stop.net.StopMessage;
import pitiupi.stop.net.VotesMessage;
import pitiupi.stop.net.VotingMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Lado do jogador: descobre partidas pelos ANÚNCIOs, entra, recebe a letra,
 * pede STOP, envia as palavras e os votos. STOP, RESPOSTAS e VOTOS são
 * reenviados até chegar o RECIBO do anfitrião.
 */
public final class StopPlayer {

    public enum State { IDLE, JOINING, LOBBY, PLAYING, WAITING, VOTING, RESULT, FINISHED }

    public static final long LOBBY_EXPIRY = 7000;
    public static final long JOIN_TIMEOUT = 6000;
    public static final long RECEIPT_TIMEOUT = 2500;
    public static final int MAX_TRIES = 3;
    public static final long HOST_CHECK_EVERY = 2000;

    /** Partida visível na lista de partidas abertas. */
    public record Lobby(String sessionId, String hostId, String hostName, String title, List<String> themes,
                        int rounds, int secondsPerRound, List<String> playerNames, long lastSeen) {
    }

    /** Callbacks para a interface. Chamados fora da thread do Swing. */
    public interface View {
        default void lobbiesChanged(List<Lobby> lobbies) { }
        default void joinResult(boolean accepted, String reason, Lobby lobby) { }
        default void lobbyPlayers(List<String> names) { }
        default void started(StartMessage start) { }
        default void round(RoundMessage round) { }
        default void halted(HaltMessage halt, boolean byMe) { }
        default void voting(VotingMessage voting) { }
        default void result(ResultMessage result) { }
        default void finished(List<PlayerScore> ranking, boolean cancelled, String reason) { }
        default void status(String text, boolean ok) { }
    }

    private final Link link;
    private final ProtocolLog log;
    private final View view;
    private final AtomicLong seq = new AtomicLong();
    private final Dedup dedup = new Dedup();
    private final Map<String, Lobby> lobbies = new LinkedHashMap<>();
    private final Map<String, ScheduledFuture<?>> pending = new HashMap<>();

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "stop-player-timer"));
    private final ExecutorService sender = Executors.newCachedThreadPool(r -> daemon(r, "stop-player-send"));

    private State state = State.IDLE;
    private Lobby current;
    private int currentRound = -1;
    private long roundReceivedAt;
    private boolean stopRequested;
    private boolean answersSent;
    private volatile Supplier<List<String>> draft = List::of;
    private ScheduledFuture<?> joinTimeout;

    public StopPlayer(Link link, ProtocolLog log, View view) {
        this.link = link;
        this.log = log == null ? ProtocolLog.NONE : log;
        this.view = view == null ? new View() { } : view;
        timers.scheduleAtFixedRate(this::housekeeping, HOST_CHECK_EVERY, HOST_CHECK_EVERY, TimeUnit.MILLISECONDS);
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isHostingMyself() {
        return current != null && link.isSelf(current.hostId());
    }

    /** A interface informa de onde ler as palavras digitadas (lidas no momento da PARADA). */
    public void setDraftSource(Supplier<List<String>> source) {
        this.draft = source;
    }

    // ================================================================== ações da interface

    /** O anfitrião entra na própria partida sem precisar de ENTRADA/ACEITE. */
    public synchronized void attachToOwnHost(StopHost host) {
        reset();
        current = new Lobby(host.sessionId(), link.selfId(), link.selfName(), host.title(), host.themes(),
                host.totalRounds(), host.secondsPerRound(), List.of(link.selfName()), System.currentTimeMillis());
        state = State.LOBBY;
        view.joinResult(true, null, current);
    }

    public synchronized void join(String sessionId) {
        Lobby lobby = lobbies.get(sessionId);
        if (lobby == null || (state != State.IDLE && state != State.FINISHED)) {
            return;
        }
        if (!link.isOnline(lobby.hostId())) {
            view.joinResult(false, "O anfitrião não aparece na lista de usuários conectados.", lobby);
            return;
        }
        reset();
        current = lobby;
        state = State.JOINING;
        send(new JoinMessage(), Link.Mode.TCP, "pedido de entrada");
        joinTimeout = timers.schedule(() -> {
            synchronized (this) {
                if (state == State.JOINING) {
                    log.note("Nenhum ACEITE em " + JOIN_TIMEOUT / 1000 + " s: pedido de entrada abandonado.");
                    view.joinResult(false, "O anfitrião não respondeu. Verifique se o firewall libera TCP na porta.", current);
                    reset();
                }
            }
        }, JOIN_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    public synchronized void leave() {
        if (current != null && !link.isSelf(current.hostId()) && state != State.IDLE && state != State.FINISHED) {
            send(new LeaveMessage(), Link.Mode.TCP, "saindo da partida");
        }
        reset();
    }

    /** Botão STOP. */
    public synchronized boolean requestStop() {
        if (state != State.PLAYING || stopRequested) {
            return false;
        }
        stopRequested = true;
        long elapsed = System.currentTimeMillis() - roundReceivedAt;
        reliable("STOP", currentRound, () -> new StopCallMessage(currentRound, elapsed), elapsed + " ms desde a RODADA");
        view.status("Pedido de STOP enviado. Aguardando o anfitrião…", true);
        return true;
    }

    /** Envia os votos (pode ser chamado várias vezes; vale o último). */
    public synchronized void sendVotes(boolean[][] invalid, boolean confirmedVotes) {
        if (state != State.VOTING) {
            return;
        }
        boolean[][] copy = new boolean[invalid.length][];
        for (int i = 0; i < invalid.length; i++) {
            copy[i] = invalid[i].clone();
        }
        reliable("VOTOS", currentRound, () -> new VotesMessage(currentRound, copy, confirmedVotes),
                confirmedVotes ? "votos confirmados" : "votos parciais");
    }

    public void shutdown() {
        leave();
        timers.shutdownNow();
        sender.shutdown();
    }

    // ================================================================== recebimento

    public void handle(StopMessage m) {
        if (!dedup.firstTime(m)) {
            return;
        }
        if (m instanceof AnnounceMessage a) {
            onAnnounce(a);
            return;
        }
        synchronized (this) {
            if (current == null || !current.sessionId().equals(m.getSessionId())) {
                return;
            }
        }
        if (m instanceof JoinAckMessage ack) {
            onJoinAck(ack);
        } else if (m instanceof ProbeMessage p) {
            onProbe(p);
        } else if (m instanceof StartMessage s) {
            onStart(s);
        } else if (m instanceof RoundMessage r) {
            onRound(r);
        } else if (m instanceof HaltMessage h) {
            onHalt(h);
        } else if (m instanceof VotingMessage v) {
            onVoting(v);
        } else if (m instanceof ResultMessage r) {
            onResult(r);
        } else if (m instanceof ReceiptMessage r) {
            onReceipt(r);
        } else if (m instanceof EndMessage e) {
            onEnd(e);
        }
    }

    private synchronized void onAnnounce(AnnounceMessage a) {
        if (current != null && current.sessionId().equals(a.getSessionId())) {
            view.lobbyPlayers(a.getPlayerNames());
        }
        if (link.isSelf(a.getSenderId())) {
            return; // a própria partida não aparece na lista de partidas abertas
        }
        boolean changed;
        if (a.isOpen()) {
            Lobby lobby = new Lobby(a.getSessionId(), a.getSenderId(), a.getSenderName(), a.getTitle(), a.getThemes(),
                    a.getRounds(), a.getSecondsPerRound(), a.getPlayerNames(), System.currentTimeMillis());
            Lobby previous = lobbies.put(a.getSessionId(), lobby);
            changed = previous == null || !previous.playerNames().equals(lobby.playerNames());
            if (previous == null) {
                log.received(a.type(), a.getSenderName(), "UDP multicast · nova partida \"" + a.getTitle() + "\"");
            }
        } else {
            changed = lobbies.remove(a.getSessionId()) != null;
        }
        if (changed) {
            view.lobbiesChanged(listLobbies());
        }
    }

    private synchronized void onJoinAck(JoinAckMessage ack) {
        log.received(ack.type(), ack.getSenderName(), "TCP · " + (ack.isAccepted() ? "entrada aceita" : "entrada recusada: " + ack.getReason()));
        if (state != State.JOINING) {
            return;
        }
        if (joinTimeout != null) {
            joinTimeout.cancel(false);
        }
        Lobby lobby = current;
        if (ack.isAccepted()) {
            state = State.LOBBY;
            view.joinResult(true, null, lobby);
            view.lobbyPlayers(lobby.playerNames());
        } else {
            reset();
            view.joinResult(false, ack.getReason(), lobby);
        }
    }

    private void onProbe(ProbeMessage probe) {
        EchoMessage echo = new EchoMessage(probe.getProbeId());
        String hostId;
        synchronized (this) {
            hostId = current.hostId();
            stamp(echo);
        }
        sender.execute(() -> link.send(hostId, echo, Link.Mode.UDP));
    }

    private synchronized void onStart(StartMessage s) {
        log.received(s.type(), s.getSenderName(), "UDP multicast · " + s.getPlayerIds().size() + " jogadores, "
                + s.getThemes().size() + " temas");
        if (!s.getPlayerIds().contains(link.selfId())) {
            if (state == State.JOINING || state == State.LOBBY) {
                view.finished(List.of(), true, "A partida começou sem você na lista.");
                reset();
            }
            return;
        }
        if (state == State.LOBBY) {
            view.started(s);
        }
    }

    private synchronized void onRound(RoundMessage r) {
        if (r.getRound() <= currentRound) {
            return;
        }
        if (state == State.LOBBY) {
            log.note("O INÍCIO não chegou; entrando na partida pela RODADA.");
        } else if (state == State.JOINING || state == State.IDLE || state == State.FINISHED) {
            return;
        }
        log.received(r.type(), r.getSenderName(), "UDP multicast · rodada " + (r.getRound() + 1) + ", letra " + r.getLetter());
        cancelPending();
        currentRound = r.getRound();
        roundReceivedAt = System.currentTimeMillis();
        stopRequested = false;
        answersSent = false;
        state = State.PLAYING;
        view.round(r);
    }

    private synchronized void onHalt(HaltMessage h) {
        if (h.getRound() < currentRound || state == State.IDLE || state == State.JOINING || state == State.FINISHED) {
            return;
        }
        boolean missedRound = h.getRound() > currentRound;
        if (missedRound) {
            // As 3 cópias da RODADA se perderam: o jogador não viu a letra e manda as palavras em branco.
            log.note("A RODADA " + (h.getRound() + 1) + " não chegou; enviando as palavras em branco.");
            cancelPending();
            currentRound = h.getRound();
            answersSent = false;
            state = State.PLAYING;
        }
        log.received(h.type(), h.getSenderName(), (h.getReason() == HaltMessage.Reason.STOP
                ? "STOP de " + h.getStopperName() : "tempo esgotado") + " · rodada " + (h.getRound() + 1));
        if (state == State.PLAYING) {
            state = State.WAITING;
            view.halted(h, link.isSelf(h.getStopperId()));
        }
        if (!answersSent && state == State.WAITING) {
            answersSent = true;
            List<String> words = missedRound ? List.of() : new ArrayList<>(draft.get());
            reliable("RESPOSTAS", currentRound, () -> new AnswersMessage(currentRound, words),
                    words.stream().filter(w -> !w.isBlank()).count() + " palavras preenchidas");
        }
    }

    private synchronized void onVoting(VotingMessage v) {
        if (v.getRound() < currentRound || state == State.IDLE || state == State.JOINING || state == State.FINISHED
                || (v.getRound() == currentRound && (state == State.VOTING || state == State.RESULT))) {
            return;
        }
        currentRound = v.getRound();
        log.received(v.type(), v.getSenderName(), "UDP multicast · " + v.getPlayerIds().size() + " jogadores × "
                + v.getThemes().size() + " temas");
        cancelPending();
        state = State.VOTING;
        view.voting(v);
    }

    private synchronized void onResult(ResultMessage r) {
        if (r.getRound() < currentRound || (r.getRound() == currentRound && state == State.RESULT)) {
            return;
        }
        log.received(r.type(), r.getSenderName(), "UDP multicast · rodada " + (r.getRound() + 1));
        cancelPending();
        currentRound = r.getRound();
        state = State.RESULT;
        view.result(r);
    }

    private synchronized void onReceipt(ReceiptMessage r) {
        log.received(r.type(), r.getSenderName(), "TCP · " + r.getKind() + " " + r.getStatus().label());
        if (r.getRound() != currentRound) {
            return;
        }
        ScheduledFuture<?> retry = pending.remove(r.getKind());
        if (retry != null) {
            retry.cancel(false);
        }
        String text = switch (r.getKind()) {
            case "STOP" -> r.getStatus().ok() ? "O anfitrião recebeu o seu STOP." : "O seu STOP chegou tarde: outra pessoa pediu antes.";
            case "RESPOSTAS" -> r.getStatus().ok() ? "Suas palavras foram registradas. Aguardando a votação…"
                    : "Suas palavras chegaram fora do prazo.";
            default -> r.getStatus().ok() ? "Seus votos foram registrados." : "Seus votos não foram aceitos: " + r.getStatus().label() + ".";
        };
        view.status(text, r.getStatus().ok());
    }

    private synchronized void onEnd(EndMessage e) {
        log.received(e.type(), e.getSenderName(), "UDP multicast · " + (e.isCancelled() ? "partida cancelada" : "placar final"));
        view.finished(e.getRanking(), e.isCancelled(), e.isCancelled() ? "O anfitrião encerrou a partida." : null);
        reset();
        state = State.FINISHED;
    }

    // ================================================================== tarefas periódicas

    private synchronized void housekeeping() {
        long now = System.currentTimeMillis();
        if (lobbies.values().removeIf(l -> now - l.lastSeen() > LOBBY_EXPIRY)) {
            view.lobbiesChanged(listLobbies());
        }
        if (current != null && state != State.IDLE && state != State.FINISHED && state != State.JOINING
                && !link.isOnline(current.hostId())) {
            log.note("O anfitrião " + current.hostName() + " parou de enviar sinal de presença.");
            view.finished(List.of(), true, "A conexão com o anfitrião foi perdida: ele parou de enviar sinal de presença.");
            reset();
        }
    }

    // ================================================================== envio confiável

    /**
     * Envia por TCP e espera o RECIBO. Sem RECIBO em 2,5 s, reenvia (até 3 vezes).
     * Cada tentativa é um pacote novo; o anfitrião reconhece a repetição pela rodada.
     */
    private void reliable(String kind, int r, Supplier<StopMessage> factory, String detail) {
        ScheduledFuture<?> old = pending.remove(kind);
        if (old != null) {
            old.cancel(false);
        }
        attempt(kind, r, factory, detail, 1);
    }

    private void attempt(String kind, int r, Supplier<StopMessage> factory, String detail, int n) {
        send(factory.get(), Link.Mode.TCP, n == 1 ? detail : detail + " · tentativa " + n);
        ScheduledFuture<?> retry = timers.schedule(() -> {
            synchronized (this) {
                if (currentRound != r || pending.get(kind) == null) {
                    return;
                }
                if (n >= MAX_TRIES) {
                    pending.remove(kind);
                    view.status("O anfitrião não confirmou o envio de " + kind + ".", false);
                    return;
                }
                attempt(kind, r, factory, detail, n + 1);
            }
        }, RECEIPT_TIMEOUT, TimeUnit.MILLISECONDS);
        pending.put(kind, retry);
    }

    private void send(StopMessage m, Link.Mode mode, String detail) {
        stamp(m);
        String hostId = current.hostId();
        if (!link.isSelf(hostId)) {
            log.sent(m.type(), mode == Link.Mode.TCP ? "TCP" : "UDP unicast", current.hostName(), detail);
        }
        if (sender.isShutdown()) {
            return;
        }
        sender.execute(() -> {
            if (!link.send(hostId, m, mode)) {
                log.note("Falha ao enviar " + m.type() + " para o anfitrião.");
            }
        });
    }

    private void stamp(StopMessage m) {
        m.stamp(current.sessionId(), link.selfId(), link.selfName(), seq.incrementAndGet());
    }

    // ================================================================== apoio

    private void cancelPending() {
        for (ScheduledFuture<?> f : pending.values()) {
            f.cancel(false);
        }
        pending.clear();
    }

    private void reset() {
        if (joinTimeout != null) {
            joinTimeout.cancel(false);
        }
        cancelPending();
        state = State.IDLE;
        current = null;
        currentRound = -1;
        stopRequested = false;
        answersSent = false;
    }

    private List<Lobby> listLobbies() {
        List<Lobby> list = new ArrayList<>(lobbies.values());
        list.sort(Comparator.comparing(Lobby::hostName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
