package pitiupi.stop.control;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Regras do jogo, sem rede e sem interface, para poderem ser testadas sozinhas.
 */
public final class StopRules {

    /** Temas oferecidos na tela de criação; os 10 primeiros vêm marcados. */
    public static final List<String> DEFAULT_THEMES = List.of(
            "Nome", "Animal", "Cor", "Fruta, verdura ou legume", "Objeto", "Cidade, estado ou país",
            "Profissão", "Comida", "Parte do corpo", "Filme, série ou desenho",
            "Termo de redes", "Esporte", "Marca", "Instrumento musical", "Time de futebol", "Meio de transporte"
    );
    public static final int DEFAULT_SELECTED = 10;

    /** Letras que costumam ficar de fora por terem poucas palavras. */
    public static final String HARD_LETTERS = "KWXYQ";

    public static final int POINTS_UNIQUE = 10;
    public static final int POINTS_REPEATED = 5;

    /** Tolerância de relógio e escalonamento ao conferir o tempo informado pelo jogador. */
    public static final long TOLERANCE_MILLIS = 300;

    private StopRules() {
    }

    /** Minúsculas, sem acentos e com espaços simples: "  São  Paulo " vira "sao paulo". */
    public static String normalize(String word) {
        if (word == null) {
            return "";
        }
        String s = Normalizer.normalize(word, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toLowerCase().strip().replaceAll("\\s+", " ");
    }

    /** A palavra não está vazia e começa com a letra da rodada (ignorando acentos e maiúsculas)? */
    public static boolean startsWithLetter(String word, char letter) {
        String n = normalize(word);
        return !n.isEmpty() && n.charAt(0) == Character.toLowerCase(letter);
    }

    /** Motivo da invalidação automática, ou {@code null} se a palavra passa na checagem automática. */
    public static String autoInvalidReason(String word, char letter) {
        if (normalize(word).isEmpty()) {
            return "em branco";
        }
        if (!startsWithLetter(word, letter)) {
            return "não começa com " + letter;
        }
        return null;
    }

    /** Sorteia uma letra que ainda não saiu nesta partida. */
    public static char drawLetter(Set<Character> used, boolean includeHard, Random random) {
        List<Character> pool = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            if ((includeHard || HARD_LETTERS.indexOf(c) < 0) && !used.contains(c)) {
                pool.add(c);
            }
        }
        if (pool.isEmpty()) {
            used.clear();
            return drawLetter(used, includeHard, random);
        }
        char letter = pool.get(random.nextInt(pool.size()));
        used.add(letter);
        return letter;
    }

    /**
     * A palavra continua válida? Ela cai só se a maioria absoluta dos votantes
     * (sem contar o autor) votar contra. Empate ou nenhum votante: válida.
     */
    public static boolean survivesVote(int invalidVotes, int voters) {
        return invalidVotes * 2 <= voters;
    }

    /**
     * Pontos de cada palavra num tema: 10 se for válida e só um jogador a escreveu,
     * 5 se for válida e repetida, 0 se for inválida.
     *
     * @param words palavras dos jogadores neste tema
     * @param valid veredito de cada palavra
     */
    public static int[] pointsForTheme(String[] words, boolean[] valid) {
        Map<String, Integer> count = new HashMap<>();
        for (int j = 0; j < words.length; j++) {
            if (valid[j]) {
                count.merge(normalize(words[j]), 1, Integer::sum);
            }
        }
        int[] points = new int[words.length];
        for (int j = 0; j < words.length; j++) {
            if (valid[j]) {
                points[j] = count.get(normalize(words[j])) > 1 ? POINTS_REPEATED : POINTS_UNIQUE;
            }
        }
        return points;
    }

    /**
     * Tempo usado para decidir quem pediu STOP primeiro.
     * {@code hostObserved} = chegada do STOP − envio da RODADA, no relógio do anfitrião,
     * e inclui ≈ 1 RTT. Se o tempo informado pelo jogador estiver no intervalo plausível
     * [hostObserved − RTT − tolerância, hostObserved + tolerância], ele vale; senão vale
     * {@code hostObserved − RTT}.
     */
    public static long effectiveElapsed(long reported, long hostObserved, long rttMillis) {
        long rtt = Math.max(0, rttMillis);
        if (reported >= hostObserved - rtt - TOLERANCE_MILLIS && reported <= hostObserved + TOLERANCE_MILLIS) {
            return Math.max(0, reported);
        }
        return Math.max(0, hostObserved - rtt);
    }

    /** EstimatedRTT = 0,875 · EstimatedRTT + 0,125 · SampleRTT, como no TCP. */
    public static long smoothRtt(long estimated, long sample) {
        return estimated < 0 ? sample : Math.round(0.875 * estimated + 0.125 * sample);
    }
}
