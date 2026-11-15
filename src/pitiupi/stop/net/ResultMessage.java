package pitiupi.stop.net;

import java.util.List;

/** RESULTADO: anfitrião → sala (UDP multicast, 3 cópias). Veredito de cada palavra, pontos e placar. */
public class ResultMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private int round;
    private int totalRounds;
    private char letter;
    private List<String> themes;
    private List<String> playerIds;
    private List<String> playerNames;
    private String[][] words;
    private boolean[][] valid;
    private int[][] points;
    private int[][] invalidVotes;
    private int[] voters;
    private List<PlayerScore> ranking;

    public ResultMessage() {
    }

    public ResultMessage(int round, int totalRounds, char letter, List<String> themes, List<String> playerIds,
                         List<String> playerNames, String[][] words, boolean[][] valid, int[][] points,
                         int[][] invalidVotes, int[] voters, List<PlayerScore> ranking) {
        this.round = round;
        this.totalRounds = totalRounds;
        this.letter = letter;
        this.themes = List.copyOf(themes);
        this.playerIds = List.copyOf(playerIds);
        this.playerNames = List.copyOf(playerNames);
        this.words = words;
        this.valid = valid;
        this.points = points;
        this.invalidVotes = invalidVotes;
        this.voters = voters;
        this.ranking = List.copyOf(ranking);
    }

    public int getRound() { return round; }
    public int getTotalRounds() { return totalRounds; }
    public char getLetter() { return letter; }
    public List<String> getThemes() { return themes; }
    public List<String> getPlayerIds() { return playerIds; }
    public List<String> getPlayerNames() { return playerNames; }
    public String[][] getWords() { return words; }
    public boolean[][] getValid() { return valid; }
    public int[][] getPoints() { return points; }
    public int[][] getInvalidVotes() { return invalidVotes; }
    /** voters[j] = quantos votantes podiam julgar as palavras do jogador j (todos menos ele). */
    public int[] getVoters() { return voters; }
    public List<PlayerScore> getRanking() { return ranking; }

    @Override public String type() { return "RESULTADO"; }
}
