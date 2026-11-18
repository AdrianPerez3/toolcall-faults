package toolcallfaults.server;
import java.util.List;
import java.util.Map;
public record Exchange(String method, String pathPattern, int status, Map<String,String> headers,
                       BodyKind kind, List<byte[]> parts, End end, long stallMs, int[] delaysMs) {}
