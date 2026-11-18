package toolcallfaults.server;
import java.util.List;
public record Script(List<Exchange> exchanges) {
    public Script { exchanges = List.copyOf(exchanges); if (exchanges.isEmpty()) throw new IllegalArgumentException("Empty script"); }
}
