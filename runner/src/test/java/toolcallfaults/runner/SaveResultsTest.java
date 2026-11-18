package toolcallfaults.runner;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.results.Records.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.results.*;

class SaveResultsTest {
  @TempDir Path root;

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectories(root.resolve("suite"));
    Files.copy(Path.of("../suite/judgements.json"), root.resolve("suite/judgements.json"));
  }

  List<RunRecord> rows(String cell, String category) {
    return java.util.stream.IntStream.rangeClosed(1, 3).mapToObj(i -> row(cell, i, category, 1)).toList();
  }

  String tracked() throws Exception {
    Path p = root.resolve("results/target/1.jsonl");
    return Files.exists(p) ? Files.readString(p) : "";
  }

  @Test
  void unsupported_unfiled_rows_never_reach_tracked_results() throws Exception {
    RunCommands.saveResults(root, root.resolve("results"), rows(CELL, "EXECUTED_ALTERED"));
    assertFalse(tracked().contains(CELL));
    assertTrue(Files.readString(root.resolve(".local/results/target/1.jsonl")).contains(CELL));
  }

  @Test
  void harness_rows_never_reach_tracked_results() throws Exception {
    String cell = CELL.replace("loop-stream", "model-stream");
    RunCommands.saveResults(root, root.resolve("results"), rows(cell, "HARNESS"));
    assertFalse(tracked().contains(cell));
    assertTrue(Files.readString(root.resolve(".local/results/target/1.jsonl")).contains(cell));
  }

  @Test
  void harness_or_unexpected_control_fails_run_verification() {
    assertThrows(java.io.IOException.class, () -> RunCommands.verifyRun(rows(CELL, "HARNESS"), 0));
    assertThrows(java.io.IOException.class, () -> RunCommands.verifyRun(rows(CELL, "ERROR_TYPED"), 1));
  }

  @Test
  void published_selection_keeps_only_cells_with_tracked_rows_on_any_jdk() throws Exception {
    // CELL is target@1|module|loop-stream|openai-chat/truncation.name-only|record_note|jdk21|jdk
    String other = CELL.replace("loop-stream", "model-stream");
    new ResultStore(root.resolve("results"), root.resolve(".local/results"))
        .write("target", "1", rows(other, "SURFACED"), Set.of());
    var withRows =
        new toolcallfaults.runner.cell.Cell(
            "target", "1", "module", "model-stream", "openai-chat/truncation.name-only",
            "record_note", 17, "jdk");
    var withoutRows =
        new toolcallfaults.runner.cell.Cell(
            "target", "1", "module", "loop-stream", "openai-chat/truncation.name-only",
            "record_note", 17, "jdk");
    assertEquals(
        List.of(withRows),
        RunCommands.published(List.of(withRows, withoutRows), root.resolve("results")));
    assertEquals(
        List.of(), RunCommands.published(List.of(withoutRows), root.resolve("no-such-directory")));
  }
}
