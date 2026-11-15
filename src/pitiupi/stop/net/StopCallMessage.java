package pitiupi.stop.net;

/**
 * STOP: jogador → anfitrião (TCP). O jogador preencheu tudo e pediu para parar a rodada.
 *
 * <p>{@code elapsedMillis} é o tempo entre a chegada da RODADA e o clique,
 * medido no relógio do jogador. O anfitrião usa esse valor (conferido contra
 * o RTT) para decidir quem pediu primeiro quando dois pedidos chegam juntos.</p>
 */
public class StopCallMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private int round;
    private long elapsedMillis;

    public StopCallMessage() {
    }

    public StopCallMessage(int round, long elapsedMillis) {
        this.round = round;
        this.elapsedMillis = elapsedMillis;
    }

    public int getRound() { return round; }
    public long getElapsedMillis() { return elapsedMillis; }

    @Override public String type() { return "STOP"; }
    @Override public boolean isForHost() { return true; }
}
