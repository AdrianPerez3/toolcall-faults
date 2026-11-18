package toolcallfaults.runner.fixture;
import com.fasterxml.jackson.databind.JsonNode;
public record TruthCall(String id, String name, String argumentsRaw, boolean complete, JsonNode intendedArguments) {}
