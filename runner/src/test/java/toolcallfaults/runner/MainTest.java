package toolcallfaults.runner;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MainTest {
  @Test
  void recorded_tool_name_is_not_a_schema_variant() {
    assertEquals(
        "openai-chat/truncation.args-cut-mid-string@recorded",
        Main.fixtureId(
            "openai-chat",
            "truncation.args-cut-mid-string",
            "model-stream",
            "create_event",
            "recorded"));
  }

  @Test
  void zero_argument_tool_is_the_scenario_default() {
    assertEquals(
        "anthropic/tools.zero-arg-call@nonstream",
        Main.fixtureId("anthropic", "tools.zero-arg-call", "loop-call", "flush_notes", null));
  }

  @Test
  void shape_mode_and_schema_tokens_keep_their_order() {
    assertEquals(
        "anthropic/truncation.name-only@block-stop+nonstream+set_level",
        Main.fixtureId(
            "anthropic", "truncation.name-only", "loop-call", "set_level", "block-stop"));
  }
}
