package toolcallfaults.adapter.openaijava;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import toolcallfaults.kit.*;

public final class Main {
  private static ChatCompletionFunctionTool definition(String name) {
    Map<String, Object> properties = new LinkedHashMap<>();
    switch (name) {
      case "record_note" -> {
        properties.put("priority", Map.of("type", "integer"));
        properties.put("title", Map.of("type", "string"));
        properties.put("body", Map.of("type", "string"));
      }
      case "set_level" -> {
        properties.put("level", Map.of("type", "integer"));
        properties.put("urgent", Map.of("type", "boolean"));
      }
      case "flush_notes" -> {}
      case "create_event" -> {
        for (String field : List.of("title", "date", "city"))
          properties.put(field, Map.of("type", "string"));
        properties.put("attendees", Map.of("type", "array", "items", Map.of("type", "string")));
      }
      default -> throw new IllegalArgumentException("Unsupported tool " + name);
    }
    return ChatCompletionFunctionTool.builder()
        .function(
            FunctionDefinition.builder()
                .name(name)
                .description("Recorder tool")
                .parameters(
                    FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(properties))
                        .putAdditionalProperty(
                            "required", JsonValue.from(new ArrayList<>(properties.keySet())))
                        .build())
                .build())
        .build();
  }

  private static void observe(
      ChatCompletion response, ResultLine line, boolean includeTextAndFinish) {
    for (var choice : response.choices()) {
      if (includeTextAndFinish) {
        line.callerFinish(choice.finishReason().toString());
        choice.message().content().ifPresent(line::callerText);
      }
      for (var tool : choice.message().toolCalls().orElse(List.of())) {
        var call = tool.asFunction();
        line.callerToolCall(call.id(), call.function().name(), call.function().arguments());
      }
    }
  }

  public static void main(String[] args) throws Exception {
    long start = System.currentTimeMillis();
    Flags flags = Flags.parse(args);
    Recorder recorder = new Recorder(flags.workdir());
    Signals signals = new Signals(recorder);
    ResultLine line =
        ResultLine.start(
                flags,
                "openai-java",
                flags.surface().equals("model-stream")
                    ? "chat().completions().createStreaming() with ChatCompletionAccumulator"
                    : "chat().completions().create()")
            .httpClient("okhttp")
            .setting("timeout_ms", flags.timeoutMs())
            .setting("max_retries", 0)
            .watch(
                "com.openai:openai-java-core",
                "com.squareup.okhttp3:okhttp",
                "com.fasterxml.jackson.core:jackson-databind");
    AtomicInteger chunks = new AtomicInteger();
    try {
      if (!flags.wire().equals("openai-chat")
          || !Set.of("model-stream", "model-call").contains(flags.surface())
          || flags.tools().size() != 1)
        throw new IllegalArgumentException("Unsupported adapter flags");
      var client =
          OpenAIOkHttpClient.builder()
              .baseUrl(flags.baseUrl() + "/v1")
              .apiKey("test")
              .maxRetries(0)
              .timeout(Duration.ofMillis(flags.timeoutMs()))
              .build();
      try {
        var params =
            ChatCompletionCreateParams.builder()
                .model("gpt-4o")
                .addUserMessage(flags.prompt())
                .addTool(definition(flags.tools().get(0)))
                .build();
        if (flags.surface().equals("model-call")) {
          observe(client.chat().completions().create(params), line, true);
          line.chunks(1);
        } else {
          var accumulator = ChatCompletionAccumulator.create();
          try (var stream = client.chat().completions().createStreaming(params)) {
            stream.stream()
                .forEach(
                    chunk -> {
                      line.chunks(chunks.incrementAndGet());
                      for (var choice : chunk.choices()) {
                        choice
                            .finishReason()
                            .ifPresent(reason -> line.callerFinish(reason.toString()));
                        choice.delta().content().ifPresent(line::callerText);
                      }
                      accumulator.accumulate(chunk);
                    });
          }
          observe(accumulator.chatCompletion(), line, false);
        }
        signals.complete();
      } finally {
        client.close();
      }
    } catch (Throwable error) {
      signals.error(error);
    }
    signals.linger();
    line.emitAndHalt(signals, recorder, start);
  }
}
