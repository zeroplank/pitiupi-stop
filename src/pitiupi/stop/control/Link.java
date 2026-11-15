package pitiupi.stop.control;

import pitiupi.stop.net.StopMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Envio com "entrega local". O anfitrião também joga, então uma mensagem
 * do anfitrião para ele mesmo não passa pela rede: é serializada e
 * desserializada (para se comportar igual a uma cópia vinda da rede) e
 * entregue numa thread própria, na ordem de envio.
 *
 * <p>O multicast do anfitrião também é entregue localmente, porque o plugin
 * descarta as cópias do próprio multicast que voltam pela rede.</p>
 */
public final class Link {

    public enum Mode { UDP, TCP }

    private final StopTransport net;
    private final Consumer<StopMessage> localRouter;
    private final ExecutorService local = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stop-local");
        t.setDaemon(true);
        return t;
    });

    public Link(StopTransport net, Consumer<StopMessage> localRouter) {
        this.net = net;
        this.localRouter = localRouter;
    }

    public String selfId() {
        return net.selfId();
    }

    public String selfName() {
        return net.selfName();
    }

    public boolean isSelf(String id) {
        return net.selfId().equals(id);
    }

    public Optional<InetAddress> addressOf(String id) {
        return net.addressOf(id);
    }

    /** O participante ainda envia sinal de presença? Para si mesmo é sempre verdade. */
    public boolean isOnline(String id) {
        return isSelf(id) || net.addressOf(id).isPresent();
    }

    public boolean multicast(StopMessage m) {
        deliverLocally(m);
        return net.multicast(m);
    }

    public boolean send(String peerId, StopMessage m, Mode mode) {
        if (isSelf(peerId)) {
            deliverLocally(m);
            return true;
        }
        InetAddress addr = net.addressOf(peerId).orElse(null);
        if (addr == null) {
            return false;
        }
        return mode == Mode.TCP ? net.tcp(m, addr) : net.unicastUdp(m, addr);
    }

    private void deliverLocally(StopMessage m) {
        byte[] bytes;
        try {
            bytes = m.toByteArray();
        } catch (IOException ex) {
            return;
        }
        if (local.isShutdown()) {
            return;
        }
        local.execute(() -> {
            try (ObjectInputStream in = new PluginStream(new ByteArrayInputStream(bytes))) {
                localRouter.accept((StopMessage) in.readObject());
            } catch (IOException | ClassNotFoundException ex) {
                // cópia local corrompida: impossível na prática
            }
        });
    }

    /** Desserializa com o carregador de classes do plugin (cada JAR tem o seu). */
    private static final class PluginStream extends ObjectInputStream {
        PluginStream(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            try {
                return Class.forName(desc.getName(), false, Link.class.getClassLoader());
            } catch (ClassNotFoundException ex) {
                return super.resolveClass(desc);
            }
        }
    }
}
