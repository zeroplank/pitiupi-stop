package pitiupi.stop.control;

import pitiupi.stop.net.StopMessage;

import java.net.InetAddress;
import java.util.Optional;

/** O que o jogo precisa da rede. A implementação real usa o núcleo; os testes usam uma falsa. */
public interface StopTransport {

    String selfId();

    String selfName();

    boolean multicast(StopMessage message);

    boolean unicastUdp(StopMessage message, InetAddress address);

    boolean tcp(StopMessage message, InetAddress address);

    /** Endereço de outro participante pela lista de presença do núcleo. */
    Optional<InetAddress> addressOf(String instanceId);
}
