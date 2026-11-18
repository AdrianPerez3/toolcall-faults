package toolcallfaults.runner.lint;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.fixture.FixtureTestSupport.*;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FixtureLintTest {
  @TempDir Path dir;

  @Test
  void judgement_document_is_checked_by_manifest_lint() throws Exception {
    Path suite = dir.resolve("suite");
    Files.createDirectories(suite);
    var node = JSON.readTree(Path.of("../suite/judgements.json").toFile());
    Files.writeString(suite.resolve("judgements.json"), node.toString());
    assertTrue(FixtureLint.check(suite).isEmpty(), FixtureLint.check(suite).toString());
    assertTrue(ManifestLint.check(dir).isEmpty());
    ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("entries").get(0))
        .put("level", "ignore");
    Files.writeString(suite.resolve("judgements.json"), node.toString());
    assertTrue(
        ManifestLint.check(dir).stream().anyMatch(p -> p.jsonPath().equals("$.entries[0].level")));
  }

  @Test
  void unknown_source_applicability_fails_manifest_lint() throws Exception {
    Path suite = Files.createDirectories(dir.resolve("suite"));
    var register = JSON.readTree(Path.of("../suite/judgements.json").toFile());
    ((com.fasterxml.jackson.databind.node.ObjectNode) register.path("register").path("N-AN4"))
        .put("applicability", "unknown");
    Files.writeString(suite.resolve("judgements.json"), register.toString());
    assertTrue(ManifestLint.check(dir).stream()
        .anyMatch(p -> p.jsonPath().equals("$.register.N-AN4.applicability")));
  }

  @Test
  void recorded_fixtures_skip_completeness() throws Exception {
    var n = example();
    n.put("tier", "recorded");
    var a = (com.fasterxml.jackson.databind.node.ArrayNode) response(n).path("body").path("sse");
    var event =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            JSON.readTree(a.get(0).asText().substring(6));
    event.remove("system_fingerprint");
    a.set(0, JSON.getNodeFactory().textNode("data: " + event + "\n\n"));
    hashes(n);
    save(dir, n);
    assertEquals(0, FixtureLint.check(dir).size());
  }

  @Test
  void id_must_match_path() throws Exception {
    var n = example();
    hashes(n);
    Path p = save(dir, n);
    Files.move(p, p.resolveSibling("wrong.json"));
    assertTrue(FixtureLint.check(dir).stream().anyMatch(x -> x.jsonPath().equals("$.id")));
  }

  @Test
  void sha_mismatch_reported() throws Exception {
    var n = example();
    hashes(n);
    ((com.fasterxml.jackson.databind.node.ArrayNode) n.path("provenance").path("body_sha256"))
        .set(0, JSON.getNodeFactory().textNode("0".repeat(64)));
    save(dir, n);
    assertTrue(
        FixtureLint.check(dir).stream()
            .anyMatch(x -> x.jsonPath().equals("$.provenance.body_sha256[0]")));
  }

  @Test
  void empty_chunk_rejected() throws Exception {
    var n = example();
    response(n).set("body", JSON.readTree("{\"chunks_b64\":[\"YQ==\",\"\"]}"));
    save(dir, n);
    assertTrue(
        FixtureLint.check(dir).stream()
            .anyMatch(
                x ->
                    x.jsonPath().equals("$.exchanges[0].response.body.chunks_b64[1]")
                        && x.message().equals("empty chunk ends a chunked body")));
  }

  @Test
  void non_clean_end_needs_streamed_body() throws Exception {
    for (String end : new String[] {"abort", "reset", "stall"}) {
      var n = example();
      response(n).set("body", JSON.readTree("{\"text\":\"a\"}"));
      response(n).put("end", "abort");
      if (!end.equals("abort"))
        n.withObject("x").putObject("0").put("end", end).put("stall_ms", 2000);
      save(dir, n);
      assertTrue(
          FixtureLint.check(dir).stream().anyMatch(x -> x.message().contains("streamed body")));
    }
  }

  @Test
  void sse_element_needs_blank_line_unless_removed() throws Exception {
    var n = example();
    var a = (com.fasterxml.jackson.databind.node.ArrayNode) response(n).path("body").path("sse");
    a.set(2, JSON.getNodeFactory().textNode("data: [DONE]"));
    hashes(n);
    save(dir, n);
    assertTrue(FixtureLint.check(dir).stream().anyMatch(x -> x.message().contains("blank line")));
    n.withArray("removes").add("final-blank-line");
    save(dir, n);
    assertTrue(FixtureLint.check(dir).isEmpty(), FixtureLint.check(dir).toString());
  }

  @Test
  void derived_source_must_exist() throws Exception {
    var n = example();
    hashes(n);
    n.put("tier", "derived");
    n.putObject("derived_from")
        .put("fixture", "openai-chat/absent")
        .put("transform", "cut")
        .putObject("arguments");
    save(dir, n);
    assertTrue(
        FixtureLint.check(dir).stream().anyMatch(x -> x.message().contains("source fixture")));
  }

  @Test
  void scenario_needs_evidence_or_no_sighting_words() throws Exception {
    Path p = dir.resolve("scenarios/tools/single-call.json");
    Files.createDirectories(p.getParent());
    Files.writeString(
        p,
        "{\"format\":\"tcf.scenario/v1\",\"id\":\"tools.single-call\",\"evidence\":[],\"sighting\":\"third-party\"}");
    assertTrue(FixtureLint.check(dir).stream().anyMatch(x -> x.jsonPath().equals("$.evidence")));
    Files.writeString(p, Files.readString(p).replace("third-party", "protocol-legal, no sighting"));
    assertTrue(FixtureLint.check(dir).isEmpty());
  }
}
