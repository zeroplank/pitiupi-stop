package pitiupi.stop.GUI;

import pitiupi.stop.control.ProtocolLog;
import pitiupi.stop.control.StopHost;
import pitiupi.stop.control.StopPlayer;
import pitiupi.stop.control.StopRules;
import pitiupi.stop.net.HaltMessage;
import pitiupi.stop.net.PlayerScore;
import pitiupi.stop.net.ResultMessage;
import pitiupi.stop.net.RoundMessage;
import pitiupi.stop.net.StartMessage;
import pitiupi.stop.net.VotingMessage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Painel da aba central do Stop: criar ou entrar numa partida, preencher as
 * palavras, votar, ver o resultado e acompanhar o log do protocolo.
 */
public final class StopPanel extends JPanel implements ProtocolLog, StopHost.View, StopPlayer.View {

    /** Cria o anfitrião já ligado à rede; implementado pelo plugin. */
    public interface HostFactory {
        StopHost create(String title, List<String> themes, int rounds, int seconds, boolean hardLetters,
                        ProtocolLog log, StopHost.View view);
    }

    private static final String HOME = "home";
    private static final String LOBBY = "lobby";
    private static final String ROUND = "round";
    private static final String VOTING = "voting";
    private static final String RESULT = "result";
    private static final String END = "end";

    private static final Color OK = new Color(0x2E7D32);
    private static final Color BAD = new Color(0xC62828);
    private static final Color CELL_UNIQUE = new Color(0xC8E6C9);
    private static final Color CELL_REPEATED = new Color(0xFFF59D);
    private static final Color CELL_INVALID = new Color(0xFFCDD2);

    private final StopPlayer player;
    private final HostFactory hostFactory;
    private StopHost host;

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private String currentCard = HOME;
    private final JTextArea logArea = new JTextArea();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS");

    // início
    private final JTextField titleField = new JTextField("Stop da turma");
    private final JSpinner roundsSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 10, 1));
    private final JSpinner secondsSpinner = new JSpinner(new SpinnerNumberModel(120, 30, 300, 10));
    private final JCheckBox hardLetters = new JCheckBox("Incluir letras difíceis (K, Q, W, X e Y)");
    private final JPanel themeBoxes = new JPanel(new GridLayout(0, 2, 4, 0));
    private final List<JCheckBox> themeChecks = new ArrayList<>();
    private final JTextField newTheme = new JTextField();
    private final DefaultListModel<StopPlayer.Lobby> lobbyModel = new DefaultListModel<>();
    private final JList<StopPlayer.Lobby> lobbyList = new JList<>(lobbyModel);
    private final JButton joinButton = new JButton("Entrar na partida");
    private final JLabel homeStatus = new JLabel(" ");

    // sala de espera
    private final JLabel lobbyHeader = big(" ", 18f);
    private final JTextArea lobbyInfo = textBlock(13f, Font.PLAIN);
    private final DefaultTableModel lobbyTable = table("Jogador", "RTT (ms)", "Conectado");
    private final JButton lobbyLeave = new JButton("Sair da sala");

    // rodada
    private final JLabel letterLabel = big("?", 64f);
    private final JLabel roundHeader = big(" ", 18f);
    private final JProgressBar roundTimer = new JProgressBar();
    private final JPanel fieldsPanel = new JPanel(new GridBagLayout());
    private final List<JTextField> fields = new ArrayList<>();
    private final JButton stopButton = new JButton("STOP!");
    private final JLabel roundStatus = new JLabel(" ");
    private volatile List<String> draftSnapshot = List.of();
    private char letter = '?';

    // votação
    private final JLabel votingHeader = big(" ", 18f);
    private final JProgressBar votingTimer = new JProgressBar();
    private final JPanel votingGrid = new JPanel(new GridBagLayout());
    private final JButton confirmVotes = new JButton("Confirmar votos");
    private final JLabel votingStatus = new JLabel(" ");
    private VotingMessage voting;
    private JCheckBox[][] voteBoxes;
    private boolean votesConfirmed;

    // resultado
    private final JLabel resultHeader = big(" ", 18f);
    private final DefaultTableModel resultTable = table();
    private final JTable resultView = new JTable(resultTable);
    private final DefaultTableModel rankingTable = table("Posição", "Jogador", "Pontos na rodada", "Total");
    private final JLabel resultStatus = new JLabel(" ");
    private ResultMessage lastResult;

    // fim
    private final JLabel endHeader = big(" ", 20f);
    private final JTextArea podium = textBlock(16f, Font.BOLD);
    private final DefaultTableModel endTable = table("Posição", "Jogador", "Total");

    // barra do anfitrião
    private final JPanel hostBar = new JPanel(new BorderLayout(8, 0));
    private final JLabel hostInfo = new JLabel(" ");
    private final JButton startButton = new JButton("Começar partida");
    private final JButton closeVotingButton = new JButton("Encerrar votação");
    private final JButton nextButton = new JButton("Próxima rodada");
    private final JButton cancelButton = new JButton("Cancelar partida");

    private Timer countdown;

    public StopPanel(java.util.function.BiFunction<ProtocolLog, StopPlayer.View, StopPlayer> playerFactory,
                     HostFactory hostFactory) {
        super(new BorderLayout());
        this.hostFactory = hostFactory;
        this.player = playerFactory.apply(this, this);
        player.setDraftSource(() -> draftSnapshot);

        deck.add(buildHome(), HOME);
        deck.add(buildLobby(), LOBBY);
        deck.add(buildRound(), ROUND);
        deck.add(buildVoting(), VOTING);
        deck.add(buildResult(), RESULT);
        deck.add(buildEnd(), END);

        JPanel top = new JPanel(new BorderLayout());
        top.add(deck, BorderLayout.CENTER);
        top.add(buildHostBar(), BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel logPanel = new JPanel(new BorderLayout());
        JPanel logTop = new JPanel(new BorderLayout());
        logTop.add(new JLabel("  Protocolo STOP/1: mensagens enviadas (→) e recebidas (←)"), BorderLayout.CENTER);
        JButton clear = new JButton("Limpar");
        clear.addActionListener(e -> logArea.setText(""));
        logTop.add(clear, BorderLayout.EAST);
        logPanel.add(logTop, BorderLayout.NORTH);
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, logPanel);
        split.setResizeWeight(0.75);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        showCard(HOME);
    }

    public StopPlayer player() {
        return player;
    }

    public StopHost host() {
        return host;
    }

    // ================================================================== telas

    private JComponent buildHome() {
        JPanel create = section("Criar partida (você será o anfitrião e também joga)");
        create.setLayout(new BoxLayout(create, BoxLayout.Y_AXIS));
        create.add(row("Título:", titleField));
        create.add(row("Rodadas:", roundsSpinner));
        create.add(row("Tempo máximo por rodada (segundos):", secondsSpinner));
        create.add(left(hardLetters));
        create.add(Box.createVerticalStrut(6));
        create.add(left(new JLabel("Temas:")));
        for (int i = 0; i < StopRules.DEFAULT_THEMES.size(); i++) {
            addThemeCheck(StopRules.DEFAULT_THEMES.get(i), i < StopRules.DEFAULT_SELECTED);
        }
        create.add(left(themeBoxes));
        JButton add = new JButton("Adicionar tema");
        add.addActionListener(e -> addCustomTheme());
        newTheme.addActionListener(e -> addCustomTheme());
        JPanel addRow = new JPanel(new BorderLayout(6, 0));
        addRow.add(new JLabel("Novo tema:"), BorderLayout.WEST);
        addRow.add(newTheme, BorderLayout.CENTER);
        addRow.add(add, BorderLayout.EAST);
        addRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, addRow.getPreferredSize().height));
        create.add(left(addRow));
        create.add(Box.createVerticalStrut(8));
        JButton open = new JButton("Abrir sala de espera");
        open.addActionListener(e -> openLobby());
        create.add(left(open));
        create.add(Box.createVerticalGlue());

        JPanel join = section("Partidas abertas na rede");
        join.setLayout(new BorderLayout(4, 4));
        lobbyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lobbyList.setCellRenderer((list, l, i, sel, focus) -> {
            JLabel label = new JLabel("<html><b>" + esc(l.title()) + "</b>, criada por " + esc(l.hostName())
                    + "<br>" + plural(l.rounds(), "rodada", "rodadas") + " de até " + l.secondsPerRound() + " s · "
                    + plural(l.themes().size(), "tema", "temas")
                    + "<br>" + plural(l.playerNames().size(), "jogador", "jogadores") + ": "
                    + esc(String.join(", ", l.playerNames())) + "</html>");
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            label.setBackground(sel ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(sel ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        lobbyList.addListSelectionListener(e -> joinButton.setEnabled(lobbyList.getSelectedValue() != null));
        joinButton.setEnabled(false);
        joinButton.addActionListener(e -> joinSelected());
        join.add(new JScrollPane(lobbyList), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(joinButton, BorderLayout.WEST);
        south.add(homeStatus, BorderLayout.SOUTH);
        join.add(south, BorderLayout.SOUTH);

        JPanel home = new JPanel(new GridLayout(1, 2, 8, 0));
        home.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        home.add(new JScrollPane(create));
        home.add(join);
        return home;
    }

    private JComponent buildLobby() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(left(lobbyHeader));
        top.add(lobbyInfo);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(new JTable(lobbyTable)), BorderLayout.CENTER);
        lobbyLeave.addActionListener(e -> leave());
        JPanel south = new JPanel();
        south.add(lobbyLeave);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildRound() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new BorderLayout(12, 0));
        letterLabel.setHorizontalAlignment(SwingConstants.CENTER);
        letterLabel.setPreferredSize(new Dimension(110, 90));
        letterLabel.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2, true));
        top.add(letterLabel, BorderLayout.WEST);
        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.add(left(roundHeader));
        info.add(left(new JLabel("Preencha todos os temas com palavras que começam com a letra sorteada.")));
        roundTimer.setStringPainted(true);
        info.add(left(roundTimer));
        top.add(info, BorderLayout.CENTER);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(fieldsPanel), BorderLayout.CENTER);

        stopButton.setFont(stopButton.getFont().deriveFont(Font.BOLD, 22f));
        stopButton.setBackground(BAD);
        stopButton.setForeground(Color.WHITE);
        stopButton.setPreferredSize(new Dimension(220, 52));
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> {
            if (player.requestStop()) {
                stopButton.setEnabled(false);
            }
        });
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.add(stopButton, BorderLayout.EAST);
        south.add(roundStatus, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildVoting() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(left(votingHeader));
        top.add(left(new JLabel("Desmarque as palavras que você considera inválidas. Uma palavra só cai se a maioria dos outros jogadores votar contra.")));
        votingTimer.setStringPainted(true);
        top.add(left(votingTimer));
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(votingGrid), BorderLayout.CENTER);
        confirmVotes.addActionListener(e -> sendVotes(true));
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.add(votingStatus, BorderLayout.CENTER);
        south.add(confirmVotes, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildResult() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(resultHeader, BorderLayout.NORTH);
        resultView.setRowHeight(24);
        resultView.setDefaultRenderer(Object.class, new ResultRenderer());
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(new JScrollPane(resultView), BorderLayout.CENTER);
        JScrollPane rankingScroll = new JScrollPane(new JTable(rankingTable));
        rankingScroll.setPreferredSize(new Dimension(100, 130));
        center.add(rankingScroll, BorderLayout.SOUTH);
        p.add(center, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JLabel("Verde: palavra única (10 pontos) · Amarelo: palavra repetida (5) · Vermelho: inválida (0)"), BorderLayout.NORTH);
        south.add(resultStatus, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildEnd() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(left(endHeader));
        top.add(podium);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(new JTable(endTable)), BorderLayout.CENTER);
        JButton back = new JButton("Voltar ao início");
        back.addActionListener(e -> {
            host = null;
            hostBar.setVisible(false);
            showCard(HOME);
        });
        JPanel south = new JPanel();
        south.add(back);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildHostBar() {
        hostBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        startButton.addActionListener(e -> {
            if (host != null && !host.start()) {
                hostInfo.setText("Espere pelo menos mais um jogador entrar na sala de espera.");
            }
        });
        closeVotingButton.addActionListener(e -> {
            if (host != null) {
                host.closeVotingNow();
            }
        });
        nextButton.addActionListener(e -> {
            if (host != null) {
                host.advance();
            }
        });
        cancelButton.addActionListener(e -> {
            if (host != null) {
                host.cancel();
            }
        });
        buttons.add(startButton);
        buttons.add(closeVotingButton);
        buttons.add(nextButton);
        buttons.add(cancelButton);
        JLabel tag = new JLabel("Anfitrião:");
        tag.setFont(tag.getFont().deriveFont(Font.BOLD));
        JPanel info = new JPanel(new BorderLayout(6, 0));
        info.add(tag, BorderLayout.WEST);
        info.add(hostInfo, BorderLayout.CENTER);
        hostBar.add(info, BorderLayout.CENTER);
        hostBar.add(buttons, BorderLayout.EAST);
        hostBar.setVisible(false);
        return hostBar;
    }

    // ================================================================== ações

    private void addThemeCheck(String name, boolean selected) {
        JCheckBox box = new JCheckBox(name, selected);
        themeChecks.add(box);
        themeBoxes.add(box);
    }

    private void addCustomTheme() {
        String name = newTheme.getText().strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            return;
        }
        for (JCheckBox b : themeChecks) {
            if (StopRules.normalize(b.getText()).equals(StopRules.normalize(name))) {
                b.setSelected(true);
                newTheme.setText("");
                return;
            }
        }
        addThemeCheck(name.length() > 40 ? name.substring(0, 40) : name, true);
        newTheme.setText("");
        themeBoxes.revalidate();
        themeBoxes.repaint();
    }

    private void openLobby() {
        List<String> themes = new ArrayList<>();
        for (JCheckBox b : themeChecks) {
            if (b.isSelected()) {
                themes.add(b.getText());
            }
        }
        if (themes.isEmpty()) {
            homeStatus.setText("Escolha pelo menos um tema.");
            return;
        }
        String title = titleField.getText().isBlank() ? "Stop da turma" : titleField.getText().strip();
        host = hostFactory.create(title, themes, (Integer) roundsSpinner.getValue(), (Integer) secondsSpinner.getValue(),
                hardLetters.isSelected(), this, this);
        hostBar.setVisible(true);
        player.attachToOwnHost(host);
        host.open();
    }

    private void joinSelected() {
        StopPlayer.Lobby lobby = lobbyList.getSelectedValue();
        if (lobby == null) {
            return;
        }
        homeStatus.setText("Enviando pedido de ENTRADA por TCP para " + lobby.hostName() + "…");
        joinButton.setEnabled(false);
        player.join(lobby.sessionId());
    }

    private void leave() {
        if (host != null) {
            host.cancel();
        } else {
            player.leave();
            stopCountdown();
            showCard(HOME);
        }
    }

    private void sendVotes(boolean confirm) {
        if (voting == null || voteBoxes == null) {
            return;
        }
        int n = voting.getPlayerIds().size();
        int themes = voting.getThemes().size();
        boolean[][] invalid = new boolean[n][themes];
        for (int j = 0; j < n; j++) {
            for (int t = 0; t < themes; t++) {
                JCheckBox b = voteBoxes[j][t];
                invalid[j][t] = b != null && b.isEnabled() && !b.isSelected();
            }
        }
        if (confirm) {
            votesConfirmed = true;
            confirmVotes.setText("Atualizar votos");
        }
        player.sendVotes(invalid, confirm || votesConfirmed);
    }

    /** Aba fechada: encerra o que estiver em andamento. */
    public void stopAll() {
        if (host != null) {
            host.cancel();
            host = null;
        }
        player.leave();
        stopCountdown();
        hostBar.setVisible(false);
        showCard(HOME);
    }

    // ================================================================== StopHost.View

    @Override
    public void playersChanged(List<PlayerScore> players) {
        ui(() -> {
            if (!LOBBY.equals(currentCard)) {
                return;
            }
            lobbyTable.setRowCount(0);
            for (PlayerScore p : players) {
                lobbyTable.addRow(new Object[]{p.getName(), p.getRttMillis() < 0 ? "medindo…" : p.getRttMillis(),
                        p.isOnline() ? "sim" : "não"});
            }
        });
    }

    @Override
    public void stateChanged(StopHost.State state, int round, int totalRounds) {
        ui(() -> {
            startButton.setVisible(state == StopHost.State.LOBBY);
            closeVotingButton.setVisible(state == StopHost.State.VOTING);
            nextButton.setVisible(state == StopHost.State.RESULT);
            nextButton.setText(round + 1 >= totalRounds ? "Ver placar final" : "Próxima rodada");
            cancelButton.setVisible(state != StopHost.State.FINISHED);
            hostInfo.setText(switch (state) {
                case LOBBY -> "o ANÚNCIO da partida sai a cada 2 s por UDP multicast. Comece quando todos tiverem entrado.";
                case COUNTDOWN -> "INÍCIO enviado. A primeira letra sai em 3 s.";
                case PLAYING -> "rodada " + (round + 1) + " em andamento. Esperando um STOP ou o fim do tempo.";
                case COLLECTING -> "PARADA enviada. Recebendo as RESPOSTAS…";
                case VOTING -> "votação aberta. Você pode encerrá-la antes do prazo.";
                case RESULT -> "rodada " + (round + 1) + " de " + totalRounds + " encerrada.";
                case FINISHED -> "partida encerrada.";
            });
            if (state == StopHost.State.FINISHED) {
                hostBar.setVisible(false);
            }
        });
    }

    @Override
    public void answersProgress(int received, int expected) {
        ui(() -> hostInfo.setText("RESPOSTAS recebidas: " + received + " de " + expected + "."));
    }

    @Override
    public void votesProgress(int confirmedCount, int expected) {
        ui(() -> hostInfo.setText("votos confirmados: " + confirmedCount + " de " + expected
                + ". Você pode encerrar a votação antes do prazo."));
    }

    // ================================================================== StopPlayer.View

    @Override
    public void lobbiesChanged(List<StopPlayer.Lobby> lobbies) {
        ui(() -> {
            StopPlayer.Lobby selected = lobbyList.getSelectedValue();
            lobbyModel.clear();
            for (StopPlayer.Lobby l : lobbies) {
                lobbyModel.addElement(l);
                if (selected != null && selected.sessionId().equals(l.sessionId())) {
                    lobbyList.setSelectedIndex(lobbyModel.size() - 1);
                }
            }
            joinButton.setEnabled(lobbyList.getSelectedValue() != null);
        });
    }

    @Override
    public void joinResult(boolean accepted, String reason, StopPlayer.Lobby lobby) {
        ui(() -> {
            joinButton.setEnabled(lobbyList.getSelectedValue() != null);
            if (!accepted) {
                homeStatus.setText("Não foi possível entrar: " + (reason == null ? "pedido recusado." : reason));
                return;
            }
            homeStatus.setText(" ");
            lobbyHeader.setText("Sala de espera: " + lobby.title());
            lobbyInfo.setText((host != null ? "Você é o anfitrião. " : "Anfitrião: " + lobby.hostName() + ". ")
                    + plural(lobby.rounds(), "rodada", "rodadas") + " de até " + lobby.secondsPerRound() + " s.\nTemas: "
                    + String.join(", ", lobby.themes()) + ".");
            lobbyTable.setRowCount(0);
            for (String name : lobby.playerNames()) {
                lobbyTable.addRow(new Object[]{name, "-", "sim"});
            }
            lobbyLeave.setText(host != null ? "Cancelar partida" : "Sair da sala");
            showCard(LOBBY);
        });
    }

    @Override
    public void lobbyPlayers(List<String> names) {
        if (host != null) {
            return; // o anfitrião vê a tabela completa (com RTT) em playersChanged
        }
        ui(() -> {
            if (!LOBBY.equals(currentCard)) {
                return;
            }
            lobbyTable.setRowCount(0);
            for (String name : names) {
                lobbyTable.addRow(new Object[]{name, "-", "sim"});
            }
        });
    }

    @Override
    public void started(StartMessage start) {
        ui(() -> lobbyHeader.setText("A partida vai começar! Prepare-se…"));
    }

    @Override
    public void round(RoundMessage r) {
        ui(() -> {
            letter = r.getLetter();
            letterLabel.setText(String.valueOf(letter));
            roundHeader.setText("Rodada " + (r.getRound() + 1) + " de " + r.getTotalRounds() + ": letra " + letter);
            buildFields(r.getThemes());
            stopButton.setEnabled(false);
            roundStatus.setText("Quando preencher todos os temas, o botão STOP é liberado.");
            roundStatus.setForeground(null);
            startCountdown(roundTimer, r.getTimeLimitMillis(), null);
            showCard(ROUND);
            if (!fields.isEmpty()) {
                fields.get(0).requestFocusInWindow();
            }
        });
    }

    @Override
    public void halted(HaltMessage h, boolean byMe) {
        ui(() -> {
            stopCountdown();
            for (JTextField f : fields) {
                f.setEditable(false);
            }
            stopButton.setEnabled(false);
            String text = switch (h.getReason()) {
                case STOP -> byMe ? "Você pediu STOP primeiro!" : "STOP! " + h.getStopperName() + " pediu primeiro.";
                case TIME_UP -> "Tempo esgotado!";
                case CANCELLED -> "Rodada cancelada.";
            };
            roundStatus.setText(text + " Enviando suas palavras…");
            roundStatus.setForeground(byMe ? OK : BAD);
            roundTimer.setValue(0);
            roundTimer.setString(text);
            if (!ROUND.equals(currentCard)) {
                showCard(ROUND);
            }
        });
    }

    @Override
    public void voting(VotingMessage v) {
        ui(() -> {
            voting = v;
            votesConfirmed = false;
            confirmVotes.setText("Confirmar votos");
            confirmVotes.setEnabled(true);
            votingHeader.setText("Votação da rodada " + (v.getRound() + 1) + ": letra " + v.getLetter());
            votingStatus.setText(" ");
            votingStatus.setForeground(null);
            buildVotingGrid(v);
            startCountdown(votingTimer, v.getVoteTimeMillis(), () -> {
                if (!votesConfirmed) {
                    sendVotes(false); // prazo acabando: envia o que estiver marcado
                }
            });
            showCard(VOTING);
        });
    }

    @Override
    public void result(ResultMessage r) {
        ui(() -> {
            stopCountdown();
            lastResult = r;
            resultHeader.setText("Resultado da rodada " + (r.getRound() + 1) + " de " + r.getTotalRounds()
                    + ": letra " + r.getLetter());
            List<String> cols = new ArrayList<>();
            cols.add("Tema");
            cols.addAll(r.getPlayerNames());
            resultTable.setColumnIdentifiers(cols.toArray());
            resultTable.setRowCount(0);
            for (int t = 0; t < r.getThemes().size(); t++) {
                Object[] row = new Object[cols.size()];
                row[0] = r.getThemes().get(t);
                for (int j = 0; j < r.getPlayerIds().size(); j++) {
                    String w = r.getWords()[j][t];
                    row[j + 1] = (w == null || w.isBlank() ? "(em branco)" : w) + " · " + r.getPoints()[j][t];
                }
                resultTable.addRow(row);
            }
            fillRanking(rankingTable, r.getRanking(), true);
            boolean last = r.getRound() + 1 >= r.getTotalRounds();
            resultStatus.setText(host != null
                    ? (last ? "Clique em \"Ver placar final\" para encerrar." : "Clique em \"Próxima rodada\" quando todos estiverem prontos.")
                    : (last ? "Aguardando o anfitrião mostrar o placar final…" : "Aguardando o anfitrião iniciar a próxima rodada…"));
            showCard(RESULT);
        });
    }

    @Override
    public void finished(List<PlayerScore> ranking, boolean cancelled, String reason) {
        ui(() -> {
            stopCountdown();
            if (ranking.isEmpty() && HOME.equals(currentCard)) {
                homeStatus.setText(reason == null ? " " : reason);
                return;
            }
            endHeader.setText(cancelled ? "Partida encerrada" : "Fim de jogo!");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, ranking.size()); i++) {
                PlayerScore p = ranking.get(i);
                sb.append(i + 1).append("º lugar: ").append(p.getName()).append(", ")
                        .append(plural(p.getTotal(), "ponto", "pontos")).append("\n");
            }
            if (reason != null) {
                sb.append(reason);
            }
            podium.setText(sb.toString().strip());
            fillRanking(endTable, ranking, false);
            hostBar.setVisible(false);
            showCard(END);
        });
    }

    @Override
    public void status(String text, boolean ok) {
        ui(() -> {
            JLabel target = switch (currentCard) {
                case VOTING -> votingStatus;
                case RESULT -> resultStatus;
                case ROUND -> roundStatus;
                default -> homeStatus;
            };
            target.setText(text);
            target.setForeground(ok ? null : BAD);
        });
    }

    // ================================================================== ProtocolLog

    @Override
    public void sent(String type, String transport, String to, String detail) {
        append("→ " + pad(type) + " [" + transport + "] para " + to + (detail == null ? "" : " · " + detail));
    }

    @Override
    public void received(String type, String from, String detail) {
        append("← " + pad(type) + " de " + from + (detail == null ? "" : " · " + detail));
    }

    @Override
    public void note(String text) {
        append("• " + text);
    }

    private void append(String line) {
        String stamped = clock.format(new Date()) + "  " + line + "\n";
        ui(() -> {
            logArea.append(stamped);
            if (logArea.getLineCount() > 3000) {
                logArea.setText("");
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ================================================================== construção dinâmica

    private void buildFields(List<String> themes) {
        fieldsPanel.removeAll();
        fields.clear();
        int columns = themes.size() > 6 ? 2 : 1;
        for (int i = 0; i < themes.size(); i++) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = (i % columns) * 2;
            c.gridy = i / columns;
            c.insets = new Insets(4, 6, 4, 6);
            c.anchor = GridBagConstraints.EAST;
            JLabel label = new JLabel(themes.get(i) + ":");
            label.setFont(label.getFont().deriveFont(14f));
            fieldsPanel.add(label, c);
            JTextField f = new JTextField(18);
            f.setFont(f.getFont().deriveFont(15f));
            f.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { onDraftChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { onDraftChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { onDraftChanged(); }
            });
            final int next = i + 1;
            f.addActionListener(e -> {
                if (next < fields.size()) {
                    fields.get(next).requestFocusInWindow();
                } else if (stopButton.isEnabled()) {
                    stopButton.doClick();
                }
            });
            fields.add(f);
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = c.gridx + 1;
            fc.gridy = c.gridy;
            fc.insets = c.insets;
            fc.fill = GridBagConstraints.HORIZONTAL;
            fc.weightx = 1;
            fieldsPanel.add(f, fc);
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = themes.size();
        filler.weighty = 1;
        fieldsPanel.add(new JLabel(), filler);
        onDraftChanged();
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    private void onDraftChanged() {
        List<String> snapshot = new ArrayList<>();
        boolean allValid = !fields.isEmpty();
        for (JTextField f : fields) {
            String text = f.getText();
            snapshot.add(text);
            String reason = StopRules.autoInvalidReason(text, letter);
            if (text.isBlank()) {
                f.setBorder(BorderFactory.createLineBorder(Color.GRAY));
                f.setToolTipText(null);
            } else if (reason != null) {
                f.setBorder(BorderFactory.createLineBorder(BAD, 2));
                f.setToolTipText("A palavra " + reason + ".");
            } else {
                f.setBorder(BorderFactory.createLineBorder(OK, 2));
                f.setToolTipText(null);
            }
            allValid &= reason == null;
        }
        draftSnapshot = Collections.unmodifiableList(snapshot);
        if (player.state() == StopPlayer.State.PLAYING && fields.stream().allMatch(JTextField::isEditable)) {
            stopButton.setEnabled(allValid);
        }
    }

    private void buildVotingGrid(VotingMessage v) {
        votingGrid.removeAll();
        int n = v.getPlayerIds().size();
        int themes = v.getThemes().size();
        voteBoxes = new JCheckBox[n][themes];
        Insets in = new Insets(3, 6, 3, 6);
        GridBagConstraints h = new GridBagConstraints();
        h.gridy = 0;
        h.insets = in;
        h.anchor = GridBagConstraints.WEST;
        h.gridx = 0;
        votingGrid.add(bold("Tema"), h);
        for (int j = 0; j < n; j++) {
            h.gridx = j + 1;
            boolean me = isMine(v, j);
            votingGrid.add(bold(v.getPlayerNames().get(j) + (me ? " (você)" : "")), h);
        }
        for (int t = 0; t < themes; t++) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = t + 1;
            c.insets = in;
            c.anchor = GridBagConstraints.WEST;
            c.gridx = 0;
            votingGrid.add(new JLabel(v.getThemes().get(t)), c);
            for (int j = 0; j < n; j++) {
                c.gridx = j + 1;
                String word = v.getWords()[j][t];
                JCheckBox box;
                if (v.getAutoInvalid()[j][t]) {
                    String reason = StopRules.autoInvalidReason(word, v.getLetter());
                    box = new JCheckBox((word == null || word.isBlank() ? "(em branco)" : word) + " ✘", false);
                    box.setEnabled(false);
                    box.setForeground(BAD);
                    box.setToolTipText("Invalidada automaticamente: " + reason + ".");
                } else {
                    box = new JCheckBox(word, true);
                    if (isMine(v, j)) {
                        box.setEnabled(false);
                        box.setToolTipText("Sua palavra: você não vota nela.");
                    } else {
                        box.setToolTipText("Desmarque se a palavra for inválida para o tema \"" + v.getThemes().get(t) + "\".");
                    }
                }
                voteBoxes[j][t] = box;
                votingGrid.add(box, c);
            }
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = n + 1;
        filler.gridy = themes + 1;
        filler.weighty = 1;
        filler.weightx = 1;
        votingGrid.add(new JLabel(), filler);
        votingGrid.revalidate();
        votingGrid.repaint();
    }

    private String myId;

    /** Id desta instância (o mesmo do sinal de presença), para reconhecer as próprias palavras na votação. */
    public void setSelfId(String id) {
        this.myId = id;
    }

    private boolean isMine(VotingMessage v, int j) {
        return myId != null && myId.equals(v.getPlayerIds().get(j));
    }

    private void fillRanking(DefaultTableModel model, List<PlayerScore> ranking, boolean withRound) {
        model.setRowCount(0);
        int pos = 1;
        for (PlayerScore p : ranking) {
            String name = p.getName() + (p.isOnline() ? "" : " (desconectado)");
            model.addRow(withRound
                    ? new Object[]{pos + "º", name, p.getRoundPoints(), p.getTotal()}
                    : new Object[]{pos + "º", name, p.getTotal()});
            pos++;
        }
    }

    // ================================================================== apoio

    private final class ResultRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, sel, focus, row, column);
            setToolTipText(null);
            if (column == 0 || lastResult == null) {
                c.setBackground(sel ? table.getSelectionBackground() : table.getBackground());
                c.setForeground(sel ? table.getSelectionForeground() : table.getForeground());
                return c;
            }
            int j = column - 1;
            int t = row;
            if (j >= lastResult.getPlayerIds().size() || t >= lastResult.getThemes().size()) {
                return c;
            }
            int pts = lastResult.getPoints()[j][t];
            c.setBackground(pts == StopRules.POINTS_UNIQUE ? CELL_UNIQUE : pts == StopRules.POINTS_REPEATED ? CELL_REPEATED : CELL_INVALID);
            c.setForeground(Color.BLACK);
            int against = lastResult.getInvalidVotes()[j][t];
            int voters = lastResult.getVoters()[j];
            if (!lastResult.getValid()[j][t] && against == 0) {
                setToolTipText("Invalidada automaticamente (em branco ou com outra letra).");
            } else {
                setToolTipText(against + " de " + voters + " votantes consideraram a palavra inválida.");
            }
            return c;
        }
    }

    private void showCard(String card) {
        currentCard = card;
        cards.show(deck, card);
    }

    private void startCountdown(JProgressBar bar, long limitMillis, Runnable nearEnd) {
        stopCountdown();
        long start = System.currentTimeMillis();
        bar.setMaximum((int) limitMillis);
        bar.setValue((int) limitMillis);
        boolean[] fired = {false};
        countdown = new Timer(200, e -> {
            long left = Math.max(0, limitMillis - (System.currentTimeMillis() - start));
            bar.setValue((int) left);
            bar.setString(String.format("%d s restantes", (left + 999) / 1000));
            if (nearEnd != null && !fired[0] && left <= 3000) {
                fired[0] = true;
                nearEnd.run();
            }
            if (left == 0) {
                ((Timer) e.getSource()).stop();
            }
        });
        countdown.start();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
    }

    private static String plural(int n, String singular, String pluralForm) {
        return n + " " + (Math.abs(n) == 1 ? singular : pluralForm);
    }

    private static DefaultTableModel table(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JLabel big(String text, float size) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, size));
        return l;
    }

    private static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JTextArea textBlock(float size, int style) {
        JTextArea t = new JTextArea(" ");
        t.setEditable(false);
        t.setFocusable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setOpaque(false);
        t.setBackground(new Color(0, 0, 0, 0));
        t.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        t.setFont(new JLabel().getFont().deriveFont(style, size));
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        return t;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static JComponent row(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return left(p);
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static String pad(String type) {
        return String.format("%-10s", type);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void ui(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
