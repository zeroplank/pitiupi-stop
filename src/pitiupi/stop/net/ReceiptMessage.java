package pitiupi.stop.net;

/**
 * RECIBO: anfitrião → jogador (TCP). Confirmação, na aplicação, de que um
 * STOP, RESPOSTAS ou VOTOS foi registrado (ou do motivo de não ter sido).
 */
public class ReceiptMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    public enum Status {
        ACCEPTED("registrado"),
        DUPLICATE("já registrado antes"),
        LATE("fora do prazo"),
        NOT_IN_GAME("jogador fora da partida"),
        WRONG_ROUND("rodada diferente da atual");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean ok() {
            return this == ACCEPTED || this == DUPLICATE;
        }
    }

    private String kind;
    private int round;
    private Status status;

    public ReceiptMessage() {
    }

    public ReceiptMessage(String kind, int round, Status status) {
        this.kind = kind;
        this.round = round;
        this.status = status;
    }

    /** Tipo da mensagem confirmada: "STOP", "RESPOSTAS" ou "VOTOS". */
    public String getKind() { return kind; }
    public int getRound() { return round; }
    public Status getStatus() { return status; }

    @Override public String type() { return "RECIBO"; }
}
