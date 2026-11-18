package toolcallfaults.runner.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.fixture.FixtureTestSupport.JSON;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import toolcallfaults.runner.fixture.FixtureLoader;

class OracleBoundaryTest {
  OracleTest b;
  static final String TOOL_RESULT =
      "{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"call_TCF1\",\"content\":\"boom\"}]}";

  @BeforeEach
  void setup() throws Exception {
    b = new OracleTest();
    b.setup();
  }

  void asSent() throws Exception {
    b.f = FixtureLoader.load(Path.of("../suite/fixtures/openai-chat/tools.single-call.json"));
    ObjectNode invocation = JSON.createObjectNode().put("name", "record_note");
    invocation.set("args", b.f.truth().calls().get(0).intendedArguments().deepCopy());
    b.invocations.add(invocation);
  }

  @Test
  void tool_result_in_the_first_request_only_is_not_feedback() {
    b.requests.set(0, b.request(1, TOOL_RESULT));
    b.requests.add(b.request(2, "{}"));
    assertEquals(Category.DROPPED, b.classify().category());
  }

  @Test
  void feedback_with_two_complete_signals_is_not_hidden() {
    b.requests.add(b.request(2, TOOL_RESULT));
    b.line.putArray("terminal_signals").add("complete").add("complete");
    assertNotEquals(Category.FED_BACK_HIDDEN, b.classify().category());
  }

  @Test
  void result_line_invocations_never_replace_the_recorder_file() throws Exception {
    asSent();
    b.line.putArray("tool_invocations");
    assertEquals(Category.EXECUTED_AS_SENT, b.classify().category());
    b.invocations.clear();
    b.line.putArray("tool_invocations").addObject().put("name", "record_note")
        .set("args", b.f.truth().calls().get(0).intendedArguments().deepCopy());
    assertEquals(Category.DROPPED, b.classify().category());
  }

  @Test
  void extra_null_argument_is_altered() throws Exception {
    asSent();
    ((ObjectNode) b.invocations.get(0).get("args")).putNull("extra");
    assertEquals(Category.EXECUTED_ALTERED, b.classify().category());
  }

  @Test
  void integer_delivered_as_decimal_is_altered() throws Exception {
    asSent();
    ((ObjectNode) b.invocations.get(0).get("args")).put("priority", 3.0);
    assertEquals(Category.EXECUTED_ALTERED, b.classify().category());
  }

  @Test
  void key_order_does_not_matter() throws Exception {
    asSent();
    var args = (ObjectNode) b.invocations.get(0).get("args");
    var priority = args.remove("priority");
    args.set("priority", priority);
    assertEquals(Category.EXECUTED_AS_SENT, b.classify().category());
  }

  @Test
  void typed_prefix_must_start_the_class_name() {
    b.error("shaded.com.openai.errors.OpenAIIoException");
    assertEquals(Category.ERROR_UNTYPED, b.classify().category());
  }

  @Test
  void outermost_exception_decides_typed_or_untyped() {
    b.error("java.lang.RuntimeException");
    ((com.fasterxml.jackson.databind.node.ArrayNode) b.line.path("error").path("chain"))
        .addObject().put("type", "com.openai.errors.OpenAIIoException");
    assertEquals(Category.ERROR_UNTYPED, b.classify().category());
    b.error("com.openai.errors.OpenAIIoException");
    ((com.fasterxml.jackson.databind.node.ArrayNode) b.line.path("error").path("chain"))
        .addObject().put("type", "java.io.EOFException");
    assertEquals(Category.ERROR_TYPED, b.classify().category());
  }

  @Test
  void tool_result_for_the_executed_first_call_is_not_feedback_for_the_cut_second_call()
      throws Exception {
    b.f = FixtureLoader.load(Path.of("../suite/fixtures/openai-chat/truncation.second-call-cut.json"));
    ObjectNode invocation = JSON.createObjectNode().put("name", "record_note");
    invocation.set("args", b.f.truth().calls().get(0).intendedArguments().deepCopy());
    b.invocations.add(invocation);
    b.requests.add(b.request(2, TOOL_RESULT));
    b.line.withObject("caller").putArray("finish_reasons").add("stop");
    assertEquals(Category.DROPPED, b.classify().category());
  }

  @Test
  void right_arguments_on_the_wrong_tool_are_altered() throws Exception {
    asSent();
    ((ObjectNode) b.invocations.get(0)).put("name", "set_level");
    assertEquals(Category.EXECUTED_ALTERED, b.classify().category());
  }

  @Test
  void clean_exit_without_a_result_line_is_harness() {
    b.line = null;
    assertEquals(Category.HARNESS, b.classify().category());
    assertEquals("missing or malformed result line", b.classify().reason());
  }

  @Test
  void watchdog_kill_with_result_line_but_unserved_response_is_not_hang() {
    b.killed = true;
    b.exit = 137;
    b.requests.set(0, new toolcallfaults.server.RequestRecord("c", 1, "POST", "/v1/chat/completions", 0, "t", true, java.util.Map.of(), "{}", 1, 2, false));
    assertNotEquals(Category.HANG, b.classify().category());
  }

  @Test
  void attributes_feed_the_judgement_conditions() throws Exception {
    asSent();
    b.line.withObject("caller").putArray("finish_reasons").add("length");
    var a = b.classify().attributes();
    assertEquals(1, a.path("invocation_count").asInt());
    assertEquals(1, a.path("request_count").asInt());
    assertEquals("[\"length\"]", a.path("caller_finish_reasons").toString());
    assertEquals("[\"complete\"]", a.path("terminal_signals").toString());
    assertEquals(100, a.path("elapsed_ms").asLong());
    assertFalse(a.path("watchdog_killed").asBoolean());
    b.killed = true; b.exit = 137;
    assertTrue(b.classify().attributes().path("watchdog_killed").asBoolean());
  }
}
