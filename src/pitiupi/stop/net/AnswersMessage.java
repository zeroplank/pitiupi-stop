package pitiupi.stop.net;

import java.util.List;

/** RESPOSTAS: jogador → anfitrião (TCP). As palavras do jogador, na ordem dos temas. */
public class AnswersMessage extends StopMessage {

    private static final long serialVersionUID = 1L;

    private int round;
    private List<String> words;

    public AnswersMessage() {
    }

    public AnswersMessage(int round, List<String> words) {
        this.round = round;
        this.words = List.copyOf(words);
    }

    public int getRound() { return round; }
    public List<String> getWords() { return words; }

    @Override public String type() { return "RESPOSTAS"; }
    @Override public boolean isForHost() { return true; }
}
