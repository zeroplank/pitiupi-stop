package pitiupi.stop.net;

/** ECO: jogador → anfitrião (UDP unicast). Devolve o {@code probeId} da SONDA. */
public class EchoMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private long probeId;

    public EchoMessage() {
    }

    public EchoMessage(long probeId) {
        this.probeId = probeId;
    }

    public long getProbeId() { return probeId; }

    @Override public String type() { return "ECO"; }
    @Override public boolean isForHost() { return true; }
}
