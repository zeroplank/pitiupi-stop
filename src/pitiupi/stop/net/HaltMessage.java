package pitiupi.stop.net;

/**
 * PARADA: anfitrião → sala (UDP multicast, 3 cópias; depois TCP para quem não respondeu).
 * Fecha a rodada: todos param de escrever e enviam as RESPOSTAS.
 */
public class HaltMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    public enum Reason { STOP, TIME_UP, CANCELLED }

    private int round;
    private Reason reason;
    private String stopperId;
    private String stopperName;

    public HaltMessage() {
    }

    public HaltMessage(int round, Reason reason, String stopperId, String stopperName) {
        this.round = round;
        this.reason = reason;
        this.stopperId = stopperId;
        this.stopperName = stopperName;
    }

    public int getRound() { return round; }
    public Reason getReason() { return reason; }
    public String getStopperId() { return stopperId; }
    public String getStopperName() { return stopperName; }

    @Override public String type() { return "PARADA"; }
}
