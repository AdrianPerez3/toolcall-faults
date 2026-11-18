package toolcallfaults.runner.results;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record RunRecord(
    String cell,
    int rep,
    String surface,
    JsonNode adapter,
    JsonNode server,
    String category,
    JsonNode attributes,
    JsonNode invocations,
    String fixtureId,
    List<String> bodySha256,
    String suiteVersion,
    String date,
    String imageDigest) {}
