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
import pitiupi.stop.net.ReceiptMessage.Status;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Anfitrião da partida: guarda o estado oficial (jogadores, rodada, letra,
 * respostas, votos e placar) e conduz as fases. O anfitrião também joga,
 * por meio de um {@link StopPlayer} local que conversa com ele pelo {@link Link}.
 *
 * <p>Toda alteração de estado acontece sob {@code synchronized (this)}. Os
 * envios vão para um executor separado, para que um {@code connect} TCP lento
 * não trave o jogo.</p>
 */
public final class StopHost {

    public enum State { LOBBY, COUNTDOWN, PLAYING, COLLECTING, VOTING, RESULT, FINISHED }

    public static final long ANNOUNCE_EVERY = 2000;
    public static final long PING_EVERY = 3000;
    public static final long PRESENCE_CHECK_EVERY = 2000;
    public static final long COUNTDOWN = 3000;
    /** Janela, depois do primeiro STOP, para receber pedidos quase simultâneos. */
    public static final long STOP_WINDOW = 400;
    /** Folga depois do fim do tempo da rodada, para pacotes em trânsito. */
    public static final long ROUND_GRACE = 1000;
    /** Depois da PARADA por multicast, quem ainda não mandou RESPOSTAS recebe a PARADA por TCP. */
    public static final long HALT_TCP_FALLBACK = 1500;
    public static final long ANSWER_DEADLINE = 5000;
    public static final long VOTE_TIME = 90_000;
    public static final int MULTICAST_COPIES = 3;
    public static final long COPY_SPACING = 120;
    public static final int MAX_PLAYERS = 20;

    /** Callbacks para a barra de controle do anfitrião. Chamados fora da thread do Swing. */
    public interface View {
        default void playersChanged(List<PlayerScore> players) { }
        default void stateChanged(State state, int round, int totalRounds) { }
        default void answersProgress(int received, int expected) { }
        default void votesProgress(int confirmed, int expected) { }
    }

    private static final class Player {
        final String id;
        final String name;
        long rtt = -1;
        int total;
        int roundPoints;
        boolean online = true;

        Player(String id, String name) {
            this.id = id;
            this.name = name;
        }

        PlayerScore toScore() {
            return new PlayerScore(id, name, total, roundPoints, rtt, online);
        }
    }

    private record StopCandidate(String playerId, String name, long effectiveElapsed) {
    }

    private final Link link;
    private final ProtocolLog log;
    private final View view;
    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
    private final String title;
    private final List<String> themes;
    private final int totalRounds;
    private final int secondsPerRound;
    private final boolean includeHardLetters;
    private final Random random = new Random();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong probeIds = new AtomicLong();
    private final Dedup dedup = new Dedup();

    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Map<Long, Long> probeSentAt = new HashMap<>();
    private final Set<Character> usedLetters = new HashSet<>();

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "stop-host-timer"));
    private final ExecutorService sender = Executors.newCachedThreadPool(r -> daemon(r, "stop-host-send"));

    private State state = State.LOBBY;
    private int round = -1;
    private char letter;
    private long roundSentAt;
    private final List<StopCandidate> stopCandidates = new ArrayList<>();
    private final Map<String, List<String>> answers = new HashMap<>();
    private List<String> roundPlayerIds = List.of();
    private String[][] words;
    private boolean[][] autoInvalid;
    private final Map<String, boolean[][]> votes = new HashMap<>();
    private final Set<String> confirmed = new HashSet<>();
    private ScheduledFuture<?> phaseTimer;

    public StopHost(Link link, ProtocolLog log, View view, String title, List<String> themes,
                    int totalRounds, int secondsPerRound, boolean includeHardLetters) {
        if (themes.isEmpty()) {
            throw new IllegalArgumentException("Escolha pelo menos um tema.");
        }
        this.link = link;
        this.log = log == null ? ProtocolLog.NONE : log;
        this.view = view == null ? new View() { } : view;
        this.title = title;
        this.themes = List.copyOf(themes);
        this.totalRounds = totalRounds;
        this.secondsPerRound = secondsPerRound;
        this.includeHardLetters = includeHardLetters;
        players.put(link.selfId(), new Player(link.selfId(), link.selfName()));
        players.get(link.selfId()).rtt = 0;
    }

    // ================================================================== ciclo de vida

    public void open() {
        log.note("Sala de espera aberta. Partida " + sessionId + ": " + themes.size() + " temas, "
                + totalRounds + " rodadas de até " + secondsPerRound + " s.");
        timers.scheduleAtFixedRate(this::announce, 0, ANNOUNCE_EVERY, TimeUnit.MILLISECONDS);
        timers.scheduleAtFixedRate(this::probeAll, 500, PING_EVERY, TimeUnit.MILLISECONDS);
        timers.scheduleAtFixedRate(this::checkPresence, PRESENCE_CHECK_EVERY, PRESENCE_CHECK_EVERY, TimeUnit.MILLISECONDS);
        view.playersChanged(snapshot());
        view.stateChanged(state, round, totalRounds);
    }

    public String sessionId() {
        return sessionId;
    }

    public String title() {
        return title;
    }

    public List<String> themes() {
        return themes;
    }

    public int totalRounds() {
        return totalRounds;
    }

    public int secondsPerRound() {
        return secondsPerRound;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int playerCount() {
        return players.size();
    }

    /** Botão "Começar partida": precisa de pelo menos 2 jogadores (o anfitrião e mais um). */
    public synchronized boolean start() {
        if (state != State.LOBBY || players.size() < 2) {
            return false;
        }
        setState(State.COUNTDOWN);
        List<String> ids = new ArrayList<>(players.keySet());
        List<String> names = new ArrayList<>();
        for (Player p : players.values()) {
            names.add(p.name);
        }
        multicastRedundant(new StartMessage(themes, totalRounds, secondsPerRound, ids, names),
                players.size() + " jogadores");
        announceClosed();
        timers.schedule(this::nextRound, COUNTDOWN, TimeUnit.MILLISECONDS);
        return true;
    }

    /** Botão "Encerrar votação": fecha a votação antes do prazo. */
    public void closeVotingNow() {
        timers.execute(this::closeVoting);
    }

    /** Botão "Próxima rodada" (ou "Ver placar final" na última). */
    public synchronized boolean advance() {
        if (state != State.RESULT) {
            return false;
        }
        timers.execute(this::nextRound);
        return true;
    }

    public synchronized void cancel() {
        if (state == State.FINISHED) {
            shutdownLater();
            return;
        }
        if (phaseTimer != null) {
            phaseTimer.cancel(false);
        }
        setState(State.FINISHED);
        multicastRedundant(new EndMessage(true, ranking()), "cancelada pelo anfitrião");
        announceClosed();
        shutdownLater();
    }

    private void shutdownLater() {
        timers.schedule(() -> {
            timers.shutdownNow();
            sender.shutdown();
        }, 1, TimeUnit.SECONDS);
    }

    // ================================================================== recebimento

    public void handle(StopMessage m) {
        if (!sessionId.equals(m.getSessionId()) || !dedup.firstTime(m)) {
            return;
        }
        if (m instanceof JoinMessage j) {
            onJoin(j);
        } else if (m instanceof LeaveMessage l) {
            onLeave(l);
        } else if (m instanceof EchoMessage e) {
            onEcho(e);
        } else if (m instanceof StopCallMessage s) {
            onStopCall(s);
        } else if (m instanceof AnswersMessage a) {
            onAnswers(a);
        } else if (m instanceof VotesMessage v) {
            onVotes(v);
        }
    }

    private synchronized void onJoin(JoinMessage join) {
        log.received(join.type(), join.getSenderName(), "TCP");
        String id = join.getSenderId();
        if (!link.isOnline(id)) {
            log.note("ENTRADA de " + join.getSenderName() + " sem sinal de presença recente: não sei o IP para responder.");
            return;
        }
        Player existing = players.get(id);
        if (existing != null) {
            existing.online = true;
            replyJoin(id, true, null);
            view.playersChanged(snapshot());
            return;
        }
        if (state != State.LOBBY) {
            replyJoin(id, false, "A partida já começou.");
            return;
        }
        if (players.size() >= MAX_PLAYERS) {
            replyJoin(id, false, "A sala está cheia (" + MAX_PLAYERS + " jogadores).");
            return;
        }
        players.put(id, new Player(id, join.getSenderName()));
        replyJoin(id, true, null);
        probe(players.get(id));
        view.playersChanged(snapshot());
    }

    private void replyJoin(String id, boolean ok, String reason) {
        send(id, new JoinAckMessage(ok, reason), Link.Mode.TCP, ok ? "entrada aceita" : "entrada recusada: " + reason);
    }

    private synchronized void onLeave(LeaveMessage leave) {
        log.received(leave.type(), leave.getSenderName(), "TCP");
        if (state == State.LOBBY) {
            players.remove(leave.getSenderId());
        } else {
            Player p = players.get(leave.getSenderId());
            if (p != null) {
                p.online = false;
            }
        }
        view.playersChanged(snapshot());
        checkEarlyClose();
    }

    private synchronized void onEcho(EchoMessage echo) {
        Long sent = probeSentAt.remove(echo.getProbeId());
        Player p = players.get(echo.getSenderId());
        if (sent == null || p == null) {
            return;
        }
        long sample = System.currentTimeMillis() - sent;
        p.rtt = StopRules.smoothRtt(p.rtt, sample);
        log.received(echo.type(), echo.getSenderName(), "UDP unicast · amostra de " + sample
                + " ms · RTT estimado em " + p.rtt + " ms");
        view.playersChanged(snapshot());
    }

    private synchronized void onStopCall(StopCallMessage s) {
        long hostObserved = System.currentTimeMillis() - roundSentAt;
        log.received(s.type(), s.getSenderName(), "TCP · rodada " + (s.getRound() + 1) + " · "
                + s.getElapsedMillis() + " ms medidos pelo jogador, " + hostObserved + " ms medidos pelo anfitrião");
        Player p = players.get(s.getSenderId());
        Status status;
        if (p == null) {
            status = Status.NOT_IN_GAME;
        } else if (s.getRound() != round) {
            status = Status.WRONG_ROUND;
        } else if (state != State.PLAYING) {
            status = Status.LATE;
        } else if (stopCandidates.stream().anyMatch(c -> c.playerId().equals(p.id))) {
            status = Status.DUPLICATE;
        } else {
            long rtt = link.isSelf(p.id) ? 0 : p.rtt;
            long effective = StopRules.effectiveElapsed(s.getElapsedMillis(), hostObserved, rtt);
            stopCandidates.add(new StopCandidate(p.id, p.name, effective));
            if (stopCandidates.size() == 1) {
                log.note("Primeiro STOP recebido (" + p.name + "). Aguardando " + STOP_WINDOW
                        + " ms por pedidos quase simultâneos.");
                timers.schedule(this::decideStop, STOP_WINDOW, TimeUnit.MILLISECONDS);
            } else {
                log.note("Outro STOP dentro da janela: " + p.name + " (" + effective + " ms).");
            }
            status = Status.ACCEPTED;
        }
        receipt(s.getSenderId(), "STOP", s.getRound(), status);
    }

    private synchronized void onAnswers(AnswersMessage a) {
        log.received(a.type(), a.getSenderName(), "TCP · rodada " + (a.getRound() + 1));
        Status status;
        if (!players.containsKey(a.getSenderId())) {
            status = Status.NOT_IN_GAME;
        } else if (a.getRound() != round) {
            status = Status.WRONG_ROUND;
        } else if (answers.containsKey(a.getSenderId())) {
            status = Status.DUPLICATE;
        } else if (state != State.COLLECTING && state != State.PLAYING) {
            status = Status.LATE;
        } else {
            answers.put(a.getSenderId(), fit(a.getWords()));
            status = Status.ACCEPTED;
            view.answersProgress(answers.size(), onlineCount());
        }
        receipt(a.getSenderId(), "RESPOSTAS", a.getRound(), status);
        checkEarlyClose();
    }

    private synchronized void onVotes(VotesMessage v) {
        log.received(v.type(), v.getSenderName(), "TCP · rodada " + (v.getRound() + 1)
                + (v.isConfirmed() ? " · confirmados" : " · parciais"));
        Status status;
        if (!players.containsKey(v.getSenderId())) {
            status = Status.NOT_IN_GAME;
        } else if (v.getRound() != round) {
            status = Status.WRONG_ROUND;
        } else if (state != State.VOTING) {
            status = Status.LATE;
        } else if (!shapeMatches(v.getInvalid())) {
            status = Status.WRONG_ROUND;
        } else {
            votes.put(v.getSenderId(), v.getInvalid());
            if (v.isConfirmed()) {
                confirmed.add(v.getSenderId());
            }
            status = Status.ACCEPTED;
            view.votesProgress(confirmed.size(), onlineCount());
        }
        receipt(v.getSenderId(), "VOTOS", v.getRound(), status);
        checkEarlyClose();
    }

    // ================================================================== fases

    private synchronized void nextRound() {
        if (state == State.FINISHED) {
            return;
        }
        round++;
        if (round >= totalRounds) {
            finish();
            return;
        }
        letter = StopRules.drawLetter(usedLetters, includeHardLetters, random);
        stopCandidates.clear();
        answers.clear();
        votes.clear();
        confirmed.clear();
        for (Player p : players.values()) {
            p.roundPoints = 0;
        }
        setState(State.PLAYING);
        long limit = secondsPerRound * 1000L;
        roundSentAt = System.currentTimeMillis();
        multicastRedundant(new RoundMessage(round, totalRounds, letter, themes, (int) limit),
                "rodada " + (round + 1) + " de " + totalRounds + ", letra " + letter);
        phaseTimer = timers.schedule(() -> halt(HaltMessage.Reason.TIME_UP, null, null),
                limit + ROUND_GRACE, TimeUnit.MILLISECONDS);
    }

    private synchronized void decideStop() {
        if (state != State.PLAYING || stopCandidates.isEmpty()) {
            return;
        }
        StopCandidate winner = stopCandidates.stream()
                .min(Comparator.comparingLong(StopCandidate::effectiveElapsed)).orElseThrow();
        if (stopCandidates.size() > 1) {
            log.note("STOP disputado por " + stopCandidates.size() + " jogadores; venceu " + winner.name()
                    + " com " + winner.effectiveElapsed() + " ms.");
        }
        halt(HaltMessage.Reason.STOP, winner.playerId(), winner.name());
    }

    private synchronized void halt(HaltMessage.Reason reason, String stopperId, String stopperName) {
        if (state != State.PLAYING) {
            return;
        }
        if (phaseTimer != null) {
            phaseTimer.cancel(false);
        }
        setState(State.COLLECTING);
        HaltMessage halt = new HaltMessage(round, reason, stopperId, stopperName);
        multicastRedundant(halt, reason == HaltMessage.Reason.STOP ? "STOP de " + stopperName : "tempo esgotado");
        view.answersProgress(answers.size(), onlineCount());
        int haltRound = round;
        // Recuperação: quem não mandou RESPOSTAS (talvez tenha perdido as 3 cópias) recebe a PARADA por TCP.
        timers.schedule(() -> {
            synchronized (this) {
                if (state != State.COLLECTING || round != haltRound) {
                    return;
                }
                for (Player p : players.values()) {
                    if (p.online && !answers.containsKey(p.id) && !link.isSelf(p.id)) {
                        HaltMessage again = new HaltMessage(haltRound, reason, stopperId, stopperName);
                        send(p.id, again, Link.Mode.TCP, "recuperação: " + p.name + " ainda não enviou RESPOSTAS");
                    }
                }
            }
        }, HALT_TCP_FALLBACK, TimeUnit.MILLISECONDS);
        phaseTimer = timers.schedule(this::openVoting, ANSWER_DEADLINE, TimeUnit.MILLISECONDS);
    }

    private synchronized void openVoting() {
        if (state != State.COLLECTING) {
            return;
        }
        if (phaseTimer != null) {
            phaseTimer.cancel(false);
        }
        roundPlayerIds = new ArrayList<>(players.keySet());
        List<String> names = new ArrayList<>();
        words = new String[roundPlayerIds.size()][themes.size()];
        autoInvalid = new boolean[roundPlayerIds.size()][themes.size()];
        for (int j = 0; j < roundPlayerIds.size(); j++) {
            Player p = players.get(roundPlayerIds.get(j));
            names.add(p.name);
            List<String> w = answers.get(p.id);
            if (w == null) {
                log.note(p.name + " não enviou RESPOSTAS a tempo: todas as palavras ficam em branco.");
            }
            for (int t = 0; t < themes.size(); t++) {
                words[j][t] = w == null ? "" : w.get(t);
                autoInvalid[j][t] = StopRules.autoInvalidReason(words[j][t], letter) != null;
            }
        }
        setState(State.VOTING);
        multicastRedundant(new VotingMessage(round, letter, themes, roundPlayerIds, names, words, autoInvalid,
                (int) VOTE_TIME), roundPlayerIds.size() + " jogadores × " + themes.size() + " temas");
        view.votesProgress(0, onlineCount());
        phaseTimer = timers.schedule(this::closeVoting, VOTE_TIME + ROUND_GRACE, TimeUnit.MILLISECONDS);
    }

    private synchronized void closeVoting() {
        if (state != State.VOTING) {
            return;
        }
        if (phaseTimer != null) {
            phaseTimer.cancel(false);
        }
        int n = roundPlayerIds.size();
        int themeCount = themes.size();
        boolean[][] valid = new boolean[n][themeCount];
        int[][] invalidVotes = new int[n][themeCount];
        int[] voters = new int[n];
        for (int j = 0; j < n; j++) {
            String author = roundPlayerIds.get(j);
            for (String voter : votes.keySet()) {
                if (!voter.equals(author)) {
                    voters[j]++;
                }
            }
            for (int t = 0; t < themeCount; t++) {
                if (autoInvalid[j][t]) {
                    continue;
                }
                for (Map.Entry<String, boolean[][]> e : votes.entrySet()) {
                    if (!e.getKey().equals(author) && e.getValue()[j][t]) {
                        invalidVotes[j][t]++;
                    }
                }
                valid[j][t] = StopRules.survivesVote(invalidVotes[j][t], voters[j]);
            }
        }
        int[][] points = new int[n][themeCount];
        for (int t = 0; t < themeCount; t++) {
            String[] col = new String[n];
            boolean[] ok = new boolean[n];
            for (int j = 0; j < n; j++) {
                col[j] = words[j][t];
                ok[j] = valid[j][t];
            }
            int[] pts = StopRules.pointsForTheme(col, ok);
            for (int j = 0; j < n; j++) {
                points[j][t] = pts[j];
            }
        }
        List<String> names = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            Player p = players.get(roundPlayerIds.get(j));
            names.add(p.name);
            int sum = 0;
            for (int t = 0; t < themeCount; t++) {
                sum += points[j][t];
            }
            p.roundPoints = sum;
            p.total += sum;
        }
        setState(State.RESULT);
        multicastRedundant(new ResultMessage(round, totalRounds, letter, themes, roundPlayerIds, names, words, valid,
                points, invalidVotes, voters, ranking()), votes.size() + " jogadores votaram");
        view.playersChanged(snapshot());
    }

    private synchronized void finish() {
        setState(State.FINISHED);
        multicastRedundant(new EndMessage(false, ranking()), "placar final");
        shutdownLater();
    }

    /** Fecha a fase atual antes do prazo quando todos os jogadores conectados já fizeram a sua parte. */
    private void checkEarlyClose() {
        int online = onlineCount();
        if (state == State.COLLECTING && countOnline(answers.keySet()) >= online) {
            log.note("Todos os jogadores conectados enviaram RESPOSTAS: abrindo a votação.");
            timers.execute(this::openVoting);
        } else if (state == State.VOTING && countOnline(confirmed) >= online) {
            log.note("Todos os jogadores conectados confirmaram os votos: fechando a votação.");
            timers.execute(this::closeVoting);
        }
    }

    // ================================================================== tarefas periódicas

    private synchronized void announce() {
        if (state != State.LOBBY) {
            return;
        }
        AnnounceMessage a = new AnnounceMessage(title, themes, totalRounds, secondsPerRound, names(), true);
        stamp(a);
        sender.execute(() -> link.multicast(a));
    }

    private void announceClosed() {
        AnnounceMessage a = new AnnounceMessage(title, themes, totalRounds, secondsPerRound, names(), false);
        stamp(a);
        log.sent(a.type(), "UDP multicast", "sala", "sala de espera fechada");
        sender.execute(() -> link.multicast(a));
    }

    private synchronized void probeAll() {
        if (state == State.FINISHED) {
            return;
        }
        for (Player p : players.values()) {
            if (p.online && !link.isSelf(p.id)) {
                probe(p);
            }
        }
        long now = System.currentTimeMillis();
        probeSentAt.values().removeIf(t -> now - t > 10_000);
    }

    private void probe(Player p) {
        long id = probeIds.incrementAndGet();
        ProbeMessage probe = new ProbeMessage(id);
        stamp(probe);
        probeSentAt.put(id, System.currentTimeMillis());
        sender.execute(() -> link.send(p.id, probe, Link.Mode.UDP));
    }

    private synchronized void checkPresence() {
        boolean changed = false;
        for (Player p : players.values()) {
            boolean online = link.isOnline(p.id);
            if (online != p.online) {
                p.online = online;
                changed = true;
                log.note(p.name + (online ? " voltou a enviar sinal de presença."
                        : " ficou sem enviar sinal de presença por cerca de 15 s e foi marcado como desconectado."));
            }
        }
        if (state == State.LOBBY) {
            changed |= players.values().removeIf(p -> !p.online);
        }
        if (changed) {
            view.playersChanged(snapshot());
            checkEarlyClose();
        }
    }

    // ================================================================== envio

    private void multicastRedundant(StopMessage m, String detail) {
        stamp(m);
        log.sent(m.type(), "UDP multicast ×" + MULTICAST_COPIES, "sala", detail);
        for (int i = 0; i < MULTICAST_COPIES; i++) {
            timers.schedule(() -> sender.execute(() -> link.multicast(m)), i * COPY_SPACING, TimeUnit.MILLISECONDS);
        }
    }

    private void receipt(String to, String kind, int r, Status status) {
        send(to, new ReceiptMessage(kind, r, status), Link.Mode.TCP, kind + " " + status.label());
    }

    private void send(String to, StopMessage m, Link.Mode mode, String detail) {
        stamp(m);
        Player p = players.get(to);
        String name = p != null ? p.name : to;
        if (!link.isSelf(to)) {
            log.sent(m.type(), mode == Link.Mode.TCP ? "TCP" : "UDP unicast", name, detail);
        }
        if (sender.isShutdown()) {
            return;
        }
        sender.execute(() -> {
            if (!link.send(to, m, mode)) {
                log.note("Falha ao enviar " + m.type() + " para " + name + ".");
            }
        });
    }

    private void stamp(StopMessage m) {
        m.stamp(sessionId, link.selfId(), link.selfName(), seq.incrementAndGet());
    }

    // ================================================================== apoio

    private void setState(State s) {
        state = s;
        view.stateChanged(s, round, totalRounds);
    }

    private List<String> fit(List<String> w) {
        List<String> out = new ArrayList<>();
        for (int t = 0; t < themes.size(); t++) {
            String word = w != null && t < w.size() && w.get(t) != null ? w.get(t).strip() : "";
            out.add(word.length() > 60 ? word.substring(0, 60) : word);
        }
        return out;
    }

    private boolean shapeMatches(boolean[][] m) {
        if (m == null || m.length != roundPlayerIds.size()) {
            return false;
        }
        for (boolean[] row : m) {
            if (row == null || row.length != themes.size()) {
                return false;
            }
        }
        return true;
    }

    private int onlineCount() {
        int n = 0;
        for (Player p : players.values()) {
            if (p.online) {
                n++;
            }
        }
        return n;
    }

    private int countOnline(Set<String> ids) {
        int n = 0;
        for (String id : ids) {
            Player p = players.get(id);
            if (p != null && p.online) {
                n++;
            }
        }
        return n;
    }

    private List<String> names() {
        List<String> list = new ArrayList<>();
        for (Player p : players.values()) {
            list.add(p.name);
        }
        return list;
    }

    private List<PlayerScore> snapshot() {
        List<PlayerScore> list = new ArrayList<>();
        for (Player p : players.values()) {
            list.add(p.toScore());
        }
        return list;
    }

    private List<PlayerScore> ranking() {
        List<PlayerScore> list = snapshot();
        list.sort(Comparator.comparingInt(PlayerScore::getTotal).reversed()
                .thenComparing(PlayerScore::getName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
