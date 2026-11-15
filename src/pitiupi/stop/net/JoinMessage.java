package pitiupi.stop.net;

/** ENTRADA: jogador → anfitrião (TCP). Pedido para entrar na sala de espera. */
public class JoinMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    public JoinMessage() {
    }

    @Override public String type() { return "ENTRADA"; }
    @Override public boolean isForHost() { return true; }
}
