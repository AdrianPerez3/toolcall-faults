package toolcallfaults.runner.judge;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.oracle.Category.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import toolcallfaults.runner.fixture.FixtureLoader;
import toolcallfaults.runner.oracle.Category;

class JudgementsTest {
  Judgements judgements;
  ObjectNode attributes;

  @BeforeEach
  void setup() throws Exception {
    judgements = Judgements.load(Path.of("../suite/judgements.json"));
    attributes = FixtureLoader.JSON.createObjectNode();
  }

  Verdict judge(String fixture, Category category) {
    return judgements.judge(fixture, "loop-stream", category, attributes);
  }

  @Test
  void s03_altered_is_unsupported_with_keys() {
    // Corrected on 2026-11-17: this expected the Anthropic sentence N-AN7 as a key of an
    // OpenAI-wire cell. A provider's sentence is a basis on that provider's wire only; the
    // other provider's sentences on the same fault are listed as related.
    var v = judge("openai-chat/truncation.name-only", EXECUTED_ALTERED);
    assertTrue(v.unsupported());
    assertTrue(v.keys().contains("N-OA1"));
    assertFalse(v.keys().contains("N-AN7"));
    assertTrue(v.related().contains("N-AN7"));
    assertFalse(v.basis().isBlank());
    var anthropic = judge("anthropic/truncation.name-only@no-block-stop", EXECUTED_ALTERED);
    assertTrue(anthropic.keys().containsAll(java.util.List.of("N-AN7", "N-AN11")));
    assertTrue(anthropic.related().containsAll(java.util.List.of("N-AN6", "N-BR4")));
    assertTrue(anthropic.related().contains("N-OA1"));
    assertFalse(anthropic.keys().contains("N-OA1"));
  }

  @Test
  void optional_provider_modes_are_context_for_ordinary_tool_requests() {
    var openAi = judge("openai-chat/truncation.args-cut-mid-string", EXECUTED_ALTERED);
    assertFalse(openAi.keys().contains("N-OA6"));
    assertTrue(openAi.related().contains("N-OA6"));
    var anthropic = judge("anthropic/truncation.name-only@no-block-stop", EXECUTED_ALTERED);
    for (String key : java.util.List.of("N-AN6", "N-BR4")) {
      assertFalse(anthropic.keys().contains(key), key);
      assertTrue(anthropic.related().contains(key), key);
    }
    assertTrue(anthropic.keys().containsAll(java.util.List.of("N-AN7", "N-AN11")));
    var blocking = judgements.judge("anthropic/truncation.name-only@nonstream", "loop-call", EXECUTED_ALTERED, attributes);
    assertTrue(blocking.related().contains("N-AN11"));
    assertFalse(blocking.keys().contains("N-AN11"));
  }

  @Test
  void s03_block_stop_is_record_only() {
    var v =
        judge("anthropic/truncation.name-only@block-stop+nonstream+set_level", EXECUTED_ALTERED);
    assertEquals("record-only", v.level());
    assertFalse(v.unsupported());
  }

  @Test
  void s04_executed_is_unsupported_only_without_limit_signal() {
    assertTrue(
        judge("openai-chat/truncation.complete-args-under-limit", EXECUTED_AS_SENT).unsupported());
    attributes.putArray("caller_finish_reasons").add("LENGTH");
    assertFalse(
        judge("openai-chat/truncation.complete-args-under-limit", EXECUTED_AS_SENT).unsupported());
  }

  @Test
  void s06_openai_chat_is_flag_only() {
    var v = judge("openai-chat/stream.missing-finish", DROPPED);
    assertTrue(v.flagged());
    assertFalse(v.unsupported());
  }

  @Test
  void s07_two_terminal_signals_unsupported_whatever_the_category() {
    attributes.putArray("terminal_signals").add("error").add("complete");
    assertTrue(judge("openai-chat/stream.abort-after-call-start@fin", ERROR_TYPED).unsupported());
  }

  @Test
  void s08_hang_is_unsupported() {
    attributes.put("elapsed_ms", 12000).put("timeout_ms", 3000);
    assertTrue(judge("openai-chat/stream.stall-mid-args", HANG).unsupported());
    attributes.put("elapsed_ms", 1000);
    assertFalse(judge("openai-chat/stream.stall-mid-args", HANG).unsupported());
  }

  @Test
  void control_anything_but_as_sent_is_unsupported() {
    for (Category c : Category.values())
      assertEquals(c != EXECUTED_AS_SENT, judge("openai-chat/tools.single-call", c).unsupported());
  }

  @Test
  void every_register_key_used_is_defined() throws Exception {
    var n = FixtureLoader.JSON.readTree(Path.of("../suite/judgements.json").toFile());
    assertEquals(13, n.path("register").size());
    for (var source : n.path("register")) assertTrue(source.path("wires").size() > 0);
    // Quotes carry the clause that states their scope.
    assertTrue(
        n.path("register").path("N-AN6").path("text").asText()
            .startsWith("\"With fine-grained tool streaming,"));
    assertTrue(
        n.path("register").path("N-BR4").path("text").asText()
            .startsWith("\"Because fine-grained streaming"));
    for (var entry : n.path("entries"))
      for (var key : entry.path("keys"))
        assertTrue(n.path("register").has(key.asText()), key.asText());
  }

  @Test
  void model_surfaces_are_record_only() {
    var v =
        judgements.judge(
            "openai-chat/truncation.name-only", "model-stream", EXECUTED_ALTERED, attributes);
    assertEquals("record-only", v.level());
    assertFalse(v.unsupported());
  }

  @Test
  void s05_hang_after_first_call_is_not_a_clean_turn() {
    attributes.put("invocation_count", 1).put("watchdog_killed", false);
    assertFalse(judge("openai-chat/truncation.second-call-cut", DROPPED).unsupported());
    attributes.put("watchdog_killed", true);
    assertFalse(judge("openai-chat/truncation.second-call-cut", HANG).unsupported());
    attributes.putArray("terminal_signals").add("complete");
    assertFalse(judge("openai-chat/truncation.second-call-cut", HANG).unsupported());
  }

  @Test
  void s05_requires_execution_and_clean_turn() {
    attributes.put("invocation_count", 1).putArray("terminal_signals").add("complete");
    assertTrue(judge("openai-chat/truncation.second-call-cut", EXECUTED_AS_SENT).unsupported());
    attributes.putArray("terminal_signals").add("error");
    assertFalse(judge("openai-chat/truncation.second-call-cut", ERROR_TYPED).unsupported());
  }

  @Test
  void each_verdict_says_whose_criterion_it_applies() {
    assertEquals("provider", judge("openai-chat/truncation.name-only", EXECUTED_ALTERED).criterion());
    assertEquals(
        "suite",
        judge("openai-chat/truncation.complete-args-under-limit", EXECUTED_AS_SENT).criterion());
    attributes.put("elapsed_ms", 12000).put("timeout_ms", 3000);
    assertEquals(
        "suite", judge("openai-chat/stream.stall-mid-args", HANG).criterion());
    assertEquals(
        "provider", judge("openai-chat/stream.abort-after-call-start@fin", EXECUTED_ALTERED).criterion());
    attributes.putArray("terminal_signals").add("error").add("complete");
    assertEquals(
        "suite", judge("openai-chat/stream.abort-after-call-start@fin", ERROR_TYPED).criterion());
    assertEquals(
        "suite", judge("openai-chat/stream.error-after-call-start@data-error", ERROR_UNTYPED).criterion());
  }

  @Test
  void a_clean_silent_drop_is_judged_by_the_suite() {
    for (String fixture : java.util.List.of("truncation.args-cut-mid-string", "truncation.name-only",
        "stream.abort-after-call-start@fin", "stream.error-after-call-start@data-error")) {
      var verdict = judge("openai-chat/" + fixture, DROPPED);
      assertTrue(verdict.unsupported(), fixture);
      assertEquals("suite", verdict.criterion(), fixture);
    }
  }

  @Test
  void a_stalled_body_is_compared_to_the_suites_terminal_deadline() {
    attributes.put("elapsed_ms", 12000).put("timeout_ms", 3000);
    var verdict = judge("openai-chat/stream.stall-mid-args", HANG);
    assertTrue(verdict.unsupported());
    assertEquals("suite", verdict.criterion());
  }

  @Test
  void bases_describe_and_do_not_command() throws Exception {
    var n = FixtureLoader.JSON.readTree(Path.of("../suite/judgements.json").toFile());
    for (var entry : n.path("entries")) {
      assertFalse(
          entry.path("basis").asText().matches(".*\\b(must|require[sd]?)\\b.*"),
          entry.path("scenario").asText() + ": " + entry.path("basis").asText());
      assertTrue(
          java.util.Set.of("provider", "suite", "target-documentation", "control")
              .contains(entry.path("criterion").asText()),
          entry.path("scenario").asText());
    }
  }
}
