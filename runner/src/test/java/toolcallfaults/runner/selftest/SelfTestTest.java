package toolcallfaults.runner.selftest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import toolcallfaults.runner.fixture.FixtureLoader;

class SelfTestTest {
  @Test
  void probe_expectations_keep_their_origin_and_run_on_each_reference_jdk() throws Exception {
    var entry =
        FixtureLoader.JSON
            .readTree(Path.of("../expectations/anchors.json").toFile())
            .path("entries")
            .get(0);
    var manifest =
        toolcallfaults.runner.cell.AdapterManifest.load(
            Path.of("../adapters", entry.path("target").asText()));
    for (int jdk : java.util.List.of(17, 21, 25)) {
      var cell = SelfTest.anchorCell(entry, manifest, jdk);
      assertEquals(jdk, cell.jdk());
      assertEquals(entry.path("fixture").asText(), cell.fixtureId());
      assertEquals(entry.path("version").asText(), cell.version());
      assertEquals(21, entry.path("jdk").asInt());
    }
    assertThrows(java.io.IOException.class, () -> SelfTest.anchorCell(entry, manifest, 23));
    ((com.fasterxml.jackson.databind.node.ObjectNode) entry).put("jdk", 17);
    assertThrows(java.io.IOException.class, () -> SelfTest.anchorCell(entry, manifest, 17));
  }

  @Test
  void primitive_control_keeps_arguments_and_removes_only_limit_reason() throws Exception {
    for (String wire : java.util.List.of("openai-chat", "anthropic")) {
      var source =
          FixtureLoader.load(
              Path.of(
                  "../suite/fixtures/"
                      + wire
                      + "/truncation.complete-args-under-limit@set_level.json"));
      var control = SelfTest.levelControl(source);
      String limit = wire.equals("openai-chat") ? "length" : "max_tokens",
          normal = wire.equals("openai-chat") ? "tool_calls" : "tool_use";
      assertEquals(source.truth().calls(), control.truth().calls());
      assertEquals(normal, control.truth().rawFinish());
      String before = new String(FixtureLoader.bodyBytes(source, 0), StandardCharsets.UTF_8);
      String after = new String(FixtureLoader.bodyBytes(control, 0), StandardCharsets.UTF_8);
      assertEquals(before.replace("\"" + limit + "\"", "\"" + normal + "\""), after);
      assertEquals("set_level", control.tools().get(0));
      assertTrue(control.truth().calls().get(0).complete());
    }
  }
}
