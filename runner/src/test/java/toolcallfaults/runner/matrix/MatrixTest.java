package toolcallfaults.runner.matrix;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.fixture.FixtureLoader;

class MatrixTest {
  @TempDir Path root;

  @BeforeEach
  void setup() throws Exception {
    for (String dir : List.of("suite/fixtures", "suite/scenarios")) {
      try (var files = Files.walk(Path.of("..", dir))) {
        for (Path source : files.filter(Files::isRegularFile).toList()) {
          Path target = root.resolve(dir).resolve(Path.of("..", dir).relativize(source));
          Files.createDirectories(target.getParent());
          Files.copy(source, target);
        }
      }
    }
    for (String target : List.of("spring-ai", "langchain4j")) {
      Path dir = root.resolve("adapters/" + target);
      Files.createDirectories(dir);
      Files.copy(Path.of("../adapters", target, "adapter.json"), dir.resolve("adapter.json"));
      Files.copy(Path.of("../adapters", target, "pom.xml"), dir.resolve("pom.xml"));
    }
    Files.writeString(
        root.resolve("matrix.json"),
        """
        {"format":"tcf.matrix/v1","timeouts":{"t_ms":3000,"w_ms":12000},"repetitions":3,
        "jdks":[17,21,25],"window_days":14,"repository":null,"targets":{
          "spring-ai":{"versions":{"current":"@pom"},"surfaces":["loop-stream","loop-call","model-stream"]},
          "langchain4j":{"versions":{"current":"@pom"},"surfaces":["loop-stream","loop-call","model-stream"]}}}
        """);
  }

  @Test
  void http_client_axis_limited_to_transport_scenarios() throws Exception {
    var cells = Matrix.load(root).cells("current", 21);
    var okhttp =
        cells.stream()
            .filter(c -> c.target().equals("langchain4j") && c.httpClient().equals("okhttp"))
            .toList();
    assertEquals(35, okhttp.size());
    assertEquals(
        Set.of(
            "tools.single-call",
            "stream.missing-finish",
            "stream.abort-after-call-start",
            "stream.stall-mid-args",
            "stream.error-after-call-start",
            "stream.final-event-unterminated"),
        new HashSet<>(
            okhttp.stream().map(c -> c.fixtureId().split("/", 2)[1].split("@", 2)[0]).toList()));
    var keys = new HashSet<>(cells.stream().map(toolcallfaults.runner.cell.Cell::key).toList());
    for (var cell : okhttp)
      assertTrue(keys.contains(cell.key().replaceAll("\\|okhttp$", "|jdk")), cell.key());
  }

  @Test
  void call_surfaces_take_nonstream_fixtures_only() throws Exception {
    var cells = Matrix.load(root).cells("current", 21);
    assertFalse(cells.isEmpty());
    for (var c : cells)
      assertEquals(c.surface().endsWith("-call"), c.fixtureId().contains("nonstream"), c.key());
  }

  @Test
  void module_scenario_restriction_applies() throws Exception {
    var ids = new HashSet<String>();
    for (var c : Matrix.load(root).cells("current", 21))
      if (c.module().equals("spring-ai-deepseek"))
        ids.add(c.fixtureId().split("/", 2)[1].split("@", 2)[0]);
    assertEquals(
        Set.of(
            "tools.single-call",
            "finish.unknown-value-with-tool-call",
            "tools.parallel-sequential",
            "tools.parallel-interleaved",
            "stream.error-after-call-start"),
        ids);
  }

  @Test
  void recorded_variants_only_on_model_surfaces() throws Exception {
    var cells =
        Matrix.load(root).cells("current", 21).stream()
            .filter(c -> c.fixtureId().contains("recorded"))
            .toList();
    assertFalse(cells.isEmpty());
    assertTrue(cells.stream().allMatch(c -> c.surface().startsWith("model-")));
  }

  @Test
  void schema_axis_limited_to_named_scenarios_and_declared_controls() throws Exception {
    for (var c : Matrix.load(root).cells("current", 21))
      if (c.tool().equals("set_level"))
        assertTrue(
            List.of(
                    "truncation.name-only",
                    "truncation.complete-args-under-limit",
                    "tools.concatenated-args",
                    "tools.single-call")
                .contains(c.fixtureId().split("/", 2)[1].split("@", 2)[0]),
            c.key());
  }

  @Test
  void fixture_tool_outside_scenario_schema_axis_is_not_selected() throws Exception {
    var n =
        (ObjectNode)
            FixtureLoader.JSON.readTree(
                root.resolve(
                        "suite/fixtures/openai-chat/truncation.complete-args-under-limit@set_level.json")
                    .toFile());
    n.put("id", "openai-chat/finish.unknown-value-with-tool-call@set_level")
        .put("scenario", "finish.unknown-value-with-tool-call");
    Files.writeString(
        root.resolve(
            "suite/fixtures/openai-chat/finish.unknown-value-with-tool-call@set_level.json"),
        n.toString());
    assertTrue(
        Matrix.load(root).cells("current", 21).stream()
            .noneMatch(
                c ->
                    c.fixtureId()
                        .equals("openai-chat/finish.unknown-value-with-tool-call@set_level")));
  }

  @Test
  void unsupported_cells_are_removed() throws Exception {
    Path p = root.resolve("adapters/langchain4j/adapter.json");
    var n = (ObjectNode) FixtureLoader.JSON.readTree(p.toFile());
    n.putArray("unsupported")
        .addObject()
        .put("scenario", "truncation.name-only")
        .put("wire", "openai-chat")
        .put("surface", "loop-stream")
        .put("reason", "test")
        .putArray("docs")
        .add("https://example.org/contract");
    Files.writeString(p, n.toString());
    assertTrue(
        Matrix.load(root).cells("current", 21).stream()
            .noneMatch(
                c ->
                    c.target().equals("langchain4j")
                        && c.surface().equals("loop-stream")
                        && c.fixtureId().startsWith("openai-chat/truncation.name-only")));
  }

  @Test
  void current_version_comes_from_pom() throws Exception {
    Path p = root.resolve("adapters/spring-ai/pom.xml");
    Files.writeString(
        p,
        Files.readString(p)
            .replaceFirst(
                "<target.version>[^<]+</target.version>",
                "<target.version>9.9.9</target.version>"));
    assertTrue(
        Matrix.load(root).cells("current", 21).stream()
            .filter(c -> c.target().equals("spring-ai"))
            .allMatch(c -> c.version().equals("9.9.9")));
  }

  @Test
  void cell_key_is_stable() throws Exception {
    // The current pin moves with dependency updates; the key format must not.
    String pin =
        toolcallfaults.runner.cell.AdapterBuild.defaultVersion(root.resolve("adapters/spring-ai"));
    assertTrue(
        Matrix.load(root).cells("current", 21).stream()
            .anyMatch(
                c ->
                    c.key()
                        .equals(
                            "spring-ai@"
                                + pin
                                + "|spring-ai-openai|loop-stream|openai-chat/truncation.name-only|record_note|jdk21|okhttp")));
  }

  @Test
  void declared_controls_remain_and_non_control_is_visible_as_not_run() throws Exception {
    var matrix = Matrix.load(root);
    var cells = matrix.cells("current", 21);
    var controls =
        cells.stream()
            .filter(c -> c.module().equals("spring-ai-deepseek") && c.surface().equals("loop-call"))
            .toList();
    assertEquals(
        Set.of("record_note", "set_level"),
        new HashSet<>(controls.stream().map(c -> c.tool()).toList()));
    assertTrue(
        controls.stream().allMatch(c -> c.fixtureId().startsWith("openai-chat/tools.single-call")));
    assertEquals(1, matrix.notRun("current", 21).size());
    var skipped = matrix.notRun("current", 21).get(0);
    assertEquals(
        "openai-chat/finish.unknown-value-with-tool-call@nonstream", skipped.cell().fixtureId());
    assertEquals(
        List.of("https://github.com/spring-projects/spring-ai/pull/6775"), skipped.basis());
    Path p = root.resolve("adapters/spring-ai/adapter.json");
    var n = (ObjectNode) FixtureLoader.JSON.readTree(p.toFile());
    n.remove("control_failures");
    Files.writeString(p, n.toString());
    assertTrue(Matrix.load(root).cells("current", 21).contains(skipped.cell()));
  }

  @Test
  void fixtures_made_from_a_recording_run_on_model_surfaces_only() throws Exception {
    var cells =
        Matrix.load(root).cells("current", 21).stream()
            .filter(c -> c.fixtureId().contains("@recorded"))
            .toList();
    assertTrue(
        cells.stream()
            .anyMatch(
                c ->
                    c.fixtureId()
                        .equals("anthropic/truncation.args-cut-mid-string@recorded-terminated")));
    assertTrue(cells.stream().allMatch(c -> c.surface().startsWith("model-")), cells.toString());
  }
}
