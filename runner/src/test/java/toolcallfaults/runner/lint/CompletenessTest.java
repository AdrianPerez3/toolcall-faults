package toolcallfaults.runner.lint;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.fixture.FixtureTestSupport.*;

import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.fixture.*;

class CompletenessTest {
  @TempDir Path dir;

  @Test
  void error_envelopes_are_not_completion_chunks() throws Exception {
    for (String prefix : java.util.List.of("", "event: error\n")) {
      var n = example();
      var a = (ArrayNode) response(n).path("body").path("sse");
      a.remove(2);
      a.remove(1);
      a.add(
          prefix + "data: {\"error\":{\"type\":\"server_error\",\"message\":\"Unavailable\"}}\n\n");
      n.withArray("removes").add("finish-chunk").add("done-marker");
      assertTrue(check(n).isEmpty(), check(n).toString());
      a.set(1, JSON.getNodeFactory().textNode(prefix + "data: {\"error\":{}}\n\n"));
      assertFalse(check(n).isEmpty());
    }
  }

  ObjectNode anthropic() throws Exception {
    var n = example();
    n.put("wire", "anthropic");
    ((ArrayNode) n.get("exchanges")).remove(1);
    var frames = ((ObjectNode) response(n).get("body")).putArray("sse");
    for (String json :
        java.util.List.of(
            "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call1\",\"name\":\"record_note\",\"input\":{}}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\"}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":2}}",
            "{\"type\":\"message_stop\"}")) frames.add(anthropicEvent(json));
    return n;
  }

  String anthropicEvent(String json) throws Exception {
    return "event: " + JSON.readTree(json).path("type").asText() + "\ndata: " + json + "\n\n";
  }

  java.util.List<Problem> check(ObjectNode n) throws Exception {
    return Completeness.check(FixtureLoader.load(save(dir, n)));
  }

  @Test
  void event_line_required() throws Exception {
    var n = anthropic();
    var frames = (ArrayNode) response(n).path("body").path("sse");
    frames.set(
        0,
        JSON.getNodeFactory()
            .textNode(frames.get(0).asText().replace("event: message_start\n", "")));
    var problems = check(n);
    assertEquals(1, problems.size());
    assertTrue(problems.get(0).message().contains("event:"));
  }

  @Test
  void usage_required_on_message_start_and_delta() throws Exception {
    for (int i : new int[] {0, 5}) {
      var n = anthropic();
      var frames = (ArrayNode) response(n).path("body").path("sse");
      String frame = frames.get(i).asText();
      var event = (ObjectNode) JSON.readTree(frame.substring(frame.indexOf("data: ") + 6));
      (i == 0 ? (ObjectNode) event.get("message") : event).remove("usage");
      frames.set(i, JSON.getNodeFactory().textNode(anthropicEvent(event.toString())));
      var problems = check(n);
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).message().contains("usage"));
    }
  }

  @Test
  void leading_empty_input_json_delta_required() throws Exception {
    var n = anthropic();
    ((ArrayNode) response(n).path("body").path("sse")).remove(2);
    var problems = check(n);
    assertEquals(1, problems.size());
    assertTrue(problems.get(0).message().contains("empty input_json_delta"));
  }

  @Test
  void message_stop_required_unless_removed() throws Exception {
    var n = anthropic();
    ((ArrayNode) response(n).path("body").path("sse")).remove(6);
    assertEquals(1, check(n).size());
    n.withArray("removes").add("message_stop");
    assertTrue(check(n).isEmpty());
  }

  java.util.List<Problem> remove(
      String field, int frame, boolean choice, boolean delta, boolean tool) throws Exception {
    var n = example();
    var a = (ArrayNode) response(n).path("body").path("sse");
    ObjectNode event = (ObjectNode) JSON.readTree(a.get(frame).asText().substring(6).trim());
    ObjectNode obj = event;
    if (choice) obj = (ObjectNode) event.path("choices").get(0);
    if (delta) obj = (ObjectNode) obj.path("delta");
    if (tool) obj = (ObjectNode) obj.path("tool_calls").get(0);
    obj.remove(field);
    a.set(
        frame, JSON.getNodeFactory().textNode("data: " + JSON.writeValueAsString(event) + "\n\n"));
    return Completeness.check(FixtureLoader.load(save(dir, n)));
  }

  @Test
  void chunk_missing_created_reported() throws Exception {
    assertTrue(
        remove("created", 1, false, false, false).stream()
            .anyMatch(
                p ->
                    p.jsonPath().equals("$.exchanges[0].response.body.sse[1]")
                        && p.message().contains("created")));
  }

  @Test
  void chunk_needs_id_object_model_choices_index_delta_fingerprint() throws Exception {
    for (String f : new String[] {"id", "object", "model", "choices", "system_fingerprint"})
      assertFalse(remove(f, 1, false, false, false).isEmpty(), f);
    for (String f : new String[] {"index", "delta"})
      assertFalse(remove(f, 1, true, false, false).isEmpty(), f);
  }

  @Test
  void tool_call_delta_needs_index_and_function() throws Exception {
    for (String f : new String[] {"index", "function"})
      assertFalse(remove(f, 0, true, true, true).isEmpty(), f);
  }

  @Test
  void identity_chunk_needs_role_content_null_tool_calls_refusal_null() throws Exception {
    for (String f : new String[] {"role", "content", "tool_calls", "refusal"})
      assertFalse(remove(f, 0, true, true, false).isEmpty(), f);
  }

  @Test
  void finish_chunk_and_done_required_unless_removed() throws Exception {
    var n = example();
    var a = (ArrayNode) response(n).path("body").path("sse");
    a.remove(2);
    a.remove(1);
    assertEquals(2, Completeness.check(FixtureLoader.load(save(dir, n))).size());
    n.withArray("removes").add("finish-chunk").add("done-marker");
    assertTrue(Completeness.check(FixtureLoader.load(save(dir, n))).isEmpty());
  }

  @Test
  void content_block_stop_and_message_delta_required_unless_removed() throws Exception {
    var n = anthropic();
    var frames = (ArrayNode) response(n).path("body").path("sse");
    frames.remove(5); // message_delta
    frames.remove(4); // content_block_stop
    assertEquals(2, check(n).size(), check(n).toString());
    n.withArray("removes").add("content_block_stop").add("message_delta");
    assertTrue(check(n).isEmpty(), check(n).toString());
  }

  @Test
  void removals_exempt_only_the_damaged_first_exchange() throws Exception {
    var n = example();
    n.withArray("removes").add("finish-chunk").add("done-marker");
    var followUp =
        (ArrayNode) n.path("exchanges").get(1).path("response").path("body").path("sse");
    followUp.remove(2);
    followUp.remove(1);
    assertEquals(2, check(n).size(), check(n).toString());
  }

  @Test
  void error_envelope_is_accepted_only_as_the_last_frame_of_the_first_exchange() throws Exception {
    var n = example();
    var a = (ArrayNode) response(n).path("body").path("sse");
    a.insert(1, "data: {\"error\":{\"type\":\"server_error\",\"message\":\"Unavailable\"}}\n\n");
    assertEquals(1, check(n).size(), check(n).toString());
  }
}
