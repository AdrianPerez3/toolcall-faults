package toolcallfaults.runner.fixture;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
public record Fixture(String id, String scenario, String wire, String variant, String tier, boolean stream,
                      List<String> tools, String prompt, List<JsonNode> exchanges, JsonNode x, Truth truth,
                      List<String> removes, List<String> bodySha256, Path file) {
    /** A capture, or a named transform of one: its tool and shape are the recording's own. */
    public boolean fromRecording() { return tier.equals("recorded") || variant.startsWith("recorded"); }
}
