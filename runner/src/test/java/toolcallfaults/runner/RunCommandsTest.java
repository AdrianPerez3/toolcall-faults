package toolcallfaults.runner;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.results.Records.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.judge.*;
import toolcallfaults.runner.results.*;

class RunCommandsTest {
  @Test
  void default_parallelism_follows_the_processors_available() {
    // Each cell starts a library JVM under a watchdog; eight at once on a two-core host are
    // killed before their request reaches the server.
    assertEquals(1, RunCommands.defaultParallel(1));
    assertEquals(1, RunCommands.defaultParallel(2));
    assertEquals(2, RunCommands.defaultParallel(4));
    assertEquals(8, RunCommands.defaultParallel(16));
    assertEquals(8, RunCommands.defaultParallel(64));
  }

  @Test
  void ordinary_unstable_run_fails_after_preserving_evidence() throws Exception {
    assertUnstableRunFails(3);
  }

  @Test
  void ten_repetition_unstable_run_fails_without_a_majority_vote() throws Exception {
    assertUnstableRunFails(10);
  }

  private void assertUnstableRunFails(int count) throws Exception {
    var records = new ArrayList<RunRecord>();
    for (int i = 1; i <= count; i++) records.add(row(a, i, "ERROR_TYPED", i == count ? 2 : 1));
    RunCommands.saveResults(root, root.resolve("results"), records);
    var output = new java.io.ByteArrayOutputStream();
    var original = System.out;
    try {
      System.setOut(new java.io.PrintStream(output));
      assertThrows(java.io.IOException.class, () -> RunCommands.verifyRun(records, 0));
    } finally {
      System.setOut(original);
    }
    assertTrue(output.toString().contains("FLAKY " + a));
    assertTrue(output.toString().contains("ERROR_TYPED=" + count));
    assertEquals(count, current().read("target", "1", true).size());
    var stable = new ArrayList<RunRecord>();
    for (int i = 1; i <= count; i++) stable.add(row(a, i, "ERROR_TYPED", 1));
    assertDoesNotThrow(() -> RunCommands.verifyRun(stable, 0));
  }

  @Test
  void cross_jdk_command_rejects_the_same_partial_matrix_in_all_directories() throws Exception {
    var project = Path.of("..");
    var matrix = toolcallfaults.runner.matrix.Matrix.load(project);
    var dirs = new ArrayList<Path>();
    for (int jdk : List.of(17, 21, 25)) {
      var dir = root.resolve("jdk" + jdk);
      dirs.add(dir);
      var cell = matrix.cells("current", jdk).get(0);
      new ResultStore(dir, dir.resolve(".local"))
          .write(cell.target(), cell.version(), rows(cell.key(), 1), Set.of());
    }
    assertThrows(java.io.IOException.class, () -> RunCommands.compareJdks(project, dirs));
  }

  @Test
  void rerun_selects_only_unstable_cells_and_rejects_missing_evidence() {
    var cellA =
        new toolcallfaults.runner.cell.Cell(
            "target",
            "1",
            "module",
            "loop-stream",
            "openai-chat/truncation.name-only",
            "record_note",
            21,
            "jdk");
    var cellB =
        new toolcallfaults.runner.cell.Cell(
            "target",
            "1",
            "module",
            "model-stream",
            "openai-chat/truncation.name-only",
            "record_note",
            21,
            "jdk");
    var records = new ArrayList<>(rows(a, 1));
    records.addAll(rows(b, 1));
    assertEquals(List.of(), RunCommands.unstableCells(List.of(cellA, cellB), records));
    records.set(2, row(a, 3, "ERROR_TYPED", 2));
    assertEquals(List.of(cellA), RunCommands.unstableCells(List.of(cellA, cellB), records));
    assertThrows(
        IllegalArgumentException.class,
        () -> RunCommands.unstableCells(List.of(cellA, cellB), rows(a, 1)));
  }

  @TempDir Path root;
  String a = CELL;
  String b = CELL.replace("loop-stream", "model-stream");

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectories(root.resolve("suite"));
    Files.copy(Path.of("../suite/judgements.json"), root.resolve("suite/judgements.json"));
  }

  List<RunRecord> rows(String cell, int requests) {
    return java.util.stream.IntStream.rangeClosed(1, 3)
        .mapToObj(i -> row(cell, i, "ERROR_TYPED", requests))
        .toList();
  }

  ResultStore current() {
    return new ResultStore(root.resolve("results"), root.resolve(".local/results"));
  }

  ResultStore previous() {
    return new ResultStore(
        root.resolve(".local/previous-results"), root.resolve(".local/previous-withheld"));
  }

  void save(String cell, int requests) throws Exception {
    RunCommands.saveResults(root, root.resolve("results"), rows(cell, requests));
  }

  @Test
  void unrelated_filtered_run_preserves_outstanding_change() throws Exception {
    save(a, 1);
    save(b, 1);
    save(a, 2);
    save(b, 1);
    var findings =
        Check.check(
            current().read("target", "1", true),
            previous().read("target", "1", true),
            Judgements.load(root.resolve("suite/judgements.json")),
            Baseline.load(
                root.resolve("expectations/baseline"),
                root.resolve(".local/baseline"),
                "target",
                "1"));
    assertTrue(findings.stream().anyMatch(f -> f.kind().equals("CHANGED") && f.cell().equals(a)));
  }

  @Test
  void alternate_output_does_not_mutate_default_comparison() throws Exception {
    save(a, 1);
    save(a, 2);
    var before = previous().read("target", "1", true);
    var alternate = root.resolve("alternate");
    RunCommands.saveResults(root, alternate, rows(a, 9));
    RunCommands.saveResults(root, alternate, rows(a, 10));
    assertEquals(before, previous().read("target", "1", true));
  }
}
