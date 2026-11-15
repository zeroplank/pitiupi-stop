package pitiupi.stop.net;

/** ACEITE: anfitrião → jogador (TCP). Aceita ou recusa a entrada, com o motivo. */
public class JoinAckMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private boolean accepted;
    private String reason;

    public JoinAckMessage() {
    }

    public JoinAckMessage(boolean accepted, String reason) {
        this.accepted = accepted;
        this.reason = reason;
    }

    public boolean isAccepted() { return accepted; }
    public String getReason() { return reason; }

    @Override public String type() { return "ACEITE"; }
}
