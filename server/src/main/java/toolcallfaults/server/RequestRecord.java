package toolcallfaults.server;
import java.util.Map;
public record RequestRecord(String cellId, int seq, String method, String path, int exchangeIndex,
                            String userAgent, boolean hasCredentialHeader, Map<String,String> retryHeaders,
                            String body, long receivedAtMs, long finishedAtMs, boolean servedAsScripted) {}
