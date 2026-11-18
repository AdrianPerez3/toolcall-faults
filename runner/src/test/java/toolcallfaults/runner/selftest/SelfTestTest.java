package toolcallfaults.runner.selftest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import toolcallfaults.runner.fixture.FixtureLoader;

class SelfTestTest {
  @org.junit.jupiter.api.io.TempDir Path root;

  @Test
  void server_rejects_an_empty_fixture_set() throws Exception {
    java.nio.file.Files.createDirectories(root.resolve("fixtures"));
    java.nio.file.Files.createDirectories(root.resolve("selftest"));
    assertThrows(java.io.IOException.class, () -> SelfTest.server(root));
  }

  @Test
  void missing_expectations_are_rejected_before_building_any_adapter() throws Exception {
    var file = root.resolve("expectations/anchors.json");
    java.nio.file.Files.createDirectories(file.getParent());
    java.nio.file.Files.writeString(file, """
        {"format":"tcf.anchors/v1","entries":[
          {"target":"example","version":"1","fixture":"openai-chat/test","expect":{}}]}
        """);
    var error = assertThrows(java.io.IOException.class, () -> SelfTest.anchors(root, null, null));
    assertTrue(error.getMessage().contains("expect.category"), error.getMessage());
  }

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
