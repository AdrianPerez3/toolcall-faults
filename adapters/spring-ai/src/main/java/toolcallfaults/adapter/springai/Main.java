package toolcallfaults.adapter.springai;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import toolcallfaults.kit.*;

public final class Main {
  private static ChatModel deepSeek(Flags flags) {
    Duration timeout = Duration.ofMillis(flags.timeoutMs());
    var http =
        reactor.netty.http.client.HttpClient.create()
            .responseTimeout(timeout)
            .option(
                io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.toIntExact(flags.timeoutMs()))
            .disableRetry(true);
    var requestFactory = new org.springframework.http.client.ReactorClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(timeout);
    var api =
        DeepSeekApi.builder()
            .baseUrl(flags.baseUrl())
            .apiKey("sk-test")
            .completionsPath("/v1/chat/completions")
            .webClientBuilder(
                org.springframework.web.reactive.function.client.WebClient.builder()
                    .clientConnector(
                        new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                            http)))
            .restClientBuilder(
                org.springframework.web.client.RestClient.builder().requestFactory(requestFactory))
            .build();
    return DeepSeekChatModel.builder()
        .deepSeekApi(api)
        .options(DeepSeekChatOptions.builder().model("deepseek-chat").build())
        .retryTemplate(
            new org.springframework.core.retry.RetryTemplate(
                org.springframework.core.retry.RetryPolicy.withMaxRetries(0)))
        .build();
  }

  public static final class NoteTool {
    private final Recorder recorder;

    public NoteTool(Recorder recorder) {
      this.recorder = recorder;
    }

    @Tool(name = "record_note", description = "Record a note")
    public String recordNote(Integer priority, String title, String body) {
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("priority", priority);
      args.put("title", title);
      args.put("body", body);
      return recorder.invoked("record_note", args);
    }
  }

  public static final class WriteTool {
    private final Recorder recorder;
    private final java.nio.file.Path work;

    public WriteTool(Recorder recorder, java.nio.file.Path work) {
      this.recorder = recorder;
      this.work = work;
    }

    @Tool(name = "write_file", description = "Write content to a file")
    public String writeFile(String path, String content) {
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("path", path);
      args.put("content", content);
      recorder.invoked("write_file", args);
      return FileTool.write(work, path, content);
    }
  }

  public static final class LevelTool {
    private final Recorder recorder;

    public LevelTool(Recorder recorder) {
      this.recorder = recorder;
    }

    @Tool(name = "set_level", description = "Set the level")
    public String setLevel(int level, boolean urgent) {
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("level", level);
      args.put("urgent", urgent);
      return recorder.invoked("set_level", args);
    }
  }

  public static final class FlushTool {
    private final Recorder recorder;

    public FlushTool(Recorder recorder) {
      this.recorder = recorder;
    }

    @Tool(name = "flush_notes", description = "Flush the notes")
    public String flushNotes() {
      return recorder.invoked("flush_notes", Map.of());
    }
  }

  private static Object tool(Flags flags, Recorder recorder) {
    return switch (flags.tools().get(0)) {
      case "record_note" -> new NoteTool(recorder);
      case "set_level" -> new LevelTool(recorder);
      case "flush_notes" -> new FlushTool(recorder);
      case "write_file" -> new WriteTool(recorder, flags.workdir());
      default -> throw new IllegalArgumentException("Unsupported tool");
    };
  }

  private static ToolCallback[] definitions(Flags flags, Recorder recorder) {
    String name = flags.tools().get(0);
    if (!Set.of("create_event", "make_file").contains(name))
      return ToolCallbacks.from(tool(flags, recorder));
    String schema =
        name.equals("create_event")
            ? "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"date\":{\"type\":\"string\"},\"city\":{\"type\":\"string\"},\"attendees\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"title\",\"date\",\"city\",\"attendees\"]}"
            : "{\"type\":\"object\",\"properties\":{\"filename\":{\"type\":\"string\"},\"lines_of_text\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"filename\",\"lines_of_text\"]}";
    return new ToolCallback[] {
      new ToolCallback() {
        public ToolDefinition getToolDefinition() {
          return ToolDefinition.builder()
              .name(name)
              .description("Tool from the recorded fixture")
              .inputSchema(schema)
              .build();
        }

        public String call(String input) {
          throw new IllegalStateException("Recorded tools are definitions only");
        }
      }
    };
  }

  private static void observe(ChatResponse response, ResultLine line, AtomicInteger chunks) {
    line.chunks(chunks.incrementAndGet());
    for (var generation : response.getResults()) {
      var output = generation.getOutput();
      if (output != null) {
        line.callerText(output.getText());
        output.getToolCalls().forEach(c -> line.callerToolCall(c.id(), c.name(), c.arguments()));
      }
      if (generation.getMetadata() != null)
        line.callerFinish(generation.getMetadata().getFinishReason());
    }
  }

  private static String api(String surface) {
    return switch (surface) {
      case "loop-stream" -> "ChatClient.prompt().tools().stream().chatResponse()";
      case "loop-call" -> "ChatClient.prompt().tools().call().chatResponse()";
      case "model-stream" -> "ChatModel.stream(Prompt)";
      default -> throw new IllegalArgumentException("Unsupported surface");
    };
  }

  public static void main(String[] args) throws Exception {
    long start = System.currentTimeMillis();
    Flags flags = Flags.parse(args);
    Recorder recorder = new Recorder(flags.workdir());
    Signals signals = new Signals(recorder);
    ResultLine line =
        ResultLine.start(flags, "spring-ai", api(flags.surface()))
            .httpClient(flags.module().equals("spring-ai-deepseek") ? "reactor-netty" : "okhttp")
            .setting("timeout_ms", flags.timeoutMs())
            .setting("max_retries", 0)
            .watch(
                "org.springframework.ai:spring-ai-openai",
                "org.springframework.ai:spring-ai-anthropic",
                "org.springframework.ai:spring-ai-client-chat",
                "org.springframework.ai:spring-ai-deepseek",
                "com.openai:openai-java-core",
                "com.anthropic:anthropic-java-core",
                "com.squareup.okhttp3:okhttp",
                "io.projectreactor:reactor-core",
                "io.projectreactor.netty:reactor-netty-http",
                "io.netty:netty-transport",
                "org.springframework:spring-core",
                "tools.jackson.core:jackson-databind",
                "com.fasterxml.jackson.core:jackson-databind",
                "com.fasterxml.jackson.core:jackson-annotations");
    AtomicInteger chunks = new AtomicInteger();
    try {
      if (!Set.of("openai-chat", "anthropic").contains(flags.wire())
          || !Set.of("loop-stream", "loop-call", "model-stream").contains(flags.surface())
          || flags.tools().size() != 1)
        throw new IllegalArgumentException("Unsupported adapter flags");
      ChatModel model =
          flags.module().equals("spring-ai-deepseek")
              ? deepSeek(flags)
              : flags.wire().equals("anthropic")
                  ? AnthropicChatModel.builder()
                      .options(
                          AnthropicChatOptions.builder()
                              .baseUrl(flags.baseUrl())
                              .apiKey("sk-test")
                              .model("claude-sonnet-4-5")
                              .maxTokens(1024)
                              .maxRetries(0)
                              .timeout(Duration.ofMillis(flags.timeoutMs()))
                              .build())
                      .build()
                  : OpenAiChatModel.builder()
                      .options(
                          OpenAiChatOptions.builder()
                              .baseUrl(flags.baseUrl() + "/v1")
                              .apiKey("sk-test")
                              .model("gpt-4o")
                              .maxRetries(0)
                              .timeout(Duration.ofMillis(flags.timeoutMs()))
                              .build())
                      .build();
      if (flags.surface().equals("loop-call")) {
        observe(
            ChatClient.create(model)
                .prompt(flags.prompt())
                .tools(tool(flags, recorder))
                .call()
                .chatResponse(),
            line,
            chunks);
        signals.complete();
      } else {
        var stream =
            flags.surface().equals("model-stream")
                ? model.stream(
                    new Prompt(
                        flags.prompt(),
                        ((ToolCallingChatOptions) model.getOptions())
                            .mutate()
                            .toolCallbacks(definitions(flags, recorder))
                            .build()))
                : ChatClient.create(model)
                    .prompt(flags.prompt())
                    .tools(tool(flags, recorder))
                    .stream()
                    .chatResponse();
        stream.subscribe(r -> observe(r, line, chunks), signals::error, signals::complete);
      }
    } catch (Throwable error) {
      signals.error(error);
    }
    signals.awaitFirst(60000);
    signals.linger();
    line.emitAndHalt(signals, recorder, start);
  }
}
