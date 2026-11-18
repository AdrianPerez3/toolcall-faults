package toolcallfaults.runner.fixture;

import static toolcallfaults.runner.fixture.FixtureLoader.JSON;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Deterministic test-only construction; writing is opt-in in FixturesUpToDateTest. */
public final class FixtureBuilder {
  private FixtureBuilder() {}

  public static final String FULL =
      "{\"priority\":3,\"title\":\"Quarterly report\",\"body\":\"Send the Q3 numbers to finance"
          + " before Friday\"}";
  private static final String[] PIECES = {
    "{\"",
    "priority",
    "\":",
    "3",
    ",\"",
    "title",
    "\":\"",
    "Quarterly",
    " report",
    "\",\"",
    "body",
    "\":\"",
    "Send",
    " the",
    " Q3",
    " num",
    "bers",
    " to",
    " finance",
    " before",
    " Friday",
    "\"}"
  };
  private static final String IDENTITY =
      "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_TCF1\",\"type\":\"function\",\"function\":{\"name\":\"record_note\",\"arguments\":\"\"}}],\"refusal\":null}";
  private static final String SHAPE =
      "https://github.com/openai/openai-python/blob/main/.inline-snapshot/external/2018feb66ae13fcf5333d61b95849decc68d3f63bd38172889367e1afb1e04f7.bin";

  public static String oc(String deltaJson, String finishOrNull) {
    try {
      ObjectNode n = JSON.createObjectNode();
      n.put("id", "chatcmpl-TCF0001")
          .put("object", "chat.completion.chunk")
          .put("created", 1794800000)
          .put("model", "gpt-4o-2024-08-06")
          .put("service_tier", "default")
          .put("system_fingerprint", "fp_tcf0000");
      ObjectNode c = n.putArray("choices").addObject();
      c.put("index", 0);
      c.set("delta", JSON.readTree(deltaJson));
      c.putNull("logprobs");
      c.put("finish_reason", finishOrNull);
      return "data: " + JSON.writeValueAsString(n) + "\n\n";
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static String argument(String fragment) throws Exception {
    ObjectNode delta = JSON.createObjectNode();
    delta
        .putArray("tool_calls")
        .addObject()
        .put("index", 0)
        .putObject("function")
        .put("arguments", fragment);
    return oc(JSON.writeValueAsString(delta), null);
  }

  public static String an(String eventJson) {
    try {
      return "event: "
          + JSON.readTree(eventJson).path("type").asText()
          + "\ndata: "
          + eventJson
          + "\n\n";
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static String anStart(String id) {
    return an(
        "{\"type\":\"message_start\",\"message\":{\"id\":\""
            + id
            + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-5-20250929\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":420,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,\"output_tokens\":1,\"service_tier\":\"standard\"}}}");
  }

  private static String anArgument(String fragment) throws Exception {
    ObjectNode n = JSON.createObjectNode();
    n.put("type", "content_block_delta").put("index", 0);
    n.putObject("delta").put("type", "input_json_delta").put("partial_json", fragment);
    return an(n.toString());
  }

  private static String anFinish(String reason) {
    return an(
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""
            + reason
            + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":40}}");
  }

  private static ObjectNode anthropic(
      String scenario,
      String variant,
      int fragments,
      String finish,
      boolean blockStop,
      long stall,
      List<String> evidence)
      throws Exception {
    ObjectNode n =
        fixture(scenario, fragments, finish, stall > 0 ? "abort" : "clean", stall, evidence);
    n.put("id", "anthropic/" + scenario + (variant.isEmpty() ? "" : "@" + variant))
        .put("wire", "anthropic");
    ObjectNode p = (ObjectNode) n.get("provenance");
    p.put(
        "shape_source",
        "https://github.com/anthropics/anthropic-sdk-python/blob/d4976414f2cc4652a7ec19947627f993a2959645/tests/lib/streaming/fixtures/incomplete_partial_json_response.txt");
    p.put("built_by", "FixtureBuilder.anthropic");
    if (variant.equals("block-stop"))
      p.put("notes", "Synthetic replay; shape not backed by a capture.");
    List<String> frames = new ArrayList<>();
    frames.add(anStart("msg_01TCF0001"));
    frames.add(
        an(
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_01TCF1\",\"name\":\"record_note\",\"input\":{}}}"));
    frames.add(an("{\"type\":\"ping\"}"));
    frames.add(anArgument(""));
    for (int i = 0; i < fragments; i++) frames.add(anArgument(PIECES[i]));
    if (blockStop) frames.add(an("{\"type\":\"content_block_stop\",\"index\":0}"));
    if (finish != null) {
      frames.add(anFinish(finish));
      frames.add(an("{\"type\":\"message_stop\"}"));
    }
    List<String> follow =
        List.of(
            anStart("msg_01TCF0002"),
            an(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
            an(
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Noted.\"}}"),
            an("{\"type\":\"content_block_stop\",\"index\":0}"),
            anFinish("end_turn"),
            an("{\"type\":\"message_stop\"}"));
    var exchanges = n.putArray("exchanges");
    exchanges.add(exchange(frames, stall > 0 ? "abort" : "clean"));
    exchanges.add(exchange(follow, "clean"));
    for (JsonNode e : exchanges) ((ObjectNode) e.get("match")).put("path", "/v1/messages");
    ((ObjectNode) n.path("truth").path("calls").get(0)).put("id", "toolu_01TCF1");
    ArrayNode removes = n.putArray("removes");
    if (!blockStop) removes.add("content_block_stop");
    if (finish == null) removes.add("message_delta").add("message_stop");
    return n;
  }

  private static ObjectNode exchange(List<String> frames, String end) {
    ObjectNode e = JSON.createObjectNode();
    e.putObject("match").put("method", "POST").put("path", "/v1/chat/completions");
    ObjectNode r = e.putObject("response");
    r.put("status", 200);
    r.putObject("headers").put("content-type", "text/event-stream; charset=utf-8");
    r.putObject("body").set("sse", JSON.valueToTree(frames));
    r.put("end", end);
    return e;
  }

  private static ObjectNode fixture(
      String scenario, int fragments, String finish, String end, long stall, List<String> evidence)
      throws Exception {
    ObjectNode n = JSON.createObjectNode();
    n.put("format", "tcf.fixture/v1")
        .put("id", "openai-chat/" + scenario)
        .put("scenario", scenario)
        .put("wire", "openai-chat")
        .put("tier", "synthetic");
    ObjectNode p = n.putObject("provenance");
    p.put("shape_source", SHAPE).put("source_licence", "Apache-2.0");
    p.set("evidence", JSON.valueToTree(evidence));
    p.put("built_by", "FixtureBuilder.fixture")
        .put("licence", "Apache-2.0")
        .put("notes", "Synthetic replay; ids and fingerprint are placeholders.");
    n.putNull("derived_from");
    ObjectNode call = n.putObject("call");
    call.put("stream", true);
    call.putArray("tools").add("record_note");
    call.put("prompt", "Record a note about the quarterly report.");
    List<String> frames = new ArrayList<>();
    frames.add(oc(IDENTITY, null));
    StringBuilder raw = new StringBuilder();
    for (int i = 0; i < fragments; i++) {
      frames.add(argument(PIECES[i]));
      raw.append(PIECES[i]);
    }
    if (finish != null) {
      frames.add(oc("{}", finish));
      frames.add("data: [DONE]\n\n");
    }
    ArrayNode exchanges = n.putArray("exchanges");
    exchanges.add(exchange(frames, end));
    List<String> follow =
        List.of(
                oc("{\"role\":\"assistant\",\"content\":\"Noted.\",\"refusal\":null}", null),
                oc("{}", "stop"),
                "data: [DONE]\n\n")
            .stream()
            .map(s -> s.replace("TCF0001", "TCF0002").replace("1794800000", "1794800001"))
            .toList();
    exchanges.add(exchange(follow, "clean"));
    ObjectNode x = n.putObject("x");
    if (stall > 0) x.putObject("0").put("end", "stall").put("stall_ms", stall);
    ObjectNode truth = n.putObject("truth"), tc = truth.putArray("calls").addObject();
    tc.put("id", "call_TCF1")
        .put("name", "record_note")
        .put("arguments_raw", raw.toString())
        .put("complete", fragments == 22);
    tc.set("intended_arguments", JSON.readTree(FULL));
    truth
        .put("raw_finish", finish)
        .put("stream_completed", stall == 0)
        .put(
            "damage",
            scenario.equals("tools.single-call") ? "None; normal complete call." : scenario);
    ArrayNode removes = n.putArray("removes");
    if (finish == null) removes.add("finish-chunk").add("done-marker");
    return n;
  }

  private static String encode(ObjectNode n) throws Exception {
    ArrayNode hashes = JSON.createArrayNode();
    for (JsonNode e : n.path("exchanges")) {
      var bytes = new java.io.ByteArrayOutputStream();
      for (byte[] part : FixtureLoader.parts(e.path("response").path("body")))
        bytes.writeBytes(part);
      hashes.add(
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
    }
    ((ObjectNode) n.get("provenance")).set("body_sha256", hashes);
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n) + "\n";
  }

  private static final String FULL2 =
      "{\"priority\":1,\"title\":\"Budget review\",\"body\":\"Book the room for Monday\"}";
  private static final String[] PIECES2 = {
    "{\"",
    "priority",
    "\":",
    "1",
    ",\"",
    "title",
    "\":\"",
    "Budget",
    " review",
    "\",\"",
    "body",
    "\":\"",
    "Book",
    " the",
    " room",
    " for",
    " Monday",
    "\"}"
  };
  private static final String BEDROCK =
      "https://github.com/Arize-ai/openinference/blob/7dbbd93/js/packages/openinference-instrumentation-bedrock/test/recordings/";
  private static final String PARALLEL =
      "https://github.com/traceloop/openllmetry/blob/e3d651a/packages/opentelemetry-instrumentation-openai/tests/traces/cassettes/test_functions/test_open_ai_function_calls_tools_streaming_parallel.yaml";
  private static final String GROQ =
      "https://github.com/pydantic/pydantic-ai/blob/eedabfc/tests/models/cassettes/test_groq/test_tool_use_failed_error_streaming.yaml";

  private static void put(Map<String, String> out, ObjectNode f) throws Exception {
    out.put("fixtures/" + f.path("id").asText() + ".json", encode(f));
  }

  private static ArrayNode frames(ObjectNode f) {
    return (ArrayNode) f.path("exchanges").get(0).path("response").path("body").path("sse");
  }

  private static ObjectNode synthetic(
      String wire,
      String scenario,
      String variant,
      int count,
      String finish,
      boolean blockStop,
      List<String> evidence)
      throws Exception {
    ObjectNode f =
        wire.equals("anthropic")
            ? anthropic(scenario, variant, count, finish, blockStop, 0, evidence)
            : fixture(scenario, count, finish, "clean", 0, evidence);
    f.put("id", wire + "/" + scenario + (variant.isEmpty() ? "" : "@" + variant));
    return f;
  }

  private static String second(String frame, boolean anthropic) throws Exception {
    String prefix = anthropic ? frame.substring(0, frame.indexOf("data: ") + 6) : "data: ";
    var n = (ObjectNode) JSON.readTree(frame.substring(frame.indexOf("data: ") + 6));
    if (anthropic) {
      if (n.has("index")) n.put("index", 1);
      if (n.path("content_block").has("id"))
        ((ObjectNode) n.get("content_block")).put("id", "toolu_01TCF2");
    } else
    {
      // In recordings of two calls only the first chunk of the message carries role, content
      // and refusal; the second call starts with its tool_calls entry alone.
      ObjectNode delta = (ObjectNode) n.path("choices").get(0).path("delta");
      for (var call : delta.path("tool_calls")) {
        ((ObjectNode) call).put("index", 1);
        if (call.has("id")) {
          ((ObjectNode) call).put("id", "call_TCF2");
          delta.retain("tool_calls");
        }
      }
    }
    return prefix + n + "\n\n";
  }

  private static void twoCalls(ObjectNode f, boolean interleaved, boolean cut) throws Exception {
    boolean ant = f.path("wire").asText().equals("anthropic");
    ArrayNode original = frames(f), result = JSON.createArrayNode();
    if (ant) {
      for (int i = 0; i < 27; i++) result.add(original.get(i)); // first call through its block stop
      result.add(second(original.get(1).asText(), true));
      result.add(second(anArgument(""), true));
      for (int i = 0; i < (cut ? 15 : 18); i++) result.add(second(anArgument(PIECES2[i]), true));
      if (!cut) result.add(second(an("{\"type\":\"content_block_stop\",\"index\":0}"), true));
      result.add(anFinish(cut ? "max_tokens" : "tool_use")).add(an("{\"type\":\"message_stop\"}"));
      if (cut) f.withArray("removes").add("content_block_stop");
    } else {
      result.add(oc(IDENTITY, null));
      if (interleaved) result.add(second(oc(IDENTITY, null), false));
      for (int i = 0; i < 22; i++) {
        result.add(argument(PIECES[i]));
        if (interleaved && i < 18) result.add(second(argument(PIECES2[i]), false));
      }
      if (!interleaved) {
        result.add(second(oc(IDENTITY, null), false));
        for (int i = 0; i < (cut ? 15 : 18); i++) result.add(second(argument(PIECES2[i]), false));
      }
      result.add(oc("{}", cut ? "length" : "tool_calls")).add("data: [DONE]\n\n");
    }
    ((ObjectNode) f.path("exchanges").get(0).path("response").get("body")).set("sse", result);
    var call = f.withObject("truth").withArray("calls").addObject();
    call.put("id", ant ? "toolu_01TCF2" : "call_TCF2")
        .put("name", "record_note")
        .put("arguments_raw", String.join("", Arrays.copyOf(PIECES2, cut ? 15 : 18)))
        .put("complete", !cut)
        .set("intended_arguments", JSON.readTree(FULL2));
  }

  private static ObjectNode nonstream(ObjectNode source) throws Exception {
    ObjectNode f = source.deepCopy();
    String wire = f.path("wire").asText(), scenario = f.path("scenario").asText();
    f.put("id", wire + "/" + scenario + "@nonstream");
    f.withObject("call").put("stream", false);
    if (wire.equals("anthropic") && scenario.equals("truncation.name-only"))
      f.withObject("provenance")
          .put("shape_source", BEDROCK + "should-handle-multiple-tools-in-single-request.json");
    f.putArray("removes");
    f.putObject("x");
    for (int i = 0; i < f.path("exchanges").size(); i++) {
      var r = (ObjectNode) f.path("exchanges").get(i).get("response");
      r.withObject("headers").put("content-type", "application/json");
      r.put("end", "clean");
      var body = r.putObject("body").putObject("json");
      String reason =
          i == 0
              ? f.path("truth").path("raw_finish").asText()
              : wire.equals("anthropic") ? "end_turn" : "stop";
      if (wire.equals("openai-chat")) {
        body.put("id", "chatcmpl-TCF000" + (i + 1))
            .put("object", "chat.completion")
            .put("created", 1794800000 + i)
            .put("model", "gpt-4o-2024-08-06")
            .put("service_tier", "default")
            .put("system_fingerprint", "fp_tcf0000");
        var c = body.putArray("choices").addObject();
        c.put("index", 0);
        var message = c.putObject("message");
        message.put("role", "assistant").putNull("refusal");
        if (i == 0) {
          message.putNull("content");
          var calls = message.putArray("tool_calls");
          for (var t : f.path("truth").path("calls")) {
            var call = calls.addObject();
            call.put("id", t.path("id").asText()).put("type", "function");
            call.putObject("function")
                .put("name", t.path("name").asText())
                .put("arguments", t.path("arguments_raw").asText());
          }
        } else message.put("content", "Noted.");
        c.putNull("logprobs").put("finish_reason", reason);
        body.putObject("usage")
            .put("prompt_tokens", 420)
            .put("completion_tokens", 40)
            .put("total_tokens", 460);
      } else {
        body.put("id", "msg_01TCF000" + (i + 1))
            .put("type", "message")
            .put("role", "assistant")
            .put("model", "claude-sonnet-4-5-20250929");
        var content = body.putArray("content");
        if (i == 0)
          for (var t : f.path("truth").path("calls")) {
            String raw = t.path("arguments_raw").asText();
            JsonNode input = raw.isEmpty() ? JSON.createObjectNode() : JSON.readTree(raw);
            var call = content.addObject();
            call.put("type", "tool_use")
                .put("id", t.path("id").asText())
                .put("name", t.path("name").asText())
                .set("input", input);
            ((ObjectNode) t).put("arguments_raw", input.toString());
          }
        else content.addObject().put("type", "text").put("text", "Noted.");
        body.put("stop_reason", reason).putNull("stop_sequence");
        body.putObject("usage").put("input_tokens", 420).put("output_tokens", 40);
      }
    }
    return f;
  }

  private static void catalogue(Map<String, String> out) throws Exception {
    String[] ids = {
      "truncation.complete-args-under-limit",
      "truncation.second-call-cut",
      "stream.abort-after-call-start",
      "finish.stop-with-tool-call",
      "finish.unknown-value-with-tool-call",
      "tools.parallel-sequential",
      "tools.parallel-interleaved",
      "tools.concatenated-args",
      "stream.error-after-call-start",
      "stream.final-event-unterminated"
    };
    String[] spec = {"S04", "S05", "S07", "S09", "S10", "S11", "S12", "S13", "S15", "S16"};
    String[] classes = {
      "F4, T9", "T10", "X1", "F2", "F8", "control", "S1", "S10, C6", "X3, P16", "SSE class 10"
    };
    String[] descriptions = {
      "Complete arguments under a limit reason",
      "First call complete, second cut inside a string",
      "Call started with no argument bytes, then connection closed",
      "Complete call under a natural stop reason",
      "Complete call under an unknown finish reason",
      "Two complete calls with sequential argument fragments",
      "Two complete calls with alternating argument fragments",
      "Empty object followed by the actual argument object",
      "Call started, then an in-band error",
      "Final finish event without its terminating blank line"
    };
    List<List<String>> evidence =
        List.of(
            List.of(BEDROCK + "should-handle-tool-configuration.json"),
            List.of(
                BEDROCK + "should-handle-multiple-tools-in-single-request.json",
                "https://github.com/spring-projects/spring-ai/issues/7360"),
            List.of(
                "https://platform.openai.com/docs/api-reference/chat/streaming",
                "https://platform.claude.com/docs/en/api/errors"),
            List.of("https://github.com/anthropics/claude-code/issues/19143"),
            List.of(
                "https://api-docs.deepseek.com/api/create-chat-completion",
                "https://github.com/spring-projects/spring-ai/issues/7358"),
            List.of(PARALLEL),
            List.of(
                "https://github.com/openai/openai-java/issues/1070",
                "https://github.com/spring-projects/spring-ai/issues/6381"),
            List.of("https://github.com/openai/openai-agents-js/pull/712"),
            List.of(GROQ, "https://platform.claude.com/docs/en/api/streaming"),
            List.of(
                "https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation"));
    for (int i = 0; i < ids.length; i++) {
      ObjectNode scenario = JSON.createObjectNode();
      scenario
          .put("format", "tcf.scenario/v1")
          .put("id", ids[i])
          .put("spec_id", spec[i])
          .put("title", descriptions[i])
          .put("summary", descriptions[i])
          .put("wire_class", classes[i]);
      scenario.set("evidence", JSON.valueToTree(evidence.get(i)));
      scenario.put(
          "sighting",
          i == 6 || i == 9
              ? "protocol-legal, no sighting"
              : i == 5 ? "first-party" : "third-party");
      if (i == 8)
        scenario.put("sighting", "In-band errors recorded; no sighting with a tool call open");
      scenario.putArray("tools").add("record_note");
      var surfaces = scenario.putArray("surfaces");
      if (i != 9) surfaces.add("loop-stream");
      surfaces.add("model-stream");
      scenario.put("http_client_axis", i == 2 || i == 8 || i == 9);
      for (String wire :
          (i == 5 || i == 6 || i == 7 || i == 9)
              ? List.of("openai-chat")
              : List.of("openai-chat", "anthropic")) {
        List<String> variants =
            i == 2
                ? List.of("fin", "reset")
                : i == 8 && wire.equals("openai-chat")
                    ? List.of("data-error", "event-error")
                    : List.of("");
        for (String variant : variants) {
          boolean ant = wire.equals("anthropic"), transport = i == 2 || i == 8;
          String finish =
              transport
                  ? null
                  : (i == 0 || i == 1)
                      ? (ant ? "max_tokens" : "length")
                      : i == 3
                          ? (ant ? "end_turn" : "stop")
                          : i == 4
                              ? (ant ? "tcf_unknown_reason" : "insufficient_system_resource")
                              : (ant ? "tool_use" : "tool_calls");
          ObjectNode f =
              synthetic(
                  wire,
                  ids[i],
                  variant,
                  transport ? (i == 8 ? 4 : 0) : 22,
                  finish,
                  !transport,
                  evidence.get(i));
          if (i == 1 || i == 5 || i == 6) twoCalls(f, i == 6, i == 1);
          if (i == 2) {
            ((ObjectNode) f.path("exchanges").get(0).get("response")).put("end", "abort");
            f.withObject("truth").put("stream_completed", false);
            if (variant.equals("reset")) f.withObject("x").putObject("0").put("end", "reset");
          }
          if (i == 7) {
            frames(f).insert(1, argument("{}"));
            ((ObjectNode) f.path("truth").path("calls").get(0)).put("arguments_raw", "{}" + FULL);
          }
          if (i == 8) {
            frames(f)
                .add(
                    ant
                        ? an(
                            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")
                        : variant.equals("data-error")
                            ? "data: {\"error\":{\"message\":\"The server had an error while"
                                  + " processing your"
                                  + " request.\",\"type\":\"server_error\",\"param\":null,\"code\":null}}\n\n"
                            : "event: error\n"
                                  + "data: {\"error\":{\"message\":\"The server had an error while"
                                  + " processing your"
                                  + " request.\",\"type\":\"server_error\",\"code\":\"server_error\",\"status_code\":500}}\n\n");
            f.withObject("provenance")
                .put(
                    "notes",
                    "Synthetic in-band error after a tool start; no capture of this combined"
                        + " shape.");
          }
          if (i == 9) {
            var a = frames(f);
            a.remove(a.size() - 1);
            String last = a.get(a.size() - 1).asText();
            a.set(
                a.size() - 1, JSON.getNodeFactory().textNode(last.substring(0, last.length() - 1)));
            f.withArray("removes").add("final-blank-line").add("done-marker");
          }
          if (i == 5 || i == 6) f.withObject("provenance").put("shape_source", PARALLEL);
          if (i == 8 && !ant)
            f.withObject("provenance").put("shape_source", GROQ).put("source_licence", "MIT");
          f.withObject("truth").put("damage", descriptions[i]);
          put(out, f);
        }
      }
      out.put("scenarios/" + ids[i].replaceFirst("\\.", "/") + ".json", scenario.toString());
    }
    for (String wire : List.of("openai-chat", "anthropic"))
      for (String id :
          List.of(
              "tools.single-call",
              "truncation.args-cut-mid-string",
              "truncation.name-only",
              "truncation.complete-args-under-limit",
              "finish.stop-with-tool-call",
              "finish.unknown-value-with-tool-call",
              "tools.concatenated-args")) {
        if (wire.equals("anthropic")
            && (id.equals("truncation.args-cut-mid-string")
                || id.equals("tools.concatenated-args"))) continue;
        String source =
            wire
                + "/"
                + id
                + (wire.equals("anthropic") && id.equals("truncation.name-only")
                    ? "@no-block-stop"
                    : "");
        put(out, nonstream((ObjectNode) JSON.readTree(out.get("fixtures/" + source + ".json"))));
      }
    schemaAxis(out);
    // Scenario declarations describe exactly the generated modes and shapes.
    for (String key : new ArrayList<>(out.keySet()))
      if (key.startsWith("scenarios/")) {
        var s = (ObjectNode) JSON.readTree(out.get(key));
        String id = s.path("id").asText();
        ObjectNode wires = s.putObject("wires");
        for (var entry : out.entrySet())
          if (entry.getKey().startsWith("fixtures/")) {
            var f = JSON.readTree(entry.getValue());
            if (!f.path("scenario").asText().equals(id)) continue;
            for (JsonNode tool : f.path("call").path("tools"))
              if (!contains(s.withArray("tools"), tool.asText()))
                s.withArray("tools").add(tool.asText());
            ObjectNode w = wires.withObject(f.path("wire").asText());
            ArrayNode modes = w.withArray("modes"), variants = w.withArray("variants");
            String mode = f.path("call").path("stream").asBoolean() ? "stream" : "nonstream",
                fid = f.path("id").asText();
            String variant = fid.contains("@") ? fid.substring(fid.indexOf('@') + 1) : "";
            if (!contains(modes, mode)) modes.add(mode);
            if (!contains(variants, variant)) variants.add(variant);
            if (mode.equals("nonstream"))
              for (String surface : List.of("loop-call", "model-call"))
                if (!contains(s.withArray("surfaces"), surface))
                  s.withArray("surfaces").add(surface);
          }
        if (id.equals("truncation.args-cut-mid-string"))
          for (String wire : List.of("openai-chat", "anthropic"))
            wires.withObject(wire).withArray("variants").add("recorded");
        if (id.equals("tools.single-call")) s.put("http_client_axis", true);
        out.put(key, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(s) + "\n");
      }
  }

  private static boolean contains(ArrayNode a, String value) {
    for (JsonNode n : a) if (n.asText().equals(value)) return true;
    return false;
  }

  private static final String LEVEL = "{\"level\":2,\"urgent\":true}";
  private static final String[] LEVEL_PIECES = {
    "{\"", "level", "\":", "2", ",\"", "urgent", "\":", "true", "}"
  };

  private static ObjectNode schemaTool(ObjectNode source, String tool) throws Exception {
    ObjectNode f = (ObjectNode) JSON.readTree(source.toString().replace("record_note", tool));
    String id = f.path("id").asText();
    f.put("id", id + (id.contains("@") ? "+" : "@") + tool);
    boolean ant = f.path("wire").asText().equals("anthropic"),
        stream = f.path("call").path("stream").asBoolean();
    String scenario = f.path("scenario").asText();
    boolean empty = scenario.equals("truncation.name-only"),
        concat = scenario.equals("tools.concatenated-args");
    String raw = empty ? (ant && !stream ? "{}" : "") : (concat ? "{}" : "") + LEVEL;
    ObjectNode tc = (ObjectNode) f.path("truth").path("calls").get(0);
    tc.put("arguments_raw", raw)
        .set("intended_arguments", JSON.readTree(tool.equals("set_level") ? LEVEL : "{}"));
    f.withObject("call")
        .put(
            "prompt",
            tool.equals("set_level")
                ? "Set the level to 2 and urgent to true."
                : "Flush the notes.");
    if (stream && !empty) {
      ArrayNode rebuilt = JSON.createArrayNode();
      boolean inserted = false;
      for (JsonNode frame : frames(f)) {
        String text = frame.asText();
        boolean argument =
            text.contains(ant ? "\"type\":\"input_json_delta\"" : "\"function\":{\"arguments\":");
        if (ant && argument) {
          var event = JSON.readTree(text.substring(text.indexOf("data: ") + 6));
          argument = !event.path("delta").path("partial_json").asText().isEmpty();
        }
        if (!argument) rebuilt.add(frame);
        else if (!inserted) {
          if (concat) rebuilt.add(argument("{}"));
          for (String piece : LEVEL_PIECES) rebuilt.add(ant ? anArgument(piece) : argument(piece));
          inserted = true;
        }
      }
      ((ObjectNode) f.path("exchanges").get(0).path("response").get("body")).set("sse", rebuilt);
    } else if (!stream) {
      var body = f.path("exchanges").get(0).path("response").path("body").path("json");
      if (ant) ((ObjectNode) body.path("content").get(0)).set("input", JSON.readTree(raw));
      else
        ((ObjectNode)
                body.path("choices")
                    .get(0)
                    .path("message")
                    .path("tool_calls")
                    .get(0)
                    .get("function"))
            .put("arguments", raw);
    }
    f.withObject("provenance").put("built_by", "FixtureBuilder.schemaTool");
    return f;
  }

  private static void schemaAxis(Map<String, String> out) throws Exception {
    for (String key : new ArrayList<>(out.keySet()))
      if (key.startsWith("fixtures/")) {
        ObjectNode source = (ObjectNode) JSON.readTree(out.get(key));
        String scenario = source.path("scenario").asText();
        if (List.of(
                "truncation.name-only",
                "truncation.complete-args-under-limit",
                "tools.concatenated-args")
            .contains(scenario)) put(out, schemaTool(source, "set_level"));
        if (scenario.equals("truncation.name-only")) {
          ObjectNode flush = schemaTool(source, "flush_notes");
          put(out, flush);
          if (!key.contains("no-block-stop.json")) {
            ObjectNode control = flush.deepCopy();
            boolean ant = control.path("wire").asText().equals("anthropic"),
                stream = control.path("call").path("stream").asBoolean();
            control
                .put("scenario", "tools.zero-arg-call")
                .put(
                    "id",
                    control.path("wire").asText()
                        + "/tools.zero-arg-call"
                        + (stream ? "" : "@nonstream"));
            String limit = ant ? "max_tokens" : "length", normal = ant ? "tool_use" : "tool_calls";
            if (stream) {
              var fs = frames(control);
              for (int i = 0; i < fs.size(); i++)
                fs.set(
                    i,
                    JSON.getNodeFactory()
                        .textNode(
                            fs.get(i).asText().replace("\"" + limit + "\"", "\"" + normal + "\"")));
            } else {
              var body =
                  control.path("exchanges").get(0).path("response").path("body").path("json");
              if (ant) ((ObjectNode) body).put("stop_reason", normal);
              else ((ObjectNode) body.path("choices").get(0)).put("finish_reason", normal);
            }
            control
                .withObject("truth")
                .put("raw_finish", normal)
                .put("damage", "None; normal zero-argument call.");
            ((ObjectNode) control.path("truth").path("calls").get(0)).put("complete", true);
            control
                .withObject("provenance")
                .put(
                    "notes",
                    "Synthetic zero-argument control, twin of name-only truncation except for the"
                        + " finish reason.");
            put(out, control);
          }
        }
      }
    ObjectNode scenario =
        (ObjectNode) JSON.readTree(out.get("scenarios/truncation/name-only.json"));
    scenario
        .put("id", "tools.zero-arg-call")
        .put("spec_id", "S14")
        .put("title", "Complete call to a zero-argument tool")
        .put("summary", "Empty arguments under the normal tool-call finish reason")
        .put("wire_class", "S7 (control)")
        .put("sighting", "third-party")
        .put("http_client_axis", false);
    scenario.putArray("tools").add("flush_notes");
    out.put("scenarios/tools/zero-arg-call.json", scenario.toString());
  }

  public static Map<String, String> all() {
    try {
      Map<String, String> out = new LinkedHashMap<>();
      String[] ids = {
        "tools.single-call",
        "truncation.args-cut-mid-string",
        "truncation.name-only",
        "stream.missing-finish",
        "stream.stall-mid-args"
      };
      int[] lengths = {22, 16, 0, 22, 16};
      String[] finishes = {"tool_calls", "length", "length", null, null};
      String[] spec = {"S01", "S02", "S03", "S06", "S08"},
          classes = {"control", "T3, P1", "T1, P2", "F1, X2", "X11"};
      String[] summaries = {
        "One complete call, normal finish",
        "Arguments stop inside a string value; limit reason",
        "Call started, zero argument bytes; limit reason",
        "Complete call, then a clean HTTP end: no finish reason and no terminator",
        "Partial arguments, then silence with the connection open"
      };
      List<List<String>> evidence =
          List.of(
              List.of(SHAPE),
              List.of("https://github.com/kartsan03/wiretruth"),
              List.of(
                  "https://github.com/microsoft/semantic-kernel/issues/14723",
                  "https://github.com/spring-projects/spring-ai/issues/7360"),
              List.of("https://github.com/BerriAI/litellm/issues/42085"),
              List.of("https://github.com/BerriAI/litellm/issues/31877"));
      for (int i = 0; i < ids.length; i++) {
        ObjectNode f =
            fixture(
                ids[i],
                lengths[i],
                finishes[i],
                i == 4 ? "abort" : "clean",
                i == 4 ? 15000 : 0,
                evidence.get(i));
        out.put("fixtures/openai-chat/" + ids[i] + ".json", encode(f));
        ObjectNode s = JSON.createObjectNode();
        s.put("format", "tcf.scenario/v1")
            .put("id", ids[i])
            .put("spec_id", spec[i])
            .put("title", summaries[i])
            .put("summary", summaries[i])
            .put("wire_class", classes[i]);
        s.set("evidence", JSON.valueToTree(evidence.get(i)));
        s.put("sighting", i == 0 ? "first-party" : "third-party");
        ObjectNode wire = s.putObject("wires").putObject("openai-chat");
        wire.putArray("modes").add("stream");
        wire.putArray("variants").add("");
        ObjectNode aw = ((ObjectNode) s.get("wires")).putObject("anthropic");
        aw.putArray("modes").add("stream");
        ArrayNode variants = aw.putArray("variants");
        for (String variant : i == 2 ? List.of("no-block-stop", "block-stop") : List.of("")) {
          variants.add(variant);
          ObjectNode af =
              anthropic(
                  ids[i],
                  variant,
                  lengths[i],
                  i == 0 ? "tool_use" : i < 3 ? "max_tokens" : null,
                  i == 0 || i == 3 || variant.equals("block-stop"),
                  i == 4 ? 15000 : 0,
                  evidence.get(i));
          out.put("fixtures/" + af.path("id").asText() + ".json", encode(af));
        }
        s.putArray("tools").add("record_note");
        s.putArray("surfaces").add("loop-stream").add("model-stream");
        s.put("http_client_axis", i >= 3);
        out.put(
            "scenarios/" + ids[i].replaceFirst("\\.", "/") + ".json",
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(s) + "\n");
      }
      catalogue(out);
      for (String id : List.of("utf8-split", "cut-inside-event", "stall-2s")) {
        ObjectNode n =
            fixture(
                "selftest." + id,
                0,
                null,
                id.equals("utf8-split") ? "clean" : "abort",
                id.equals("stall-2s") ? 2000 : 0,
                List.of(SHAPE));
        n.put("id", "selftest/" + id);
        ((ArrayNode) n.get("exchanges")).remove(1);
        ObjectNode body = JSON.createObjectNode();
        ArrayNode chunks = body.putArray("chunks_b64");
        byte[] bytes = "data: café ☕\n\n".getBytes(StandardCharsets.UTF_8);
        if (id.equals("utf8-split"))
          for (byte[] part :
              List.of(
                  Arrays.copyOfRange(bytes, 0, 10),
                  Arrays.copyOfRange(bytes, 10, 14),
                  Arrays.copyOfRange(bytes, 14, bytes.length)))
            chunks.add(Base64.getEncoder().encodeToString(part));
        else
          chunks.add(
              Base64.getEncoder()
                  .encodeToString("data: {\"partial\":".getBytes(StandardCharsets.UTF_8)));
        ((ObjectNode) n.path("exchanges").get(0).path("response")).set("body", body);
        out.put("selftest/" + id + ".json", encode(n));
      }
      relabel(out);
      terminatedTwin(out);
      return out;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final String OWN_REPORTS =
      "spring-ai#7358 and #7360 were filed by this project's maintainer; they show what a client"
          + " does with the shape, not that a provider sends it.";

  /** Sighting labels say what the cited sources show, per scenario. */
  private static void relabel(Map<String, String> out) throws Exception {
    Map<String, String> sightings =
        Map.of(
            "stream.abort-after-call-start",
            "interrupted streams are documented by both providers; no sighting with a tool call open",
            "finish.stop-with-tool-call",
            "third-party, an OpenAI-compatible stream ending in stop with a truncated call; no"
                + " sighting with a complete call, none on the Anthropic wire",
            "tools.zero-arg-call",
            "third-party, on Bedrock ConverseStream; no sighting on the OpenAI or Anthropic wire",
            "tools.concatenated-args",
            "third-party, streaming only; no sighting of a non-streaming body",
            "finish.unknown-value-with-tool-call",
            "third-party documentation of further finish values on the OpenAI wire; the Anthropic"
                + " value is invented");
    for (var entry : out.entrySet()) {
      if (entry.getKey().startsWith("scenarios/")) {
        ObjectNode scenario = (ObjectNode) JSON.readTree(entry.getValue());
        String id = scenario.path("id").asText();
        if (sightings.containsKey(id)) scenario.put("sighting", sightings.get(id));
        if (scenario.path("evidence").toString().contains("spring-projects/spring-ai/issues/73"))
          scenario.put("evidence_notes", OWN_REPORTS);
        // Keep each scenario file's existing encoding.
        String before = entry.getValue();
        entry.setValue(
            before.contains("\n")
                ? JSON.writerWithDefaultPrettyPrinter().writeValueAsString(scenario)
                    + (before.endsWith("\n") ? "\n" : "")
                : scenario.toString());
      } else if (entry.getKey().startsWith("fixtures/anthropic/truncation.")) {
        ObjectNode fixture = (ObjectNode) JSON.readTree(entry.getValue());
        if (fixture.path("removes").toString().contains("content_block_stop")) {
          ((ObjectNode) fixture.get("provenance"))
              .put(
                  "notes",
                  "Synthetic replay. The shape without content_block_stop follows the one"
                      + " available capture of a truncated tool block, which was recorded with"
                      + " fine-grained tool streaming; whether the default mode omits the event"
                      + " is not established.");
          entry.setValue(encode(fixture));
        }
      }
    }
  }

  /**
   * The recorded Anthropic capture ends without the blank line that terminates its last event,
   * so a conforming parser never dispatches message_stop. This named transform appends that
   * blank line, leaving the truncation as the only damage.
   */
  private static void terminatedTwin(Map<String, String> out) throws Exception {
    String source = "anthropic/truncation.args-cut-mid-string@recorded";
    ObjectNode twin =
        (ObjectNode)
            JSON.readTree(
                java.nio.file.Files.readString(
                    java.nio.file.Path.of("../suite/fixtures/" + source + ".json")));
    twin.put("id", source + "-terminated").put("tier", "derived");
    ArrayNode frames = frames(twin);
    frames.set(frames.size() - 1, JSON.getNodeFactory().textNode(frames.get(frames.size() - 1).asText() + "\n\n"));
    ObjectNode derived = twin.putObject("derived_from");
    derived.put("fixture", source).put("transform", "terminate_final_event");
    derived.putObject("arguments").put("append", "\n\n");
    derived
        .put(
            "rationale",
            "The source file ends without the blank line that terminates its last event; the"
                + " upstream test helper re-adds line ends. With the event terminated, the cut"
                + " inside the tool input is the only damage.")
        .put("evidence", "https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation");
    ObjectNode provenance = (ObjectNode) twin.get("provenance");
    provenance.put(
        "notes",
        "Bytes of the recorded fixture with a blank line appended to the final event."
            + " Capture date is not supplied by the source.");
    provenance.put("built_by", "FixtureBuilder.terminatedTwin");
    ArrayNode removes = twin.putArray("removes");
    removes.add("content_block_stop");
    ((ObjectNode) twin.get("truth"))
        .put("damage", "Recorded tool arguments cut inside a string under a limit reason.");
    out.put("fixtures/" + source + "-terminated.json", encode(twin));
  }
}
