package toolcallfaults.adapter.langchain4j;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import toolcallfaults.kit.*;

public final class Main {
  interface Assistant {
    TokenStream chat(String message);
  }

  public static final class NoteTool {
    private final Recorder recorder;

    public NoteTool(Recorder recorder) {
      this.recorder = recorder;
    }

    @Tool(name = "record_note", value = "Record a note")
    public String recordNote(
        @P("priority") Integer priority, @P("title") String title, @P("body") String body) {
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

    @Tool(name = "write_file", value = "Write content to a file")
    public String writeFile(@P("path") String path, @P("content") String content) {
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

    @Tool(name = "set_level", value = "Set the level")
    public String setLevel(@P("level") int level, @P("urgent") boolean urgent) {
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

    @Tool(name = "flush_notes", value = "Flush the notes")
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

  interface BlockingAssistant {
    dev.langchain4j.service.Result<String> chat(String message);
  }

  private static String api(String surface) {
    return switch (surface) {
      case "loop-stream" -> "AiServices TokenStream";
      case "loop-call" -> "AiServices over ChatModel";
      case "model-stream" -> "StreamingChatModel.chat(ChatRequest, StreamingChatResponseHandler)";
      default -> throw new IllegalArgumentException("Unsupported surface");
    };
  }

  private static List<dev.langchain4j.agent.tool.ToolSpecification> definitions(
      Flags flags, Recorder recorder) {
    String name = flags.tools().get(0);
    if (!Set.of("create_event", "make_file").contains(name))
      return dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(
          tool(flags, recorder));
    var schema = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder();
    var strings =
        dev.langchain4j.model.chat.request.json.JsonArraySchema.builder()
            .items(dev.langchain4j.model.chat.request.json.JsonStringSchema.builder().build())
            .build();
    if (name.equals("create_event"))
      schema
          .addStringProperty("title")
          .addStringProperty("date")
          .addStringProperty("city")
          .addProperty("attendees", strings)
          .required("title", "date", "city", "attendees");
    else
      schema
          .addStringProperty("filename")
          .addProperty("lines_of_text", strings)
          .required("filename", "lines_of_text");
    return List.of(
        dev.langchain4j.agent.tool.ToolSpecification.builder()
            .name(name)
            .description("Tool from the recorded fixture")
            .parameters(schema.build())
            .build());
  }

  private static void observe(
      dev.langchain4j.model.chat.response.ChatResponse response, ResultLine line) {
    line.callerText(response.aiMessage().text());
    if (response.finishReason() != null) line.callerFinish(response.finishReason().toString());
    response
        .aiMessage()
        .toolExecutionRequests()
        .forEach(c -> line.callerToolCall(c.id(), c.name(), c.arguments()));
  }

  private static void modelStream(
      StreamingChatModel model,
      Flags flags,
      Recorder recorder,
      ResultLine line,
      Signals signals,
      AtomicInteger chunks) {
    var request =
        dev.langchain4j.model.chat.request.ChatRequest.builder()
            .messages(dev.langchain4j.data.message.UserMessage.from(flags.prompt()))
            .toolSpecifications(definitions(flags, recorder))
            .build();
    model.chat(
        request,
        new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
          final List<Map<String, Object>> completed = new ArrayList<>();
          final List<Map<String, Object>> partials = new ArrayList<>();

          public void onPartialResponse(String text) {
            line.chunks(chunks.incrementAndGet());
          }

          public void onPartialToolCall(dev.langchain4j.model.chat.response.PartialToolCall call) {
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("index", call.index());
            partial.put("id", call.id());
            partial.put("name", call.name());
            partial.put("arguments_fragment", call.partialArguments());
            partials.add(partial);
            line.callbackOnly("partial_tool_calls", List.copyOf(partials));
          }

          // An optional callback with a default no-op body: recorded, not part of the caller
          // view, which is the response every handler receives.
          public void onCompleteToolCall(
              dev.langchain4j.model.chat.response.CompleteToolCall call) {
            var c = call.toolExecutionRequest();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", c.id());
            view.put("name", c.name());
            view.put("arguments_raw", c.arguments());
            completed.add(view);
            line.callbackOnly("complete_tool_calls", List.copyOf(completed));
          }

          public void onCompleteResponse(
              dev.langchain4j.model.chat.response.ChatResponse response) {
            line.callerText(response.aiMessage().text());
            if (response.finishReason() != null)
              line.callerFinish(response.finishReason().toString());
            response
                .aiMessage()
                .toolExecutionRequests()
                .forEach(c -> line.callerToolCall(c.id(), c.name(), c.arguments()));
            signals.complete();
          }

          public void onError(Throwable error) {
            signals.error(error);
          }
        });
  }

  public static void main(String[] args) throws Exception {
    long start = System.currentTimeMillis();
    Flags flags = Flags.parse(args);
    Recorder recorder = new Recorder(flags.workdir());
    Signals signals = new Signals(recorder);
    ResultLine line =
        ResultLine.start(flags, "langchain4j", api(flags.surface()))
            .httpClient(flags.httpClient())
            .setting("timeout_ms", flags.timeoutMs())
            .watch(
                "dev.langchain4j:langchain4j",
                "dev.langchain4j:langchain4j-core",
                "dev.langchain4j:langchain4j-open-ai",
                "dev.langchain4j:langchain4j-anthropic",
                "dev.langchain4j:langchain4j-http-client",
                "dev.langchain4j:langchain4j-http-client-jdk",
                "dev.langchain4j:langchain4j-http-client-okhttp",
                "com.squareup.okhttp3:okhttp-jvm",
                "com.fasterxml.jackson.core:jackson-databind");
    List<String> intermediateFinishes = new ArrayList<>();
    List<Map<String, Object>> intermediateCalls = new ArrayList<>();
    AtomicInteger chunks = new AtomicInteger();
    try {
      if (!Set.of("openai-chat", "anthropic").contains(flags.wire())
          || !Set.of("loop-stream", "loop-call", "model-stream").contains(flags.surface())
          || flags.tools().size() != 1)
        throw new IllegalArgumentException("Unsupported adapter flags");
      var client = dev.langchain4j.http.client.HttpClientBuilderLoader.loadHttpClientBuilder();
      String expected =
          switch (flags.httpClient()) {
            case "jdk" -> "dev.langchain4j.http.client.jdk.JdkHttpClientBuilder";
            case "okhttp" -> "dev.langchain4j.http.client.okhttp.OkHttpClientBuilder";
            default -> throw new IllegalArgumentException("Unsupported HTTP client");
          };
      if (!client.getClass().getName().equals(expected))
        throw new IllegalStateException("HTTP client does not match build variant");
      if (flags.surface().equals("loop-call")) {
        line.setting("max_retries", 0);
        dev.langchain4j.model.chat.ChatModel model =
            flags.wire().equals("anthropic")
                ? dev.langchain4j.model.anthropic.AnthropicChatModel.builder()
                    .httpClientBuilder(client)
                    .baseUrl(flags.baseUrl() + "/v1/")
                    .apiKey("sk-test")
                    .modelName("claude-sonnet-4-5")
                    .maxTokens(1024)
                    .timeout(Duration.ofMillis(flags.timeoutMs()))
                    .maxRetries(0)
                    .build()
                : dev.langchain4j.model.openai.OpenAiChatModel.builder()
                    .httpClientBuilder(client)
                    .baseUrl(flags.baseUrl() + "/v1")
                    .apiKey("sk-test")
                    .modelName("gpt-4o")
                    .timeout(Duration.ofMillis(flags.timeoutMs()))
                    .maxRetries(0)
                    .build();
        var response =
            AiServices.builder(BlockingAssistant.class)
                .chatModel(model)
                .tools(tool(flags, recorder))
                .build()
                .chat(flags.prompt());
        observe(response.finalResponse(), line);
        // Earlier rounds are on the returned Result; kept apart from the final response, as the
        // streaming surface keeps onIntermediateResponse apart.
        for (var r : response.intermediateResponses()) {
          if (r.finishReason() != null) intermediateFinishes.add(r.finishReason().toString());
          for (var c : r.aiMessage().toolExecutionRequests()) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", c.id());
            call.put("name", c.name());
            call.put("arguments_raw", c.arguments());
            intermediateCalls.add(call);
          }
        }
        if (!response.intermediateResponses().isEmpty()) {
          line.callbackOnly("intermediate_finish_reasons", List.copyOf(intermediateFinishes));
          line.callbackOnly("intermediate_tool_requests", List.copyOf(intermediateCalls));
        }
        line.chunks(1);
        signals.complete();
      } else {
        StreamingChatModel model =
            flags.wire().equals("anthropic")
                ? AnthropicStreamingChatModel.builder()
                    .httpClientBuilder(client)
                    .baseUrl(flags.baseUrl() + "/v1/")
                    .apiKey("sk-test")
                    .modelName("claude-sonnet-4-5")
                    .maxTokens(1024)
                    .timeout(Duration.ofMillis(flags.timeoutMs()))
                    .build()
                : OpenAiStreamingChatModel.builder()
                    .httpClientBuilder(client)
                    .baseUrl(flags.baseUrl() + "/v1")
                    .apiKey("sk-test")
                    .modelName("gpt-4o")
                    .timeout(Duration.ofMillis(flags.timeoutMs()))
                    .build();
        if (flags.surface().equals("model-stream"))
          modelStream(model, flags, recorder, line, signals, chunks);
        else {
          Assistant assistant =
              AiServices.builder(Assistant.class)
                  .streamingChatModel(model)
                  .tools(tool(flags, recorder))
                  .build();
          assistant
              .chat(flags.prompt())
              .onPartialResponse(s -> line.chunks(chunks.incrementAndGet()))
              .onIntermediateResponse(
                  r -> {
                    if (r.finishReason() != null)
                      intermediateFinishes.add(r.finishReason().toString());
                    for (var c : r.aiMessage().toolExecutionRequests()) {
                      Map<String, Object> call = new LinkedHashMap<>();
                      call.put("id", c.id());
                      call.put("name", c.name());
                      call.put("arguments_raw", c.arguments());
                      intermediateCalls.add(call);
                    }
                    line.callbackOnly(
                        "intermediate_finish_reasons", List.copyOf(intermediateFinishes));
                    line.callbackOnly("intermediate_tool_requests", List.copyOf(intermediateCalls));
                  })
              .onToolExecuted(t -> {})
              .onCompleteResponse(
                  r -> {
                    line.callerText(r.aiMessage().text());
                    if (r.finishReason() != null) line.callerFinish(r.finishReason().toString());
                    r.aiMessage()
                        .toolExecutionRequests()
                        .forEach(c -> line.callerToolCall(c.id(), c.name(), c.arguments()));
                    signals.complete();
                  })
              .onError(signals::error)
              .start();
        }
      }
    } catch (Throwable error) {
      signals.error(error);
    }
    signals.awaitFirst(60000);
    signals.linger();
    line.emitAndHalt(signals, recorder, start);
  }
}
