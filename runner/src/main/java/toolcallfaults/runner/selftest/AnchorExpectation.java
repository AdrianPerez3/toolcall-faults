package toolcallfaults.runner.selftest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import toolcallfaults.runner.oracle.Category;

/** An anchor always compares a measured outcome with the earlier probe's category. */
public final class AnchorExpectation {
  private AnchorExpectation() {}

  public static void validate(JsonNode expect) throws IOException {
    Category category;
    try {
      category = Category.valueOf(expect.path("category").asText());
    } catch (IllegalArgumentException missingOrUnknown) {
      throw new IOException("expect.category must name an observed outcome");
    }
    if (category == Category.HARNESS || category == Category.UNSUPPORTED)
      throw new IOException("expect.category must name an observed outcome");
    var fields = expect.fields();
    while (fields.hasNext()) {
      var field = fields.next();
      if (field.getKey().endsWith("_contains")
          && (!field.getValue().isTextual() || field.getValue().textValue().isEmpty()))
        throw new IOException("expect." + field.getKey() + " must contain nonempty text");
    }
  }
}
