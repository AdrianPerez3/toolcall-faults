package toolcallfaults.runner.cell;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmLoggingTest {
  @TempDir Path dir;

  @Test
  void native_startup_warning_is_kept_off_the_result_channel() throws Exception {
    var command = CellRunner.javaLauncher();
    // Valid individual tags with no matching tag set generate a native warning before main.
    command.addAll(List.of("-Xlog:gc+class=warning", "-version"));
    Path out = dir.resolve("stdout"), err = dir.resolve("stderr");
    var process =
        new ProcessBuilder(command)
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start();
    assertEquals(0, process.waitFor());
    assertEquals("", Files.readString(out));
    assertTrue(Files.readString(err).contains("No tag set matches selection"));
  }
}
