package pitiupi.stop.net;

/** SONDA: anfitrião → jogador (UDP unicast). Mede o RTT de cada jogador. */
public class ProbeMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private long probeId;

    public ProbeMessage() {
    }

    public ProbeMessage(long probeId) {
        this.probeId = probeId;
    }

    public long getProbeId() { return probeId; }

    @Override public String type() { return "SONDA"; }
}
