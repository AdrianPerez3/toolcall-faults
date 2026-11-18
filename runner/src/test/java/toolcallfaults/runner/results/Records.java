package toolcallfaults.runner.results;

import java.util.List;
import toolcallfaults.runner.fixture.FixtureLoader;

public final class Records {
  public static final String CELL =
      "target@1|module|loop-stream|openai-chat/truncation.name-only|record_note|jdk21|jdk";

  public static RunRecord row(String cell, int rep, String category, int requests) {
    var json = FixtureLoader.JSON;
    var adapter = json.createObjectNode();
    adapter
        .putObject("error")
        .putArray("chain")
        .addObject()
        .put("type", "java.lang.IllegalStateException");
    var invocations = json.createArrayNode();
    invocations.addObject().put("name", "record_note").putObject("args").putNull("title");
    return new RunRecord(
        cell,
        rep,
        cell.split("\\|")[2],
        adapter,
        json.createObjectNode().put("request_count", requests),
        category,
        json.createObjectNode(),
        invocations,
        cell.split("\\|")[3],
        List.of("0".repeat(64)),
        "test",
        "2026-11-16",
        null);
  }
}
