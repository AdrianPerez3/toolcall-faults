package toolcallfaults.runner.oracle;
import com.fasterxml.jackson.databind.JsonNode;
public record Classified(Category category,String reason,JsonNode attributes) {}
