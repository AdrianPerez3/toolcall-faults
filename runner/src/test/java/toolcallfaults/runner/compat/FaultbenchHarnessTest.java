package toolcallfaults.runner.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.fixture.FixtureLoader;

class FaultbenchHarnessTest {
  @TempDir Path work;

  @Test
  void both_loop_surfaces_are_accepted() {
    for (String surface : List.of("loop-stream", "loop-call"))
      for (String mode : List.of("complete", "length", "drop", "reset"))
        assertDoesNotThrow(() -> FaultbenchHarness.validateCell(surface, mode));
    assertThrows(
        IllegalArgumentException.class,
        () -> FaultbenchHarness.validateCell("model-stream", "complete"));
    assertThrows(
        IllegalArgumentException.class, () -> FaultbenchHarness.validateCell("loop-call", "other"));
  }

  @Test
  void disk_state_of_complete_file() throws Exception {
    StringBuilder content = new StringBuilder();
    for (int i = 1; i <= 40; i++)
      content.append(String.format("line %02d: the quick brown fox jumps over the lazy dog\n", i));
    Files.writeString(work.resolve("report.txt"), content);
    var disk = FaultbenchHarness.diskState(work);
    assertTrue(disk.path("file_written").asBoolean());
    assertEquals(40, disk.path("lines").asInt());
    assertEquals(2120, disk.path("bytes").asInt());
    assertTrue(disk.path("complete").asBoolean());
    assertTrue(disk.path("last_line").asText().startsWith("line 40"));
  }

  @Test
  void disk_state_when_no_file() throws Exception {
    assertEquals(
        FixtureLoader.JSON.readTree("{\"file_written\":false}"), FaultbenchHarness.diskState(work));
  }

  @Test
  void record_has_faultbench_fields() throws Exception {
    var record =
        FaultbenchHarness.record(
            "id",
            "spring-ai",
            "loop-stream",
            "length",
            2,
            work,
            FixtureLoader.JSON.createObjectNode(),
            List.of(),
            List.of(),
            1.25,
            null);
    for (String key :
        List.of(
            "run_id",
            "framework",
            "variant",
            "streaming",
            "run",
            "requests",
            "request_paths",
            "tool_calls",
            "tool_called",
            "disk",
            "answer",
            "error",
            "duration_s",
            "timestamp",
            "java")) assertTrue(record.has(key), key);
    assertEquals("chat-length", record.path("variant").asText());
    assertEquals(2, record.path("run").asInt());
    assertEquals("spring-ai", record.path("framework").asText());
  }

  @Test
  void write_file_stays_in_workdir() throws Exception {
    assertEquals(
        "Wrote 5 characters to report.txt",
        FaultbenchHarness.safeWrite(work, "../../x/report.txt", "hello"));
    assertEquals("hello", Files.readString(work.resolve("report.txt")));
  }

  @Test
  void watched_versions_with_the_same_artifact_name_are_preserved() throws Exception {
    var result = FixtureLoader.JSON.createObjectNode();
    result
        .putObject("versions")
        .put("tools.jackson.core:jackson-databind", "3.1.7")
        .put("com.fasterxml.jackson.core:jackson-databind", "2.21.7");
    var row =
        FaultbenchHarness.record(
            "id",
            "spring-ai",
            "loop-stream",
            "complete",
            1,
            work,
            result,
            List.of(),
            List.of(),
            1.0,
            null);
    assertEquals("3.1.7", row.path("version_tools.jackson.core:jackson-databind").asText());
    assertEquals(
        "2.21.7", row.path("version_com.fasterxml.jackson.core:jackson-databind").asText());
  }
}
