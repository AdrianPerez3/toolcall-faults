package toolcallfaults.runner.matrix;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.cell.*;

class ControlFailuresTest {
  @TempDir Path root;
  static final String BASIS = "https://github.com/spring-projects/spring-ai/pull/6775";

  AdapterManifest manifest(boolean declared) throws Exception {
    Files.writeString(
        root.resolve("adapter.json"),
        """
        {"format":"tcf.adapter/v1","target":"example","control_failures":
        """
            + (declared
                ? """
                [{"module":"deepseek","surface":"loop-call","wire":"openai-chat",
                  "tool":"record_note","category":"ERROR_UNTYPED","error_class":"java.lang.IllegalStateException",
                  "basis":"https://github.com/spring-projects/spring-ai/pull/6775","notes":"synthetic fixture"}]
                """
                : "[]")
            + "}");
    return AdapterManifest.load(root);
  }

  Cell cell(String module, String surface, String fixture, String tool) {
    return new Cell("example", "1", module, surface, "openai-chat/" + fixture, tool, 21, "jdk");
  }

  @Test
  void non_control_is_not_run_with_basis_but_controls_and_other_surfaces_remain() throws Exception {
    var s10 =
        cell(
            "deepseek",
            "loop-call",
            "finish.unknown-value-with-tool-call@nonstream",
            "record_note");
    var stream =
        cell("deepseek", "loop-stream", "finish.unknown-value-with-tool-call", "record_note");
    var other =
        cell("openai", "loop-call", "finish.unknown-value-with-tool-call@nonstream", "record_note");
    var control = cell("deepseek", "loop-call", "tools.single-call@nonstream", "record_note");
    var primitive =
        cell("deepseek", "loop-call", "tools.single-call@nonstream+set_level", "set_level");
    var result =
        ControlFailures.select(
            List.of(s10, stream, other, control, primitive), Map.of("example", manifest(true)));
    assertEquals(List.of(stream, other, control, primitive), result.run());
    assertEquals(1, result.notRun().size());
    assertEquals(s10, result.notRun().get(0).cell());
    assertEquals(List.of(BASIS), result.notRun().get(0).basis());
  }

  @Test
  void removing_declaration_restores_cell_without_an_exclusion_edit() throws Exception {
    var s10 =
        cell(
            "deepseek",
            "loop-call",
            "finish.unknown-value-with-tool-call@nonstream",
            "record_note");
    var result = ControlFailures.select(List.of(s10), Map.of("example", manifest(false)));
    assertEquals(List.of(s10), result.run());
    assertTrue(result.notRun().isEmpty());
  }

  @Test
  void rule_covers_other_tools_on_the_affected_module_and_surface() throws Exception {
    var future =
        cell("deepseek", "loop-call", "truncation.name-only@nonstream+set_level", "set_level");
    var result = ControlFailures.select(List.of(future), Map.of("example", manifest(true)));
    assertTrue(result.run().isEmpty());
    assertEquals(future, result.notRun().get(0).cell());
  }

  @Test
  void recovered_version_has_no_failure_declaration_and_restores_non_controls() throws Exception {
    manifest(true);
    var path = root.resolve("adapter.json");
    var n = toolcallfaults.runner.fixture.FixtureLoader.JSON.readTree(path.toFile());
    ((com.fasterxml.jackson.databind.node.ObjectNode) n.path("control_failures").get(0))
        .putArray("versions")
        .add("1");
    Files.writeString(path, n.toString());
    var manifest = AdapterManifest.load(root);
    var old =
        cell(
            "deepseek",
            "loop-call",
            "finish.unknown-value-with-tool-call@nonstream",
            "record_note");
    var recovered =
        new Cell(
            old.target(),
            "2",
            old.module(),
            old.surface(),
            old.fixtureId(),
            old.tool(),
            old.jdk(),
            old.httpClient());
    var result = ControlFailures.select(List.of(old, recovered), Map.of("example", manifest));
    assertEquals(List.of(recovered), result.run());
    assertEquals(List.of(old), result.notRun().stream().map(ControlFailures.NotRun::cell).toList());
    assertTrue(manifest.controlFailure(recovered).isEmpty());
    assertTrue(manifest.controlFailure(old).isPresent());
  }
}
