package toolcallfaults.kit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ResultLineTest {
  @TempDir Path dir;
  private final java.io.PrintStream original = System.out;

  @AfterEach
  void restore() {
    System.setOut(original);
  }

  @Test
  void key_order_matches_spec_example() {
    var r =
        ResultLine.start(Flags.parse(FlagsTest.args(dir.toString())), "test-target", "test-api");
    String line = r.render(new Signals(), new Recorder(dir), 12);
    int previous = -1;
    for (String key :
        List.of(
            "format",
            "target",
            "module",
            "surface",
            "api",
            "wire",
            "tools",
            "versions",
            "classpath_sha256",
            "runtime",
            "settings",
            "terminal_signals",
            "error",
            "caller",
            "tool_invocations",
            "elapsed_ms")) {
      int at = line.indexOf("\"" + key + "\":");
      assertTrue(at > previous, key);
      previous = at;
    }
    assertTrue(Json.isValid(line));
  }

  byte[][] fork() throws Exception {
    Process p =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=US-ASCII",
                "-cp",
                System.getProperty("java.class.path"),
                Probe.class.getName(),
                dir.toString())
            .start();
    byte[] out = p.getInputStream().readAllBytes(), err = p.getErrorStream().readAllBytes();
    assertEquals(0, p.waitFor());
    return new byte[][] {out, err};
  }

  @Test
  void result_line_is_utf8_whatever_the_default_charset() throws Exception {
    String s = new String(fork()[0], StandardCharsets.UTF_8);
    assertTrue(s.contains("café ☕"));
    assertTrue(Json.isValid(s));
  }

  @Test
  void target_stdout_noise_does_not_reach_stdout() throws Exception {
    var streams = fork();
    String out = new String(streams[0], StandardCharsets.UTF_8);
    assertEquals(1, out.lines().count());
    assertFalse(out.contains("noise"));
    assertTrue(new String(streams[1], StandardCharsets.UTF_8).contains("noise"));
  }

  @Test
  void unparseable_tool_call_arguments_are_flagged() {
    var r = ResultLine.start(Flags.parse(FlagsTest.args(dir.toString())), "target", "api");
    r.callerToolCall("c", "record_note", "{\"a\":");
    String s = r.render(new Signals(), new Recorder(dir), 0);
    assertTrue(s.contains("\"arguments_parsed_ok\":false,\"arguments\":null"));
  }

  @Test
  void records_whether_a_tool_ran_before_the_first_error() {
    for (boolean before : List.of(true, false)) {
      var r = ResultLine.start(Flags.parse(FlagsTest.args(dir.toString())), "target", "api");
      var recorder = new Recorder(dir);
      var signals = new Signals(recorder);
      if (before) recorder.invoked("flush_notes", Map.of());
      signals.error(new IllegalStateException());
      if (!before) recorder.invoked("flush_notes", Map.of());
      signals.error(new IllegalStateException());
      String output = r.render(signals, recorder, 0);
      assertTrue(output.contains("\"tools_ran_before_error\":" + before));
    }
  }

  private static final String PRETTY =
      "{\n"
          + " \"priority\": 3,\n"
          + " \"title\": \"a  b\", \"body\": \"quoted \\\"text\\\" and \\\\ path\"\n"
          + "}";

  @Test
  void pretty_tool_arguments_emit_one_line_without_changing_string_content() throws Exception {
    String output = new String(fork()[0], StandardCharsets.UTF_8);
    assertEquals(1, output.lines().count());
    assertTrue(Json.isValid(output));
    assertTrue(output.contains("\"arguments_raw\":" + Json.write(PRETTY)));
    assertTrue(
        output.contains(
            "\"arguments\":{\"priority\":3,\"title\":\"a  b\",\"body\":\"quoted \\\"text\\\" and"
                + " \\\\ path\"}"));
  }

  public static class Probe {
    public static void main(String[] args) {
      var f = Flags.parse(FlagsTest.args(args[0]));
      var r = ResultLine.start(f, "target", "api");
      System.out.println("noise");
      r.callerText("café ☕");
      r.callerToolCall("call_1", "record_note", PRETTY);
      var s = new Signals();
      s.complete();
      r.emitAndHalt(s, new Recorder(f.workdir()), System.currentTimeMillis());
    }
  }
}
