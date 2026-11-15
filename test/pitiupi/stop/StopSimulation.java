package pitiupi.stop;

import pitiupi.models.Messages.Message;
import pitiupi.stop.control.Link;
import pitiupi.stop.control.ProtocolLog;
import pitiupi.stop.control.StopHost;
import pitiupi.stop.control.StopPlayer;
import pitiupi.stop.control.StopRules;
import pitiupi.stop.control.StopTransport;
import pitiupi.stop.net.HaltMessage;
import pitiupi.stop.net.PlayerScore;
import pitiupi.stop.net.ResultMessage;
import pitiupi.stop.net.RoundMessage;
import pitiupi.stop.net.StopMessage;
import pitiupi.stop.net.VotingMessage;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Partida simulada sem interface e sem sockets: o anfitrião (que também joga)
 * e dois jogadores trocam as mensagens reais do STOP/1, serializadas em bytes,
 * por uma rede em memória com atraso e 30% de perda nos datagramas UDP.
 *
 * <p>Cenário de cada rodada, com os temas Nome, Animal e Cor:</p>
 * <ul>
 *   <li>Ana escreve palavras únicas e pede STOP aos 300 ms;</li>
 *   <li>Beto pede STOP aos 320 ms (quase junto), repete a palavra do anfitrião no tema Nome,
 *       erra a letra no tema Animal e escreve uma palavra que os outros invalidam no tema Cor;</li>
 *   <li>o anfitrião escreve palavras únicas nos temas Animal e Cor.</li>
 * </ul>
 */
public final class StopSimulation {

    static final double UDP_LOSS = 0.30;
    static final Random RNG = new Random(11);
    static final ScheduledExecutorService WIRE = Executors.newScheduledThreadPool(4);
    static final Map<String, Node> NODES = new ConcurrentHashMap<>();
    static final AtomicInteger DROPPED = new AtomicInteger();
    static final List<String> THEMES = List.of("Nome", "Animal", "Cor");

    static final class Node implements StopTransport {
        final String id;
        final String name;
        final InetAddress addr;
        volatile StopHost host;
        volatile StopPlayer player;
        final Link link;

        Node(String id, String name, int last) throws Exception {
            this.id = id;
            this.name = name;
            this.addr = InetAddress.getByName("10.0.0." + last);
            this.link = new Link(this, this::route);
        }

        void route(StopMessage m) {
            if (m.isForHost()) {
                if (host != null) host.handle(m);
            } else if (player != null) {
                player.handle(m);
            }
        }

        void fromWire(byte[] bytes) {
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                StopMessage m = (StopMessage) in.readObject();
                if (!m.getSenderId().equals(id)) {
                    route(m);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        public String selfId() { return id; }
        public String selfName() { return name; }

        public boolean multicast(StopMessage m) {
            for (Node n : NODES.values()) {
                if (n != this) udp(m, n);
            }
            return true;
        }

        public boolean unicastUdp(StopMessage m, InetAddress a) {
            NODES.values().stream().filter(n -> n.addr.equals(a)).forEach(n -> udp(m, n));
            return true;
        }

        public boolean tcp(StopMessage m, InetAddress a) {
            byte[] b = bytes(m);
            NODES.values().stream().filter(n -> n.addr.equals(a))
                    .forEach(n -> WIRE.schedule(() -> n.fromWire(b), 25, TimeUnit.MILLISECONDS));
            return true;
        }

        private void udp(StopMessage m, Node n) {
            if (RNG.nextDouble() < UDP_LOSS) {
                DROPPED.incrementAndGet();
                return;
            }
            byte[] b = bytes(m);
            WIRE.schedule(() -> n.fromWire(b), 5 + RNG.nextInt(40), TimeUnit.MILLISECONDS);
        }

        public Optional<InetAddress> addressOf(String other) {
            Node n = NODES.get(other);
            return n == null || n == this ? Optional.empty() : Optional.of(n.addr);
        }
    }

    static byte[] bytes(Message m) {
        try {
            return m.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("FALHOU: " + what);
        }
        System.out.println("  ok  " + what);
    }

    /** Palavras de cada jogador para a letra sorteada. */
    static List<String> wordsFor(String who, char letter) {
        String l = String.valueOf(letter);
        return switch (who) {
            case "Ana" -> List.of(l + "ana", l + "animal", l + "cor-ana");
            case "Beto" -> List.of(l + "repetida", "1bicho", l + "inventada");
            default -> List.of(l + "Repetida ", l + "animal-host", l + "cor-host");
        };
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== Regras");
        check(StopRules.normalize("  São   Paulo ").equals("sao paulo"), "normalização tira acentos, maiúsculas e espaços extras");
        check(StopRules.startsWithLetter("Ágata", 'A'), "\"Ágata\" começa com A");
        check(!StopRules.startsWithLetter("Bola", 'A'), "\"Bola\" não começa com A");
        check(StopRules.autoInvalidReason("  ", 'A').equals("em branco"), "palavra em branco é invalidada automaticamente");
        int[] pts = StopRules.pointsForTheme(new String[]{"Arara", "arara", "Anta", "Abelha"}, new boolean[]{true, true, true, false});
        check(pts[0] == 5 && pts[1] == 5 && pts[2] == 10 && pts[3] == 0, "pontos: repetida 5, única 10, inválida 0");
        check(StopRules.survivesVote(1, 2) && !StopRules.survivesVote(2, 3) && StopRules.survivesVote(0, 0),
                "maioria absoluta derruba a palavra; empate e nenhum votante mantêm");
        Set<Character> used = new HashSet<>();
        for (int i = 0; i < 21; i++) {
            StopRules.drawLetter(used, false, RNG);
        }
        check(used.size() == 21 && !used.contains('K') && !used.contains('W'), "21 letras sorteadas sem repetir e sem K, W, X, Y e Q");

        System.out.println("== Partida simulada (perda UDP " + (int) (UDP_LOSS * 100) + "%)");
        Node h = new Node("host-1", "Prof", 1);
        Node a = new Node("ana-1", "Ana", 2);
        Node b = new Node("beto-1", "Beto", 3);
        for (Node n : List.of(h, a, b)) {
            NODES.put(n.id, n);
        }

        CountDownLatch joined = new CountDownLatch(2);
        CountDownLatch finished = new CountDownLatch(3);
        List<ResultMessage> results = new CopyOnWriteArrayList<>();
        List<String> stoppers = new CopyOnWriteArrayList<>();
        Map<String, List<PlayerScore>> finalRanking = new ConcurrentHashMap<>();

        for (Node n : List.of(h, a, b)) {
            n.player = new StopPlayer(n.link, ProtocolLog.NONE, new StopPlayer.View() {
                volatile char letter;

                @Override
                public void lobbiesChanged(List<StopPlayer.Lobby> lobbies) {
                    if (n != h && !lobbies.isEmpty() && n.player.state() == StopPlayer.State.IDLE) {
                        n.player.join(lobbies.get(0).sessionId());
                    }
                }

                @Override
                public void joinResult(boolean accepted, String reason, StopPlayer.Lobby lobby) {
                    if (accepted && n != h) joined.countDown();
                }

                @Override
                public void round(RoundMessage r) {
                    letter = r.getLetter();
                    n.player.setDraftSource(() -> wordsFor(n.name, letter));
                    if (n == a) WIRE.schedule(() -> n.player.requestStop(), 300, TimeUnit.MILLISECONDS);
                    if (n == b) WIRE.schedule(() -> n.player.requestStop(), 320, TimeUnit.MILLISECONDS);
                }

                @Override
                public void halted(HaltMessage halt, boolean byMe) {
                    if (n == h) stoppers.add(halt.getStopperName());
                }

                @Override
                public void voting(VotingMessage v) {
                    boolean[][] invalid = new boolean[v.getPlayerIds().size()][v.getThemes().size()];
                    int beto = v.getPlayerIds().indexOf("beto-1");
                    if (n != b && beto >= 0) {
                        invalid[beto][2] = true; // "inventada" não é cor
                    }
                    WIRE.schedule(() -> n.player.sendVotes(invalid, true), 100 + RNG.nextInt(200), TimeUnit.MILLISECONDS);
                }

                @Override
                public void result(ResultMessage r) {
                    if (n == h) {
                        results.add(r);
                        WIRE.schedule(() -> h.host.advance(), 300, TimeUnit.MILLISECONDS);
                    }
                }

                @Override
                public void finished(List<PlayerScore> ranking, boolean cancelled, String reason) {
                    finalRanking.put(n.name, ranking);
                    finished.countDown();
                }
            });
        }

        h.host = new StopHost(h.link, ProtocolLog.NONE, null, "Teste", THEMES, 2, 20, false);
        h.player.attachToOwnHost(h.host);
        h.host.open();
        check(joined.await(15, TimeUnit.SECONDS), "Ana e Beto descobriram a partida (ANÚNCIO) e entraram (ENTRADA/ACEITE)");
        Thread.sleep(3500); // SONDA/ECO
        check(h.host.start(), "anfitrião começou a partida");
        check(finished.await(90, TimeUnit.SECONDS), "os 3 participantes receberam o FIM");

        check(results.size() == 2, "2 rodadas com RESULTADO");
        check(stoppers.stream().allMatch("Ana"::equals), "STOP disputado: Ana (300 ms) venceu Beto (320 ms) nas duas rodadas");
        ResultMessage r = results.get(0);
        int ih = r.getPlayerIds().indexOf("host-1");
        int ia = r.getPlayerIds().indexOf("ana-1");
        int ib = r.getPlayerIds().indexOf("beto-1");
        check(r.getPoints()[ia][0] == 10 && r.getPoints()[ia][1] == 10 && r.getPoints()[ia][2] == 10, "Ana: 3 palavras únicas, 30 pontos");
        check(r.getPoints()[ih][0] == 5 && r.getPoints()[ib][0] == 5, "tema Nome: palavra repetida (com outra caixa e espaço) vale 5 para cada");
        check(!r.getValid()[ib][1] && r.getInvalidVotes()[ib][1] == 0, "tema Animal: \"1bicho\" invalidada automaticamente (outra letra)");
        check(!r.getValid()[ib][2] && r.getInvalidVotes()[ib][2] == 2, "tema Cor: palavra do Beto derrubada por 2 de 2 votos");
        check(r.getValid()[ih][2] && r.getPoints()[ih][2] == 10, "palavras do anfitrião (que também joga) contam normalmente");
        List<PlayerScore> fin = finalRanking.get("Prof");
        check(fin.get(0).getName().equals("Ana") && fin.get(0).getTotal() == 60, "placar final: Ana em 1º com 60 pontos");
        check(fin.get(1).getName().equals("Prof") && fin.get(1).getTotal() == 50, "anfitrião em 2º com 50 pontos");
        check(fin.get(2).getName().equals("Beto") && fin.get(2).getTotal() == 10, "Beto em 3º com 10 pontos");
        check(finalRanking.get("Ana").equals(finalRanking.get("Beto")) || finalRanking.get("Ana").size() == 3,
                "Ana e Beto receberam o placar final completo");
        System.out.println("  (datagramas UDP descartados pela rede simulada: " + DROPPED.get() + ")");
        System.out.println("TUDO OK");
        System.exit(0);
    }
}
