package toolcallfaults.adapter.anthropicsdk;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.helpers.*;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam.Content;
import com.anthropic.models.beta.messages.ToolRunnerCreateParams;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import toolcallfaults.kit.*;

public final class Main {
  @JsonTypeName("record_note")
  public static final class RecordNote {
    public Integer priority;
    public String title;
    public String body;
  }

  @JsonTypeName("set_level")
  public static final class SetLevel {
    public int level;
    public boolean urgent;
  }

  private static com.anthropic.models.messages.Tool definition(String name) {
    Map<String, Object> fields = new LinkedHashMap<>();
    switch (name) {
      case "record_note" -> {
        fields.put("priority", Map.of("type", "integer"));
        fields.put("title", Map.of("type", "string"));
        fields.put("body", Map.of("type", "string"));
      }
      case "set_level" -> {
        fields.put("level", Map.of("type", "integer"));
        fields.put("urgent", Map.of("type", "boolean"));
      }
      case "flush_notes" -> {}
      case "make_file" -> {
        fields.put("filename", Map.of("type", "string"));
        fields.put("lines_of_text", Map.of("type", "array", "items", Map.of("type", "string")));
      }
      default -> throw new IllegalArgumentException("Unsupported tool " + name);
    }
    var properties = com.anthropic.models.messages.Tool.InputSchema.Properties.builder();
    fields.forEach(
        (key, value) ->
            properties.putAdditionalProperty(key, com.anthropic.core.JsonValue.from(value)));
    return com.anthropic.models.messages.Tool.builder()
        .name(name)
        .description("Recorder tool")
        .inputSchema(
            com.anthropic.models.messages.Tool.InputSchema.builder()
                .type(com.anthropic.core.JsonValue.from("object"))
                .properties(properties.build())
                .required(new ArrayList<>(fields.keySet()))
                .build())
        .build();
  }

  private static BetaRunnableTool tool(String name, Recorder recorder) {
    return switch (name) {
      case "record_note" ->
          BetaRunnableTool.of(
              RecordNote.class,
              input -> {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("priority", input.priority);
                args.put("title", input.title);
                args.put("body", input.body);
                return Content.ofString(recorder.invoked(name, args));
              });
      case "set_level" ->
          BetaRunnableTool.of(
              SetLevel.class,
              input ->
                  Content.ofString(
                      recorder.invoked(
                          name, Map.of("level", input.level, "urgent", input.urgent))));
      case "flush_notes" ->
          BetaRunnableTool.of(
              com.anthropic.models.beta.messages.BetaTool.builder()
                  .name(name)
                  .description("Flush the notes")
                  .inputSchema(
                      com.anthropic.models.beta.messages.BetaTool.InputSchema.builder()
                          .type(com.anthropic.core.JsonValue.from("object"))
                          .properties(
                              com.anthropic.models.beta.messages.BetaTool.InputSchema.Properties
                                  .builder()
                                  .build())
                          .required(List.of())
                          .build())
                  .build(),
              input -> Content.ofString(recorder.invoked(name, Map.of())));
      default -> throw new IllegalArgumentException("Unsupported loop tool " + name);
    };
  }

  private static void observe(Message message, ResultLine line, boolean includeFinish) {
    if (includeFinish)
      message.stopReason().ifPresent(reason -> line.callerFinish(reason.toString()));
    for (var block : message.content()) {
      block.text().ifPresent(text -> line.callerText(text.text()));
      block
          .toolUse()
          .ifPresent(
              call ->
                  line.callerToolCall(
                      call.id(), call.name(), Json.write(call._input().convert(Object.class))));
    }
  }

  private static void observe(BetaMessage message, ResultLine line, boolean includeFinish) {
    if (includeFinish)
      message.stopReason().ifPresent(reason -> line.callerFinish(reason.toString()));
    for (var block : message.content()) {
      block.text().ifPresent(text -> line.callerText(text.text()));
      block
          .toolUse()
          .ifPresent(
              call ->
                  line.callerToolCall(
                      call.id(), call.name(), Json.write(call._input().convert(Object.class))));
    }
  }

  public static void main(String[] args) throws Exception {
    long start = System.currentTimeMillis();
    Flags flags = Flags.parse(args);
    Recorder recorder = new Recorder(flags.workdir());
    Signals signals = new Signals(recorder);
    String api =
        switch (flags.surface()) {
          case "loop-stream" -> "beta().messages().toolRunner().streaming()";
          case "loop-call" -> "beta().messages().toolRunner()";
          case "model-stream" -> "messages().createStreaming() with MessageAccumulator";
          case "model-call" -> "messages().create()";
          default -> throw new IllegalArgumentException("Unsupported surface");
        };
    ResultLine line =
        ResultLine.start(flags, "anthropic-sdk-java", api)
            .httpClient("okhttp")
            .setting("timeout_ms", flags.timeoutMs())
            .setting("max_retries", 0)
            .watch(
                "com.anthropic:anthropic-java-core",
                "com.squareup.okhttp3:okhttp",
                "com.fasterxml.jackson.core:jackson-databind");
    AtomicInteger chunks = new AtomicInteger();
    try {
      if (!flags.wire().equals("anthropic") || flags.tools().size() != 1)
        throw new IllegalArgumentException("Unsupported adapter flags");
      var client =
          AnthropicOkHttpClient.builder()
              .baseUrl(flags.baseUrl())
              .apiKey("test")
              .maxRetries(0)
              .timeout(Duration.ofMillis(flags.timeoutMs()))
              .build();
      try {
        String name = flags.tools().get(0);
        if (flags.surface().startsWith("loop-")) {
          line.setting("thread", false);
          var params =
              com.anthropic.models.beta.messages.MessageCreateParams.builder()
                  .model("claude-sonnet-4-5")
                  .maxTokens(1024)
                  .addUserMessage(flags.prompt())
                  .addTool(tool(name, recorder))
                  .build();
          var runner =
              client
                  .beta()
                  .messages()
                  .toolRunner(
                      ToolRunnerCreateParams.builder()
                          .initialMessageParams(params)
                          .thread(false)
                          .build());
          if (flags.surface().equals("loop-call")) {
            for (var message : runner) {
              line.chunks(chunks.incrementAndGet());
              observe(message, line, true);
            }
          } else {
            for (var response : runner.streaming()) {
              var accumulator = BetaMessageAccumulator.create();
              try (response) {
                response.stream()
                    .forEach(
                        event -> {
                          line.chunks(chunks.incrementAndGet());
                          event
                              .messageDelta()
                              .ifPresent(
                                  delta ->
                                      delta
                                          .delta()
                                          .stopReason()
                                          .ifPresent(
                                              reason -> line.callerFinish(reason.toString())));
                          accumulator.accumulate(event);
                        });
              }
              observe(accumulator.message(), line, false);
            }
          }
        } else {
          var params =
              MessageCreateParams.builder()
                  .model("claude-sonnet-4-5")
                  .maxTokens(1024)
                  .addUserMessage(flags.prompt())
                  .addTool(definition(name))
                  .build();
          if (flags.surface().equals("model-call")) {
            observe(client.messages().create(params), line, true);
            line.chunks(1);
          } else {
            var accumulator = MessageAccumulator.create();
            try (var response = client.messages().createStreaming(params)) {
              response.stream()
                  .forEach(
                      event -> {
                        line.chunks(chunks.incrementAndGet());
                        event
                            .messageDelta()
                            .ifPresent(
                                delta ->
                                    delta
                                        .delta()
                                        .stopReason()
                                        .ifPresent(reason -> line.callerFinish(reason.toString())));
                        accumulator.accumulate(event);
                      });
            }
            observe(accumulator.message(), line, false);
          }
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
