package toolcallfaults.runner.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.fixture.FixtureTestSupport.JSON;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import toolcallfaults.runner.cell.*;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.server.*;

class OracleTest {
  @Test
  void model_zero_argument_call_accepts_the_empty_wire_representation() throws Exception {
    surface = "model-stream";
    f = FixtureLoader.load(Path.of("../suite/fixtures/openai-chat/tools.zero-arg-call.json"));
    line.withObject("caller")
        .putArray("tool_calls")
        .addObject()
        .put("id", "call_TCF1")
        .put("name", "flush_notes")
        .put("arguments_raw", "")
        .put("arguments_parsed_ok", false)
        .putNull("arguments");
    assertEquals(Category.EXECUTED_AS_SENT, classify().category());
  }

  // Corrected on 2026-11-17: this expected EXECUTED_ALTERED. The wire carries exactly this empty
  // string under a length reason, so the client altered nothing; the plan's model-surface rule
  // (an unparsed call counts as an incomplete call for SURFACED) applies to every tool.
  @Test
  void model_verbatim_hand_up_of_an_incomplete_zero_argument_call_is_surfaced() throws Exception {
    surface = "model-stream";
    f =
        FixtureLoader.load(
            Path.of("../suite/fixtures/openai-chat/truncation.name-only@flush_notes.json"));
    line.withObject("caller").putArray("finish_reasons").add("length");
    line.withObject("caller")
        .putArray("tool_calls")
        .addObject()
        .put("id", "call_TCF1")
        .put("name", "flush_notes")
        .put("arguments_raw", "")
        .put("arguments_parsed_ok", false)
        .putNull("arguments");
    assertEquals(Category.SURFACED, classify().category());
  }

  private void handUp(String fixture, String raw, String finish) throws Exception {
    surface = "model-call";
    f = FixtureLoader.load(Path.of("../suite/fixtures/" + fixture + ".json"));
    line.withObject("caller").putArray("finish_reasons").add(finish);
    var call =
        line.withObject("caller")
            .putArray("tool_calls")
            .addObject()
            .put("id", "toolu_01TCF1")
            .put("name", "record_note")
            .put("arguments_raw", raw)
            .put("arguments_parsed_ok", true);
    call.set("arguments", JSON.readTree(raw));
  }

  @Test
  void model_verbatim_hand_up_of_the_wire_input_object_is_surfaced() throws Exception {
    // The non-streaming body itself carries "input": {} under max_tokens.
    handUp("anthropic/truncation.name-only@nonstream", "{ }", "max_tokens");
    assertEquals(Category.SURFACED, classify().category());
  }

  @Test
  void model_verbatim_hand_up_without_a_limit_sign_is_dropped() throws Exception {
    handUp("anthropic/truncation.name-only@nonstream", "{}", "end_turn");
    assertEquals(Category.DROPPED, classify().category());
  }

  @Test
  void model_hand_up_that_differs_from_the_wire_is_altered() throws Exception {
    // The stream carried no argument bytes; an empty object is the client's own default.
    surface = "model-stream";
    handUp("anthropic/truncation.name-only@block-stop", "{}", "max_tokens");
    surface = "model-stream";
    assertEquals(Category.EXECUTED_ALTERED, classify().category());
  }

  @Test
  void later_request_body_that_does_not_parse_makes_feedback_undecidable() {
    requests.add(request(2, "{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"call_TCF1\",\"content\":\"bo"));
    var c = classify();
    assertEquals(Category.HARNESS, c.category());
    assertEquals("later request body is not parseable JSON", c.reason());
  }

  @Test
  void unparseable_later_body_does_not_hide_an_altered_execution() throws Exception {
    nulls();
    requests.add(request(2, "{\"messages\":["));
    assertEquals(Category.EXECUTED_ALTERED, classify().category());
  }

  @Test
  void empty_arguments_for_a_parameterized_model_call_are_not_repaired() {
    surface = "model-stream";
    line.withObject("caller")
        .putArray("tool_calls")
        .addObject()
        .put("id", "call_TCF1")
        .put("name", "record_note")
        .put("arguments_raw", "")
        .put("arguments_parsed_ok", false)
        .putNull("arguments");
    assertEquals(Category.SURFACED, classify().category());
  }

  Fixture f;
  ObjectNode line;
  List<JsonNode> invocations;
  List<RequestRecord> requests;
  boolean killed, torn;
  Integer exit = 0;
  String surface = "loop-stream";

  @BeforeEach
  void setup() throws Exception {
    f = FixtureLoader.load(Path.of("../suite/fixtures/openai-chat/truncation.name-only.json"));
    line =
        (ObjectNode)
            JSON.readTree(
                "{\"format\":\"tcf.result/v1\",\"terminal_signals\":[\"complete\"],\"error\":null,\"caller\":{\"text\":\"\",\"finish_reasons\":[],\"tool_calls\":[],\"callback_only\":{}}}");
    invocations = new ArrayList<>();
    requests = new ArrayList<>(List.of(request(1, "{}")));
  }

  RequestRecord request(int seq, String body) {
    return new RequestRecord(
        "c1", seq, "POST", "/v1/chat/completions", 0, "test", true, Map.of(), body, 1, 2, true);
  }

  CellResult result() {
    return new CellResult(
        new Cell("target", "1", "module", surface, f.id(), "record_note", 21, "jdk"),
        1,
        line,
        invocations,
        torn,
        requests,
        false,
        killed,
        exit,
        100);
  }

  Classified classify() {
    return Oracle.classify(f, result(), List.of("com.openai.errors."));
  }

  void nulls() throws Exception {
    invocations.add(
        JSON.readTree(
            "{\"seq\":1,\"name\":\"record_note\",\"args\":{\"priority\":null,\"title\":null,\"body\":null}}"));
  }

  void error(String type) {
    line.putArray("terminal_signals").add("error");
    line.putObject("error")
        .putArray("chain")
        .addObject()
        .put("type", type)
        .put("message", "failed");
  }

  @Test
  void harness_when_no_request() {
    requests.clear();
    assertEquals(Category.HARNESS, classify().category());
  }

  @Test
  void torn_recorder_line_is_harness() {
    torn = true;
    assertEquals(Category.HARNESS, classify().category());
    assertEquals("recorder file torn", classify().reason());
  }

  @Test
  void nonzero_exit_is_harness() {
    exit = 1;
    assertEquals(Category.HARNESS, classify().category());
    assertEquals("adapter exited 1", classify().reason());
  }

  @Test
  void executed_altered_when_nulls() throws Exception {
    nulls();
    assertEquals(Category.EXECUTED_ALTERED, classify().category());
  }

  @Test
  void executed_altered_outranks_later_hang() throws Exception {
    nulls();
    killed = true;
    exit = 137;
    line = null;
    assertEquals(Category.EXECUTED_ALTERED, classify().category());
  }

  @Test
  void incomplete_call_run_with_matching_args_is_still_altered() throws Exception {
    f =
        new Fixture(
            f.id(),
            f.scenario(),
            f.wire(),
            f.variant(),
            f.tier(),
            f.stream(),
            f.tools(),
            f.prompt(),
            f.exchanges(),
            f.x(),
            new Truth(
                List.of(new TruthCall("call_TCF1", "record_note", "", false, JSON.readTree("{}"))),
                "length",
                true,
                "cut"),
            f.removes(),
            f.bodySha256(),
            f.file());
    invocations.add(JSON.readTree("{\"name\":\"record_note\",\"args\":{}}"));
    assertEquals(Category.EXECUTED_ALTERED, classify().category());
  }

  @Test
  void hang_when_killed_after_scripted_response() {
    killed = true;
    exit = 137;
    line = null;
    assertEquals(Category.HANG, classify().category());
  }

  @Test
  void error_typed_by_manifest_prefix() {
    error("com.openai.errors.OpenAIIoException");
    assertEquals(Category.ERROR_TYPED, classify().category());
  }

  @Test
  void error_untyped_otherwise() {
    error("java.lang.RuntimeException");
    assertEquals(Category.ERROR_UNTYPED, classify().category());
  }

  @Test
  void fed_back_hidden() {
    requests.add(
        request(
            2,
            "{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"call_TCF1\",\"content\":\"boom\"}]}"));
    line.withObject("caller").putArray("finish_reasons").add("STOP");
    assertEquals(Category.FED_BACK_HIDDEN, classify().category());
    assertEquals("boom", classify().attributes().get("fed_back_text").asText());
  }

  @Test
  void surfaced_on_limit_reason() {
    line.withObject("caller").putArray("finish_reasons").add("max_tokens");
    assertEquals(Category.SURFACED, classify().category());
  }

  @Test
  void dropped_when_nothing_distinguishes() {
    assertEquals(Category.DROPPED, classify().category());
  }

  @Test
  void executed_as_sent() throws Exception {
    f = FixtureLoader.load(Path.of("../suite/fixtures/openai-chat/tools.single-call.json"));
    ObjectNode invocation = JSON.createObjectNode().put("name", "record_note");
    invocation.set("args", f.truth().calls().get(0).intendedArguments());
    invocations.add(invocation);
    assertEquals(Category.EXECUTED_AS_SENT, classify().category());
  }

  @Test
  void timeout_fired_from_chain() {
    error("java.lang.RuntimeException");
    ((ArrayNode) line.path("error").path("chain"))
        .addObject()
        .put("type", "java.io.IOException")
        .put("message", "timeout");
    assertTrue(classify().attributes().get("timeout_fired").asBoolean());
  }

  @Test
  void model_surface_complete_call_is_as_sent() throws Exception {
    surface = "model-stream";
    f = FixtureLoader.load(Path.of("../suite/fixtures/openai-chat/tools.single-call.json"));
    var call = line.withObject("caller").putArray("tool_calls").addObject();
    call.put("id", "call_TCF1")
        .put("name", "record_note")
        .put("arguments_parsed_ok", true)
        .set("arguments", f.truth().calls().get(0).intendedArguments());
    assertEquals(Category.EXECUTED_AS_SENT, classify().category());
  }

  @Test
  void model_surface_parsed_call_for_incomplete_call_is_altered() throws Exception {
    surface = "model-stream";
    var call = line.withObject("caller").putArray("tool_calls").addObject();
    call.put("id", "call_TCF1")
        .put("name", "record_note")
        .put("arguments_raw", "{}")
        .put("arguments_parsed_ok", true)
        .set("arguments", JSON.readTree("{}"));
    assertEquals(Category.EXECUTED_ALTERED, classify().category());
  }

  @Test
  void model_surface_raw_partial_call_is_surfaced() {
    surface = "model-stream";
    line.withObject("caller")
        .putArray("tool_calls")
        .addObject()
        .put("id", "call_TCF1")
        .put("name", "record_note")
        .put("arguments_raw", "{\"priority\":3,\"ti")
        .put("arguments_parsed_ok", false);
    line.withObject("caller").putArray("finish_reasons").add("length");
    assertEquals(Category.SURFACED, classify().category());
  }
}
