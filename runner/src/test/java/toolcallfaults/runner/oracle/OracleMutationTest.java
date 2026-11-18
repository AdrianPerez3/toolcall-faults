package toolcallfaults.runner.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.*;
import toolcallfaults.server.RequestRecord;

class OracleMutationTest {
  OracleTest b;

  @BeforeEach
  void setup() throws Exception {
    b = new OracleTest();
    b.setup();
  }

  @Test
  void fed_back_with_request_count_1_is_dropped() {
    b.fed_back_hidden();
    b.requests.remove(1);
    assertEquals(Category.DROPPED, b.classify().category());
  }

  @Test
  void fed_back_with_length_in_caller_view_is_surfaced() {
    b.fed_back_hidden();
    b.line.withObject("caller").putArray("finish_reasons").add("length");
    assertEquals(Category.SURFACED, b.classify().category());
  }

  @Test
  void executed_as_sent_with_one_null_argument_is_altered() throws Exception {
    b.executed_as_sent();
    ((ObjectNode) b.invocations.get(0)).set("args", b.invocations.get(0).get("args").deepCopy());
    ((ObjectNode) b.invocations.get(0).get("args")).putNull("title");
    assertEquals(Category.EXECUTED_ALTERED, b.classify().category());
  }

  @Test
  void executed_twice_is_altered() throws Exception {
    b.executed_as_sent();
    b.invocations.add(b.invocations.get(0).deepCopy());
    assertEquals(Category.EXECUTED_ALTERED, b.classify().category());
  }

  @Test
  void hang_without_scripted_response_is_harness() {
    b.hang_when_killed_after_scripted_response();
    b.requests.set(
        0,
        new RequestRecord(
            "c", 1, "POST", "/v1/chat/completions", 0, "test", true, Map.of(), "{}", 1, 2, false));
    assertEquals(Category.HARNESS, b.classify().category());
  }

  @Test
  void typed_error_with_unlisted_prefix_is_untyped() {
    b.error_typed_by_manifest_prefix();
    ((ObjectNode) b.line.path("error").path("chain").get(0)).put("type", "other.ClientException");
    assertEquals(Category.ERROR_UNTYPED, b.classify().category());
  }

  @Test
  void surfaced_only_in_callback_is_dropped() {
    b.surfaced_on_limit_reason();
    b.line.withObject("caller").putArray("finish_reasons");
    b.line
        .withObject("caller")
        .withObject("callback_only")
        .putArray("intermediate_finish_reasons")
        .add("LENGTH");
    assertEquals(Category.DROPPED, b.classify().category());
    assertTrue(b.classify().attributes().path("signal_only_in_callback").asBoolean());
  }

  @Test
  void dropped_with_unparseable_caller_tool_call_is_surfaced() {
    b.dropped_when_nothing_distinguishes();
    b.line
        .withObject("caller")
        .putArray("tool_calls")
        .addObject()
        .put("arguments_parsed_ok", false);
    assertEquals(Category.SURFACED, b.classify().category());
  }

  @Test
  void error_after_altered_execution_stays_altered() throws Exception {
    b.executed_altered_when_nulls();
    b.error("java.lang.IllegalStateException");
    assertEquals(Category.EXECUTED_ALTERED, b.classify().category());
  }

  @Test
  void model_surface_ignores_recorder_free_loop_rules() throws Exception {
    b.model_surface_complete_call_is_as_sent();
    assertTrue(b.invocations.isEmpty());
    assertEquals(1, b.requests.size());
    b.surface = "loop-stream";
    assertEquals(Category.DROPPED, b.classify().category());
  }
}
