package pitiupi.stop.net;

import java.util.List;

/** RODADA: anfitrião → sala (UDP multicast, 3 cópias). Letra sorteada e prazo da rodada. */
public class RoundMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private int round;
    private int totalRounds;
    private char letter;
    private List<String> themes;
    private int timeLimitMillis;

    public RoundMessage() {
    }

    public RoundMessage(int round, int totalRounds, char letter, List<String> themes, int timeLimitMillis) {
        this.round = round;
        this.totalRounds = totalRounds;
        this.letter = letter;
        this.themes = List.copyOf(themes);
        this.timeLimitMillis = timeLimitMillis;
    }

    public int getRound() { return round; }
    public int getTotalRounds() { return totalRounds; }
    public char getLetter() { return letter; }
    public List<String> getThemes() { return themes; }
    public int getTimeLimitMillis() { return timeLimitMillis; }

    @Override public String type() { return "RODADA"; }
}
