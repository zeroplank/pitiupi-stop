package pitiupi.stop.net;

/** SAÍDA: jogador → anfitrião (TCP). O jogador saiu por vontade própria. */
public class LeaveMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    public LeaveMessage() {
    }

    @Override public String type() { return "SAÍDA"; }
    @Override public boolean isForHost() { return true; }
}
