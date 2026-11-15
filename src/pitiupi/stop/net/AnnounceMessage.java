package pitiupi.stop.net;

import java.util.List;

/** ANÚNCIO: anfitrião → sala (UDP multicast, a cada 2 s enquanto a sala de espera está aberta). */
public class AnnounceMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private String title;
    private List<String> themes;
    private int rounds;
    private int secondsPerRound;
    private List<String> playerNames;
    private boolean open;

    public AnnounceMessage() {
    }

    public AnnounceMessage(String title, List<String> themes, int rounds, int secondsPerRound,
                           List<String> playerNames, boolean open) {
        this.title = title;
        this.themes = List.copyOf(themes);
        this.rounds = rounds;
        this.secondsPerRound = secondsPerRound;
        this.playerNames = List.copyOf(playerNames);
        this.open = open;
    }

    public String getTitle() { return title; }
    public List<String> getThemes() { return themes; }
    public int getRounds() { return rounds; }
    public int getSecondsPerRound() { return secondsPerRound; }
    public List<String> getPlayerNames() { return playerNames; }
    /** {@code false}: a partida começou ou foi cancelada; sai da lista de partidas abertas. */
    public boolean isOpen() { return open; }

    @Override public String type() { return "ANÚNCIO"; }
}
