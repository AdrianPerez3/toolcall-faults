package toolcallfaults.runner.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import toolcallfaults.kit.*;
import toolcallfaults.runner.cell.*;
import toolcallfaults.runner.fixture.FixtureLoader;

public final class FaultbenchHarness {
  private FaultbenchHarness() {}

  public static String safeWrite(Path work, String path, String content) {
    return FileTool.write(work, path, content);
  }

  public static ObjectNode diskState(Path work) throws Exception {
    ObjectNode out = FixtureLoader.JSON.createObjectNode();
    Path file = work.resolve("report.txt");
    out.put("file_written", Files.exists(file));
    if (!Files.exists(file)) return out;
    byte[] bytes = Files.readAllBytes(file);
    String content =
        StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    long lines = content.chars().filter(c -> c == '\n').count();
    String stripped = content.replaceFirst("\n+$", "");
    String last = stripped.isBlank() ? "" : stripped.substring(stripped.lastIndexOf('\n') + 1);
    out.put("lines", lines)
        .put("bytes", bytes.length)
        .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .put("last_line", last)
        .put("complete", lines == 40 && content.endsWith("\n") && last.startsWith("line 40"));
    return out;
  }

  public static ObjectNode record(
      String runId,
      String target,
      String surface,
      String mode,
      int run,
      Path work,
      JsonNode result,
      List<JsonNode> invocations,
      List<JsonNode> requests,
      double duration,
      String processError)
      throws Exception {
    ObjectNode out = FixtureLoader.JSON.createObjectNode();
    out.put("run_id", runId)
        .put("framework", target)
        .put("variant", "chat-" + mode)
        .put("streaming", surface.endsWith("-stream"))
        .put("run", run);
    result
        .path("versions")
        .fields()
        .forEachRemaining(e -> out.put("version_" + e.getKey(), e.getValue().asText()));
    out.put("requests", requests.size());
    var paths = out.putArray("request_paths");
    for (JsonNode request : requests) {
      if (!request.path("endpoint").asText().equals("chat"))
        throw new IOException("Unexpected compatibility endpoint");
      paths.add("/" + request.path("mode").asText() + "/v1/chat/completions");
    }
    var calls = out.putArray("tool_calls");
    for (JsonNode invocation : invocations) {
      if (!invocation.path("name").asText().equals("write_file"))
        throw new IOException("Unexpected compatibility tool");
      String content = invocation.path("args").path("content").asText("");
      calls
          .addObject()
          .put("content_chars", content.codePointCount(0, content.length()))
          .put("bytes", content.getBytes(StandardCharsets.UTF_8).length)
          .put("newline_lines", content.chars().filter(c -> c == '\n').count());
    }
    out.put("tool_called", !invocations.isEmpty());
    out.set("disk", diskState(work));
    out.put("answer", result.path("caller").path("text").asText(""));
    String error = processError;
    if (error == null && !result.path("error").isMissingNode() && !result.path("error").isNull())
      error = result.path("error").toString();
    out.put("error", error);
    out.put("duration_s", duration)
        .put("timestamp", Instant.now().toString())
        .put(
            "java", result.path("runtime").path("java").asText(System.getProperty("java.version")));
    return out;
  }

  private static List<JsonNode> rows(Path file) throws IOException {
    if (!Files.exists(file)) return List.of();
    byte[] bytes = Files.readAllBytes(file);
    if (bytes.length > 0 && bytes[bytes.length - 1] != '\n')
      throw new IOException("Torn JSONL: " + file);
    List<JsonNode> out = new ArrayList<>();
    for (String row : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      JsonNode n = FixtureLoader.JSON.readTree(row);
      if (n == null || !n.isObject()) throw new IOException("Invalid JSONL: " + file);
      out.add(n);
    }
    return out;
  }

  static void validateCell(String surface, String mode) {
    if (!Set.of("loop-stream", "loop-call").contains(surface)
        || !Set.of("complete", "length", "drop", "reset").contains(mode))
      throw new IllegalArgumentException("Unsupported compatibility cell");
  }

  public static void main(String[] args) throws Exception {
    if (!Files.exists(Path.of("/.dockerenv")))
      throw new IOException("Target execution requires ./tcf --container");
    if (args.length != 5)
      throw new IllegalArgumentException("Expected target@version surface mode run base-url");
    String[] target = args[0].split("@", -1);
    if (target.length != 2) throw new IllegalArgumentException("Expected target@version");
    String surface = args[1], mode = args[2];
    validateCell(surface, mode);
    Path root = Path.of(".").toAbsolutePath().normalize();
    BuiltAdapter adapter = AdapterBuild.ensure(root, target[0], target[1], "");
    AdapterManifest manifest = AdapterManifest.load(root.resolve("adapters").resolve(target[0]));
    var module = manifest.modules().entrySet().iterator().next();
    String runId = System.getenv("BENCH_RUN_ID");
    Path
        resultFile = Path.of(Objects.requireNonNull(System.getenv("BENCH_RESULT"), "BENCH_RESULT")),
        log = Path.of(Objects.requireNonNull(System.getenv("BENCH_LOG"), "BENCH_LOG"));
    Path work = Files.createTempDirectory(resultFile.toAbsolutePath().getParent(), "cell-");
    List<String> command = toolcallfaults.runner.cell.CellRunner.javaLauncher();
    command.addAll(
        List.of(
            "-cp",
            String.join(File.pathSeparator, adapter.classpath()),
            adapter.mainClass(),
            "--wire",
            "openai-chat",
            "--module",
            module.getKey(),
            "--surface",
            surface,
            "--tools",
            "write_file",
            "--base-url",
            args[4],
            "--timeout-ms",
            "3000",
            "--workdir",
            work.toString(),
            "--http-client",
            module.getValue().httpClient(),
            "--prompt",
            "Write the 40-line report to report.txt."));
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(work.toFile())
            .redirectOutput(work.resolve("stdout.jsonl").toFile())
            .redirectError(work.resolve("stderr.log").toFile());
    builder.environment().clear();
    long start = System.nanoTime();
    Process process = builder.start();
    boolean killed;
    try {
      killed = !process.waitFor(12000, TimeUnit.MILLISECONDS);
      if (killed) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor();
      }
    } catch (InterruptedException e) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw e;
    }
    var output = rows(work.resolve("stdout.jsonl"));
    JsonNode result = output.size() == 1 ? output.get(0) : FixtureLoader.JSON.createObjectNode();
    String error =
        killed
            ? "watchdog after 12000 ms"
            : process.exitValue() != 0 ? "adapter exited " + process.exitValue() : null;
    if (error == null && !result.path("format").asText().equals("tcf.result/v1"))
      throw new IOException("Missing adapter result");
    List<JsonNode> requests = rows(log);
    if (requests.isEmpty()) throw new IOException("No request reached neighbour server");
    if (requests.size() > 8)
      throw new IOException(
          "Private-reporting review required: compatibility request count exceeded eight; evidence"
              + " is local");
    ObjectNode row =
        record(
            runId,
            target[0],
            surface,
            mode,
            Integer.parseInt(args[3]),
            work,
            result,
            rows(work.resolve(Recorder.FILE)),
            requests,
            (System.nanoTime() - start) / 1_000_000_000.0,
            error);
    Files.writeString(
        resultFile,
        FixtureLoader.JSON.writeValueAsString(row) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    System.out.println(row);
  }
}
