package pitiupi.stop.net;

import java.util.List;

/** FIM: anfitrião → sala (UDP multicast, 3 cópias). Placar final ou cancelamento. */
public class EndMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private boolean cancelled;
    private List<PlayerScore> ranking;

    public EndMessage() {
    }

    public EndMessage(boolean cancelled, List<PlayerScore> ranking) {
        this.cancelled = cancelled;
        this.ranking = List.copyOf(ranking);
    }

    public boolean isCancelled() { return cancelled; }
    public List<PlayerScore> getRanking() { return ranking; }

    @Override public String type() { return "FIM"; }
}
