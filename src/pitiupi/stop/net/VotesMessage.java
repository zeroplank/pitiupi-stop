package pitiupi.stop.net;

/**
 * VOTOS: jogador → anfitrião (TCP).
 *
 * <p>{@code invalid[jogador][tema] = true} quando quem vota considera a
 * palavra inválida, na ordem de jogadores da VOTAÇÃO. Pode ser enviada mais
 * de uma vez; vale a última. {@code confirmed} indica que o jogador terminou.</p>
 */
public class VotesMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private int round;
    private boolean[][] invalid;
    private boolean confirmed;

    public VotesMessage() {
    }

    public VotesMessage(int round, boolean[][] invalid, boolean confirmed) {
        this.round = round;
        this.invalid = invalid;
        this.confirmed = confirmed;
    }

    public int getRound() { return round; }
    public boolean[][] getInvalid() { return invalid; }
    public boolean isConfirmed() { return confirmed; }

    @Override public String type() { return "VOTOS"; }
    @Override public boolean isForHost() { return true; }
}
