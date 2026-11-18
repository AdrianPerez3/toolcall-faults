package toolcallfaults.runner.fixture;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.fixture.FixtureTestSupport.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;

class FixturesUpToDateTest {
  @Test
  void twin_fixtures_differ_only_in_finish_reason() throws Exception {
    var control =
        FixtureLoader.load(Path.of("../suite/fixtures/openai-chat/tools.zero-arg-call.json"));
    var damaged =
        FixtureLoader.load(
            Path.of("../suite/fixtures/openai-chat/truncation.name-only@flush_notes.json"));
    String controlBody =
        new String(FixtureLoader.bodyBytes(control, 0), java.nio.charset.StandardCharsets.UTF_8);
    String damagedBody =
        new String(FixtureLoader.bodyBytes(damaged, 0), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(
        controlBody.replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"length\""),
        damagedBody);
    assertTrue(control.truth().calls().get(0).complete());
    assertFalse(damaged.truth().calls().get(0).complete());
  }

  @Test
  void catalogue_is_complete() throws Exception {
    var expected =
        new java.util.TreeSet<>(
            Files.readAllLines(Path.of("src/test/resources/catalogue-ids.txt")));
    var actual = new java.util.TreeSet<String>();
    try (var files = Files.walk(Path.of("../suite/fixtures"))) {
      for (Path p : files.filter(p -> p.toString().endsWith(".json")).toList())
        actual.add(JSON.readTree(Files.readString(p)).path("id").asText());
    }
    var missing = new java.util.TreeSet<>(expected);
    missing.removeAll(actual);
    var extra = new java.util.TreeSet<>(actual);
    extra.removeAll(expected);
    assertEquals(expected, actual, "missing: " + missing + "; extra: " + extra);
  }

  @Test
  void interleaved_fixture_alternates() throws Exception {
    var f =
        FixtureLoader.load(
            Path.of("../suite/fixtures/openai-chat/tools.parallel-interleaved.json"));
    var indices = new java.util.ArrayList<Integer>();
    for (var frame : f.exchanges().get(0).path("response").path("body").path("sse")) {
      if (frame.asText().contains("[DONE]")) continue;
      var n = JSON.readTree(frame.asText().substring(6));
      for (var c : n.path("choices").get(0).path("delta").path("tool_calls")) {
        if (!c.path("function").path("arguments").asText().isEmpty()) {
          assertFalse(c.has("id"));
          indices.add(c.path("index").asInt());
        }
      }
    }
    assertEquals(java.util.List.of(0, 1, 0, 1), indices.subList(0, 4));
    assertEquals(40, indices.size());
  }

  @Test
  void unterminated_fixture_ends_with_single_newline() throws Exception {
    var f =
        FixtureLoader.load(
            Path.of("../suite/fixtures/openai-chat/stream.final-event-unterminated.json"));
    var frames = f.exchanges().get(0).path("response").path("body").path("sse");
    String last = frames.get(frames.size() - 1).asText();
    assertTrue(last.endsWith("}\n"));
    assertFalse(last.endsWith("\n\n"));
  }

  @Test
  void recorded_variants_are_byte_identical_to_sources() throws Exception {
    var hashes =
        java.util.Map.of(
            "openai-chat",
            "8b65d4f4255e273590dd3d28c96a2d985cfc474a2357c78bcbd1e79cbea0dfd2",
            "anthropic",
            "e0e79ee10c18063861f4cc191ff6c6bdda767cd7407bec1c050c7831ff41f90d");
    for (var e : hashes.entrySet()) {
      var f =
          FixtureLoader.load(
              Path.of(
                  "../suite/fixtures/"
                      + e.getKey()
                      + "/truncation.args-cut-mid-string@recorded.json"));
      String hash =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(FixtureLoader.bodyBytes(f, 0)));
      assertEquals(e.getValue(), hash);
      assertEquals(e.getValue(), f.bodySha256().get(0));
    }
  }

  @Test
  void builder_matches_spec_example() throws Exception {
    var generated =
        JSON.readTree(FixtureBuilder.all().get("fixtures/openai-chat/truncation.name-only.json"));
    assertEquals(example().get("exchanges"), generated.get("exchanges"));
    Path exampleFile = Path.of("src/test/resources/spec-example-s03.json");
    var fixture = FixtureLoader.load(exampleFile);
    String text = Files.readString(exampleFile);
    for (int i = 0; i < fixture.exchanges().size(); i++) {
      String hash =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(FixtureLoader.bodyBytes(fixture, i)));
      if (Boolean.getBoolean("tcf.fixtures.write"))
        text = text.replace("<hex of exchange " + i + " body>", hash);
      else assertEquals(hash, fixture.bodySha256().get(i));
    }
    if (Boolean.getBoolean("tcf.fixtures.write")) Files.writeString(exampleFile, text);
  }

  @Test
  void committed_fixtures_equal_builder_output() throws Exception {
    for (var entry : FixtureBuilder.all().entrySet()) {
      Path p = Path.of("../suite").resolve(entry.getKey());
      if (Boolean.getBoolean("tcf.fixtures.write")) {
        Files.createDirectories(p.getParent());
        Files.writeString(p, entry.getValue());
      }
      assertTrue(Files.exists(p), p.toString());
      assertEquals(entry.getValue(), Files.readString(p), p.toString());
    }
  }

  @Test
  void second_call_identity_chunk_carries_only_the_tool_call() throws Exception {
    // As in the cited recording of two calls: role and content come once, in the first chunk.
    for (String name :
        java.util.List.of(
            "tools.parallel-sequential", "tools.parallel-interleaved", "truncation.second-call-cut")) {
      var fixture = JSON.readTree(Path.of("../suite/fixtures/openai-chat/" + name + ".json").toFile());
      int seen = 0;
      for (var frame : fixture.path("exchanges").get(0).path("response").path("body").path("sse")) {
        if (!frame.asText().contains("\"id\":\"call_TCF2\"")) continue;
        seen++;
        var delta =
            JSON.readTree(frame.asText().substring(6)).path("choices").get(0).path("delta");
        var keys = new java.util.ArrayList<String>();
        delta.fieldNames().forEachRemaining(keys::add);
        assertEquals(java.util.List.of("tool_calls"), keys, name);
      }
      assertEquals(1, seen, name);
    }
  }

  @Test
  void terminated_twin_differs_from_the_recording_only_by_the_final_blank_line() throws Exception {
    var recorded =
        FixtureLoader.load(
            Path.of("../suite/fixtures/anthropic/truncation.args-cut-mid-string@recorded.json"));
    var twin =
        FixtureLoader.load(
            Path.of(
                "../suite/fixtures/anthropic/truncation.args-cut-mid-string@recorded-terminated.json"));
    assertEquals("derived", twin.tier());
    assertEquals(
        new String(FixtureLoader.bodyBytes(recorded, 0), java.nio.charset.StandardCharsets.UTF_8)
            + "\n\n",
        new String(FixtureLoader.bodyBytes(twin, 0), java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(recorded.truth().calls(), twin.truth().calls());
    assertEquals(java.util.List.of("content_block_stop"), twin.removes());
    assertTrue(recorded.truth().damage().contains("message_stop"), recorded.truth().damage());
    assertTrue(recorded.fromRecording() && twin.fromRecording());
  }
}
