package toolcallfaults.runner.cell;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import toolcallfaults.kit.Recorder;
import toolcallfaults.kit.Signals;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.server.*;

public final class CellRunner {
  public static List<String> javaLauncher() {
    return new ArrayList<>(
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xlog:all=off:stdout",
            "-Xlog:all=warning:stderr"));
  }

  private final ReplayServer server;
  private final long timeoutMs, watchdogMs;

  public CellRunner(ReplayServer server, long timeoutMs, long watchdogMs) {
    this.server = server;
    this.timeoutMs = timeoutMs;
    this.watchdogMs = watchdogMs;
  }

  public CellResult run(Cell cell, Fixture fixture, BuiltAdapter adapter, int rep)
      throws IOException {
    if (!adapter.target().equals("stub") && !Files.exists(Path.of("/.dockerenv")))
      throw new IOException("Target execution requires ./tcf --container");
    String id = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    server.register(id, FixtureLoader.toScript(fixture));
    Path parent = Path.of(".local/cells");
    Files.createDirectories(parent);
    Path work = Files.createTempDirectory(parent, id + "-").toAbsolutePath();
    String cp =
        String.join(
            File.pathSeparator,
            adapter.classpath().stream().map(p -> Path.of(p).toAbsolutePath().toString()).toList());
    List<String> command = javaLauncher();
    command.addAll(List.of("-cp", cp, adapter.mainClass()));
    command.addAll(
        List.of(
            "--wire",
            fixture.wire(),
            "--module",
            cell.module(),
            "--surface",
            cell.surface(),
            "--tools",
            cell.tool(),
            "--base-url",
            "http://127.0.0.1:" + server.port() + "/c/" + id,
            "--timeout-ms",
            Long.toString(timeoutMs),
            "--workdir",
            work.toString(),
            "--http-client",
            cell.httpClient(),
            "--prompt",
            fixture.prompt()));
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(work.toFile())
            .redirectOutput(work.resolve("stdout.jsonl").toFile())
            .redirectError(work.resolve("stderr.log").toFile());
    builder.environment().clear();
    long started = System.nanoTime();
    Process process = builder.start();
    Long watchdogAtMs = null;
    try {
      if (!process.waitFor(watchdogMs, TimeUnit.MILLISECONDS)) {
        watchdogAtMs = System.currentTimeMillis();
        terminate(process);
      }
    } catch (InterruptedException e) {
      terminate(process);
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted cell", e);
    }
    long elapsed = (System.nanoTime() - started) / 1_000_000;
    JsonNode line = null;
    try {
      List<String> lines = Files.readAllLines(work.resolve("stdout.jsonl"), StandardCharsets.UTF_8);
      if (lines.size() == 1) {
        JsonNode parsed = FixtureLoader.JSON.readTree(lines.get(0));
        if (parsed != null
            && parsed.path("format").asText().equals("tcf.result/v1")
            && parsed.path("terminal_signals").isArray()
            && parsed.path("caller").isObject()) line = parsed;
      }
    } catch (IOException ignored) {
    }
    List<JsonNode> invocations = new ArrayList<>();
    boolean torn = false;
    Path recorder = work.resolve(Recorder.FILE);
    if (Files.exists(recorder)) {
      byte[] bytes = Files.readAllBytes(recorder);
      torn = bytes.length > 0 && bytes[bytes.length - 1] != '\n';
      String content;
      try {
        content =
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      } catch (java.nio.charset.CharacterCodingException e) {
        content = "";
        torn = true;
      }
      for (String row : content.lines().toList())
        try {
          JsonNode parsed = FixtureLoader.JSON.readTree(row);
          if (parsed == null
              || !parsed.isObject()
              || !parsed.path("name").isTextual()
              || !parsed.path("args").isObject()) torn = true;
          else invocations.add(parsed);
        } catch (IOException e) {
          torn = true;
        }
    }
    List<RequestRecord> requests = server.log(id);
    String watchdogProblem = null;
    if (watchdogAtMs != null) {
      if (Files.exists(work.resolve(Signals.FIRST_SIGNAL_FILE)))
        watchdogProblem = "adapter received a terminal signal before watchdog";
      else if (!requests.isEmpty()
          && watchdogAtMs - requests.get(requests.size() - 1).receivedAtMs() < timeoutMs)
        watchdogProblem = "last request reached server less than one client timeout before watchdog";
    }
    return new CellResult(
        cell,
        rep,
        line,
        List.copyOf(invocations),
        torn,
        requests,
        server.flagged(id),
        watchdogAtMs != null,
        process.exitValue(),
        elapsed,
        watchdogProblem);
  }

  private static void terminate(Process process) {
    List<ProcessHandle> children = process.descendants().toList();
    children.forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor();
      for (ProcessHandle child : children) child.onExit().get(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
    }
  }
}
