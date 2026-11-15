package pitiupi.stop.control;

/** Registro didático de cada mensagem do protocolo (painel "Protocolo STOP/1"). */
public interface ProtocolLog {

    void sent(String type, String transport, String to, String detail);

    void received(String type, String from, String detail);

    void note(String text);

    ProtocolLog NONE = new ProtocolLog() {
        @Override public void sent(String type, String transport, String to, String detail) { }
        @Override public void received(String type, String from, String detail) { }
        @Override public void note(String text) { }
    };
}
