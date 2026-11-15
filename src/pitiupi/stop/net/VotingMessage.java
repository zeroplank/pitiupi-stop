package pitiupi.stop.net;

import java.util.List;

/**
 * VOTAÇÃO: anfitrião → sala (UDP multicast, 3 cópias).
 *
 * <p>Tabela com as palavras de todos ({@code words[jogador][tema]}) e as
 * que já foram invalidadas automaticamente (vazias ou com outra letra).</p>
 */
public class VotingMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private int round;
    private char letter;
    private List<String> themes;
    private List<String> playerIds;
    private List<String> playerNames;
    private String[][] words;
    private boolean[][] autoInvalid;
    private int voteTimeMillis;

    public VotingMessage() {
    }

    public VotingMessage(int round, char letter, List<String> themes, List<String> playerIds,
                         List<String> playerNames, String[][] words, boolean[][] autoInvalid, int voteTimeMillis) {
        this.round = round;
        this.letter = letter;
        this.themes = List.copyOf(themes);
        this.playerIds = List.copyOf(playerIds);
        this.playerNames = List.copyOf(playerNames);
        this.words = words;
        this.autoInvalid = autoInvalid;
        this.voteTimeMillis = voteTimeMillis;
    }

    public int getRound() { return round; }
    public char getLetter() { return letter; }
    public List<String> getThemes() { return themes; }
    public List<String> getPlayerIds() { return playerIds; }
    public List<String> getPlayerNames() { return playerNames; }
    public String[][] getWords() { return words; }
    public boolean[][] getAutoInvalid() { return autoInvalid; }
    public int getVoteTimeMillis() { return voteTimeMillis; }

    @Override public String type() { return "VOTAÇÃO"; }
}
