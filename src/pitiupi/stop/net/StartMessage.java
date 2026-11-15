package pitiupi.stop.net;

import java.util.List;

/** INÍCIO: anfitrião → sala (UDP multicast, 3 cópias). Temas, número de rodadas e lista oficial de jogadores. */
public class StartMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private List<String> themes;
    private int rounds;
    private int secondsPerRound;
    private List<String> playerIds;
    private List<String> playerNames;

    public StartMessage() {
    }

    public StartMessage(List<String> themes, int rounds, int secondsPerRound,
                        List<String> playerIds, List<String> playerNames) {
        this.themes = List.copyOf(themes);
        this.rounds = rounds;
        this.secondsPerRound = secondsPerRound;
        this.playerIds = List.copyOf(playerIds);
        this.playerNames = List.copyOf(playerNames);
    }

    public List<String> getThemes() { return themes; }
    public int getRounds() { return rounds; }
    public int getSecondsPerRound() { return secondsPerRound; }
    public List<String> getPlayerIds() { return playerIds; }
    public List<String> getPlayerNames() { return playerNames; }

    @Override public String type() { return "INÍCIO"; }
}
