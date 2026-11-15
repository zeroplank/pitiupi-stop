package pitiupi.stop.net;

import java.io.Serializable;

/** Linha do placar levada em RESULTADO e FIM. */
public class PlayerScore implements Serializable {

    private static final long serialVersionUID = 1L;

    private String playerId;
    private String name;
    private int total;
    private int roundPoints;
    private long rttMillis;
    private boolean online;

    public PlayerScore() {
    }

    public PlayerScore(String playerId, String name, int total, int roundPoints, long rttMillis, boolean online) {
        this.playerId = playerId;
        this.name = name;
        this.total = total;
        this.roundPoints = roundPoints;
        this.rttMillis = rttMillis;
        this.online = online;
    }

    public String getPlayerId() { return playerId; }
    public String getName() { return name; }
    public int getTotal() { return total; }
    public int getRoundPoints() { return roundPoints; }
    /** -1 = ainda sem medida. */
    public long getRttMillis() { return rttMillis; }
    public boolean isOnline() { return online; }
}
