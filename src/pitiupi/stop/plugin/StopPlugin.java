package pitiupi.stop.plugin;

import pitiupi.api.plugin.PeerActionsRegistrar;
import pitiupi.api.plugin.PluginWorkspace;
import pitiupi.application.peeractions.PeerAction;
import pitiupi.application.peeractions.PeerActionTarget;
import pitiupi.control.ConnectionManager;
import pitiupi.models.Messages.Message;
import pitiupi.models.Plugin;
import pitiupi.stop.GUI.StopPanel;
import pitiupi.stop.control.Link;
import pitiupi.stop.control.PitiupiTransport;
import pitiupi.stop.control.StopHost;
import pitiupi.stop.control.StopPlayer;
import pitiupi.stop.net.AnnounceMessage;
import pitiupi.stop.net.AnswersMessage;
import pitiupi.stop.net.EchoMessage;
import pitiupi.stop.net.EndMessage;
import pitiupi.stop.net.HaltMessage;
import pitiupi.stop.net.JoinAckMessage;
import pitiupi.stop.net.JoinMessage;
import pitiupi.stop.net.LeaveMessage;
import pitiupi.stop.net.ProbeMessage;
import pitiupi.stop.net.ReceiptMessage;
import pitiupi.stop.net.ResultMessage;
import pitiupi.stop.net.RoundMessage;
import pitiupi.stop.net.StartMessage;
import pitiupi.stop.net.StopCallMessage;
import pitiupi.stop.net.StopMessage;
import pitiupi.stop.net.VotesMessage;
import pitiupi.stop.net.VotingMessage;

import java.util.List;

/**
 * Stop (Adedonha) multiplayer sobre o Pitiupi.
 *
 * <p>Quem abre a partida é o anfitrião e também joga. Os outros descobrem a
 * partida pelo ANÚNCIO em multicast e entram por TCP. A cada rodada sai uma
 * letra; quem preencher todos os temas pede STOP; depois todos votam nas
 * palavras dos outros. O protocolo está em {@code plugins/stop/README.md}.</p>
 */
public class StopPlugin extends Plugin {

    private PitiupiTransport transport;
    private StopPanel panel;
    private PluginWorkspace workspace;

    @Override
    public void createPlugin(ConnectionManager connectionManager, PluginWorkspace workspace) {
        this.workspace = workspace;
        this.transport = new PitiupiTransport(connectionManager);
        Link link = new Link(transport, this::route);
        this.panel = new StopPanel(
                (log, view) -> new StopPlayer(link, log, view),
                (title, themes, rounds, seconds, hard, log, view) ->
                        new StopHost(link, log, view, title, themes, rounds, seconds, hard)
        );
        bindShellWorkspaceContent(panel);
        installPeerActions(workspace.peerActions());
    }

    @Override
    public void installPeerActions(PeerActionsRegistrar registrar) {
        registrar.register(PeerAction.of(
                "stop.open",
                "Abrir o Stop",
                PeerActionTarget.ALL_PEERS,
                target -> openTab()
        ));
        registrar.register(PeerAction.of(
                "stop.open.peer",
                "Jogar Stop",
                PeerActionTarget.SINGLE_PEER,
                target -> openTab()
        ));
    }

    private void openTab() {
        panel.setSelfId(transport.selfId());
        workspace.openOrFocusTab(getIdentifier(), getDisplayName());
    }

    @Override
    public List<Class<? extends Message>> getMessages() {
        return List.of(
                AnnounceMessage.class, JoinMessage.class, JoinAckMessage.class, LeaveMessage.class,
                ProbeMessage.class, EchoMessage.class, StartMessage.class, RoundMessage.class,
                StopCallMessage.class, HaltMessage.class, AnswersMessage.class, VotingMessage.class,
                VotesMessage.class, ReceiptMessage.class, ResultMessage.class, EndMessage.class
        );
    }

    /**
     * Mensagens vindas da rede. As cópias do próprio multicast que voltam
     * pela rede são descartadas: o {@link Link} já as entregou localmente.
     */
    @Override
    public void receiveMessage(Message message) {
        if (!(message instanceof StopMessage m) || panel == null) {
            return;
        }
        if (m.getVersion() != StopMessage.PROTOCOL_VERSION || transport.selfId().equals(m.getSenderId())) {
            return;
        }
        route(m);
    }

    /** Mensagens para o anfitrião vão ao {@link StopHost} ativo; as demais, ao {@link StopPlayer}. */
    private void route(StopMessage m) {
        if (panel == null) {
            return;
        }
        panel.setSelfId(transport.selfId());
        if (m.isForHost()) {
            StopHost host = panel.host();
            if (host != null) {
                host.handle(m);
            }
        } else {
            panel.player().handle(m);
        }
    }

    @Override
    public void onShellTabClosed() {
        if (panel != null) {
            panel.stopAll();
        }
    }

    @Override
    public String getName() {
        return "Stop";
    }

    @Override
    public String getDisplayName() {
        return "Stop (Adedonha)";
    }

    @Override
    public String getDescription() {
        return "Stop multiplayer: letra sorteada, temas, pedido de STOP disputado pela rede e votação entre os jogadores.";
    }

    @Override
    public String getAuthor() {
        return "Bruno";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public String getIdentifier() {
        return "pitiupi.stop";
    }
}
