package pitiupi.stop.control;

import pitiupi.stop.net.StopMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/** Descarta cópias repetidas de um pacote pela chave (senderId, seq). */
final class Dedup {

    private static final int CAPACITY = 1024;

    private final Map<String, Boolean> seen = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CAPACITY;
        }
    };

    synchronized boolean firstTime(StopMessage m) {
        return seen.put(m.getSenderId() + "#" + m.getSeq(), Boolean.TRUE) == null;
    }
}
