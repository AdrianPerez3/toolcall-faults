package toolcallfaults.runner.fixture;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
public final class FixtureTestSupport {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static ObjectNode example() throws Exception {
        return (ObjectNode) JSON.readTree(Path.of("src/test/resources/spec-example-s03.json").toFile());
    }
    public static Path save(Path dir, ObjectNode node) throws Exception {
        Path file=dir.resolve("fixtures/"+node.path("id").asText()+".json");Files.createDirectories(file.getParent());
        Files.writeString(file,JSON.writeValueAsString(node));return file;
    }
    public static void hashes(ObjectNode node) throws Exception {
        ArrayNode hashes=JSON.createArrayNode();
        for(JsonNode e:node.path("exchanges")) {
            var body=e.path("response").path("body");String bytes=body.has("sse")?String.join("",JSON.convertValue(body.get("sse"),String[].class)):body.path("text").asText();
            hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        ((ObjectNode)node.path("provenance")).set("body_sha256",hashes);
    }
    public static ObjectNode response(ObjectNode n){return (ObjectNode)n.path("exchanges").get(0).path("response");}
}
