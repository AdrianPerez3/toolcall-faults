package toolcallfaults.runner.lint;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import toolcallfaults.runner.fixture.*;

public final class Completeness {
  private Completeness() {}

  public static List<Problem> check(Fixture f) {
    List<Problem> out = new ArrayList<>();
    if (f.wire().equals("anthropic")) return anthropic(f);
    if (!f.wire().equals("openai-chat")) return out;
    for (int i = 0; i < f.exchanges().size(); i++) {
      JsonNode frames = f.exchanges().get(i).path("response").path("body").path("sse");
      if (!frames.isArray()) continue;
      boolean finish = false, done = false;
      int j = 0;
      for (JsonNode frame : frames) {
        String path = "$.exchanges[" + i + "].response.body.sse[" + j++ + "]",
            s = frame.asText().trim();
        if (s.equals("data: [DONE]")) {
          if (j != frames.size()) add(out, f, path, "done marker must end the exchange");
          done = true;
          continue;
        }
        try {
          boolean namedError = s.startsWith("event: error\n");
          if (namedError) s = s.substring("event: error\n".length());
          if (!s.startsWith("data: ")) throw new IllegalArgumentException("expected data frame");
          JsonNode n = FixtureLoader.JSON.readTree(s.substring(6));
          if (namedError || n.has("error")) {
            if (!n.path("error").path("type").isTextual()
                || !n.path("error").path("message").isTextual())
              add(out, f, path, "error envelope needs type and message");
            // An in-band error is the declared damage only where it ends the damaged stream.
            if (i != 0 || j != frames.size())
              add(out, f, path, "error envelope must be the last frame of the first exchange");
            continue;
          }
          for (String key :
              List.of("id", "object", "created", "model", "system_fingerprint", "choices"))
            if (!n.has(key)) add(out, f, path, "missing " + key);
          if (!n.path("object").asText().equals("chat.completion.chunk"))
            add(out, f, path, "object must be chat.completion.chunk");
          for (JsonNode choice : n.path("choices")) {
            for (String key : List.of("index", "delta"))
              if (!choice.has(key)) add(out, f, path, "missing " + key);
            JsonNode delta = choice.path("delta");
            if (!choice.path("finish_reason").isNull()
                && !choice.path("finish_reason").isMissingNode()) {
              finish = true;
              if (!delta.isObject() || !delta.isEmpty())
                add(out, f, path, "finish chunk needs empty delta");
            }
            if (j == 1 && i == 0 && !f.truth().calls().isEmpty()) {
              for (String key : List.of("role", "content", "tool_calls", "refusal"))
                if (!delta.has(key)) add(out, f, path, "identity missing " + key);
              if (delta.has("content") && !delta.get("content").isNull())
                add(out, f, path, "identity content must be null");
              if (delta.has("refusal") && !delta.get("refusal").isNull())
                add(out, f, path, "identity refusal must be null");
            }
            for (JsonNode call : delta.path("tool_calls")) {
              if (!call.has("index")) add(out, f, path, "tool delta missing index");
              if (!call.path("function").isObject())
                add(out, f, path, "tool delta missing function object");
            }
          }
        } catch (Exception e) {
          add(out, f, path, "invalid event JSON: " + e.getMessage());
        }
      }
      // Removals describe the damaged first exchange; follow-up exchanges stay complete.
      if (!finish && !(i == 0 && f.removes().contains("finish-chunk")))
        add(out, f, "$.exchanges[" + i + "].response.body.sse", "missing finish chunk");
      if (!done && !(i == 0 && f.removes().contains("done-marker")))
        add(out, f, "$.exchanges[" + i + "].response.body.sse", "missing done marker");
    }
    return out;
  }

  private static List<Problem> anthropic(Fixture f) {
    List<Problem> out = new ArrayList<>();
    for (int i = 0; i < f.exchanges().size(); i++) {
      JsonNode frames = f.exchanges().get(i).path("response").path("body").path("sse");
      if (!frames.isArray()) continue;
      boolean start = false, stop = false, messageDelta = false;
      Map<Integer, String> pending = new LinkedHashMap<>(), open = new LinkedHashMap<>();
      int j = 0;
      for (JsonNode frame : frames) {
        String path = "$.exchanges[" + i + "].response.body.sse[" + j++ + "]";
        String event = null, data = null;
        for (String line : frame.asText().split("\n")) {
          if (line.startsWith("event: ")) event = line.substring(7);
          if (line.startsWith("data: ")) data = line.substring(6);
        }
        if (event == null) add(out, f, path, "missing event: line");
        try {
          if (data == null) throw new IllegalArgumentException("missing data: line");
          JsonNode n = FixtureLoader.JSON.readTree(data);
          String type = n.path("type").asText();
          if (event != null && !event.equals(type))
            add(out, f, path, "event: line must match type");
          if (type.equals("message_start")) {
            if (start || !open.isEmpty() || messageDelta)
              add(out, f, path, "message_start must open the exchange");
            start = true;
          }
          if (stop && (type.startsWith("message_") || type.startsWith("content_block_")))
            add(out, f, path, "message_stop must end the exchange");
          if (!start && type.startsWith("content_block_"))
            add(out, f, path, "content block before message_start");
          if (type.equals("message_start") && !n.path("message").path("usage").isObject()
              || type.equals("message_delta") && !n.path("usage").isObject())
            add(out, f, path, "missing usage object");
          int index = n.path("index").asInt();
          if (type.equals("content_block_start")) open.put(index, path);
          if (type.equals("content_block_stop")) open.remove(index);
          if (type.equals("message_delta")) messageDelta = true;
          if (type.equals("content_block_start")
              && n.path("content_block").path("type").asText().equals("tool_use"))
            pending.put(index, path);
          if (type.equals("content_block_delta")
              && n.path("delta").path("type").asText().equals("input_json_delta")
              && pending.remove(index) != null) {
            if (!n.path("delta").path("partial_json").isTextual()
                || !n.path("delta").path("partial_json").asText().isEmpty())
              add(out, f, path, "leading empty input_json_delta required");
          }
          if (type.equals("message_stop")) stop = true;
        } catch (Exception e) {
          add(out, f, path, "invalid event JSON: " + e.getMessage());
        }
      }
      if (!start) add(out, f, "$.exchanges[" + i + "].response.body.sse", "missing message_start");
      for (String path : pending.values())
        add(out, f, path, "leading empty input_json_delta required");
      // Removals describe the damaged first exchange; follow-up exchanges stay complete.
      if (!(i == 0 && f.removes().contains("content_block_stop")))
        for (String path : open.values()) add(out, f, path, "missing content_block_stop");
      if (!messageDelta && !(i == 0 && f.removes().contains("message_delta")))
        add(out, f, "$.exchanges[" + i + "].response.body.sse", "missing message_delta");
      if (!stop && !(i == 0 && f.removes().contains("message_stop")))
        add(out, f, "$.exchanges[" + i + "].response.body.sse", "missing message_stop");
    }
    return out;
  }

  private static void add(List<Problem> out, Fixture f, String path, String message) {
    out.add(new Problem(f.file().toString(), path, message));
  }
}
