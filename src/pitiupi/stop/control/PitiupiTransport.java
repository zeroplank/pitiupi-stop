package pitiupi.stop.control;

import pitiupi.application.messaging.MessageDispatcher;
import pitiupi.application.messaging.SendTarget;
import pitiupi.application.messaging.TransportMode;
import pitiupi.application.presence.UserPeerView;
import pitiupi.control.ConnectionManager;
import pitiupi.stop.net.StopMessage;

import java.net.InetAddress;
import java.util.Optional;

/** Liga o jogo ao núcleo: todo envio passa pelo {@link MessageDispatcher}. */
public final class PitiupiTransport implements StopTransport {

    private final ConnectionManager cm;

    public PitiupiTransport(ConnectionManager cm) {
        this.cm = cm;
    }

    @Override
    public String selfId() {
        return cm.getHeartbeatManager().getInstanceId();
    }

    @Override
    public String selfName() {
        return cm.getUserName();
    }

    @Override
    public boolean multicast(StopMessage message) {
        return MessageDispatcher.send(cm, message, SendTarget.broadcastAll(), TransportMode.MULTICAST_UDP) > 0;
    }

    @Override
    public boolean unicastUdp(StopMessage message, InetAddress address) {
        return MessageDispatcher.send(cm, message, SendTarget.singlePeer(address), TransportMode.UNICAST_UDP) > 0;
    }

    @Override
    public boolean tcp(StopMessage message, InetAddress address) {
        return MessageDispatcher.send(cm, message, SendTarget.singlePeer(address), TransportMode.TCP) > 0;
    }

    @Override
    public Optional<InetAddress> addressOf(String instanceId) {
        if (instanceId == null) {
            return Optional.empty();
        }
        for (UserPeerView v : cm.getPresenceService().listPeerViews()) {
            if (!v.isBroadcastTarget() && !v.isSelf() && instanceId.equals(v.getInstanceId())) {
                return v.getInetAddress();
            }
        }
        return Optional.empty();
    }
}
