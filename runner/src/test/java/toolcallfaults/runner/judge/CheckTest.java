package toolcallfaults.runner.judge;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.results.Records.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.fixture.FixtureLoader;
import toolcallfaults.runner.results.RunRecord;

class CheckTest {
  @TempDir Path root;
  Judgements judgements;

  @BeforeEach
  void setup() throws Exception {
    judgements = Judgements.load(Path.of("../suite/judgements.json"));
  }

  Baseline baseline(String disclosure) throws Exception {
    var json = FixtureLoader.JSON.createObjectNode().put("format", "tcf.baseline/v1");
    var entries = json.putArray("entries");
    if (disclosure != null)
      entries
          .addObject()
          .put("cell", CELL)
          .put("category", "EXECUTED_ALTERED")
          .put(
              "issue",
              disclosure.equals("filed") ? "https://github.com/example/target/issues/1" : null)
          .put("status", disclosure.equals("filed") ? "reported" : null)
          .put("disclosure", disclosure)
          .put("filedOn", disclosure.equals("filed") ? "2026-11-01" : null)
          .put("maintainerAnswered", false)
          .putNull("maintainerPosition");
    Path dir = root.resolve(disclosure != null && !disclosure.equals("filed") ? "local" : "public");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("target@1.json"), json.toString());
    return Baseline.load(root.resolve("public"), root.resolve("local"), "target", "1");
  }

  List<RunRecord> altered() {
    return repetitions("EXECUTED_ALTERED", 1);
  }

  List<RunRecord> repetitions(String category, int requests) {
    return java.util.stream.IntStream.rangeClosed(1, 3)
        .mapToObj(i -> row(CELL, i, category, requests))
        .toList();
  }

  @Test
  void harness_failure_rejects_record_only_model_cells() throws Exception {
    String cell = CELL.replace("loop-stream", "model-stream");
    var failures =
        Check.check(
            List.of(
                row(cell, 1, "HARNESS", 0), row(cell, 2, "HARNESS", 0), row(cell, 3, "HARNESS", 0)),
            List.of(),
            judgements,
            baseline(null));
    assertTrue(failures.stream().anyMatch(f -> f.kind().equals("HARNESS")));
  }

  @Test
  void harness_failure_rejects_record_only_loop_scenarios() throws Exception {
    String cell = CELL.replace("truncation.name-only", "finish.stop-with-tool-call");
    assertTrue(
        Check.check(
                List.of(
                    row(cell, 1, "HARNESS", 0),
                    row(cell, 2, "HARNESS", 0),
                    row(cell, 3, "HARNESS", 0)),
                List.of(),
                judgements,
                baseline(null))
            .stream()
            .anyMatch(f -> f.kind().equals("HARNESS")));
  }

  @Test
  void incomplete_repetitions_fail_even_when_supported() throws Exception {
    for (var rows :
        List.of(
            List.of(row(CELL, 1, "ERROR_TYPED", 1)),
            List.of(row(CELL, 1, "ERROR_TYPED", 1), row(CELL, 2, "ERROR_TYPED", 1)),
            List.of(
                row(CELL, 1, "ERROR_TYPED", 1),
                row(CELL, 1, "ERROR_TYPED", 1),
                row(CELL, 3, "ERROR_TYPED", 1)),
            List.of(
                row(CELL, 1, "ERROR_TYPED", 1),
                row(CELL, 2, "ERROR_TYPED", 1),
                row(CELL, 4, "ERROR_TYPED", 1)))) {
      assertTrue(
          Check.check(rows, List.of(), judgements, baseline(null)).stream()
              .anyMatch(f -> f.kind().equals("INCOMPLETE")));
    }
  }

  @Test
  void complete_diagnostic_repetitions_pass() throws Exception {
    var rows =
        java.util.stream.IntStream.rangeClosed(1, 10)
            .mapToObj(i -> row(CELL, i, "ERROR_TYPED", 1))
            .toList();
    assertTrue(Check.check(rows, List.of(), judgements, baseline(null)).isEmpty());
  }

  @Test
  void model_argument_change_is_reported() throws Exception {
    String cell = CELL.replace("loop-stream", "model-stream");
    var before =
        java.util.stream.IntStream.rangeClosed(1, 3)
            .mapToObj(i -> row(cell, i, "EXECUTED_ALTERED", 1))
            .toList();
    var after =
        java.util.stream.IntStream.rangeClosed(1, 3)
            .mapToObj(i -> row(cell, i, "EXECUTED_ALTERED", 1))
            .toList();
    for (var r : before)
      ((com.fasterxml.jackson.databind.node.ObjectNode) r.adapter())
          .putObject("caller")
          .putArray("tool_calls")
          .addObject()
          .put("arguments_parsed_ok", true)
          .putObject("arguments")
          .put("title", "A");
    for (var r : after)
      ((com.fasterxml.jackson.databind.node.ObjectNode) r.adapter())
          .putObject("caller")
          .putArray("tool_calls")
          .addObject()
          .put("arguments_parsed_ok", true)
          .putObject("arguments")
          .put("title", "B");
    assertEquals(
        List.of("CHANGED"),
        Check.check(after, before, judgements, baseline(null)).stream()
            .map(Finding::kind)
            .toList());
  }

  @Test
  void unsupported_in_baseline_passes() throws Exception {
    assertTrue(Check.check(altered(), List.of(), judgements, baseline("filed")).isEmpty());
  }

  @Test
  void unsupported_not_in_baseline_is_new() throws Exception {
    assertEquals(
        "NEW", Check.check(altered(), List.of(), judgements, baseline(null)).get(0).kind());
  }

  @Test
  void baseline_entry_without_outcome_is_stale() throws Exception {
    assertTrue(
        Check.check(repetitions("ERROR_TYPED", 1), List.of(), judgements, baseline("filed"))
            .stream()
            .anyMatch(f -> f.kind().equals("STALE")));
  }

  @Test
  void supported_outcome_passes() throws Exception {
    assertTrue(
        Check.check(repetitions("ERROR_TYPED", 1), List.of(), judgements, baseline(null))
            .isEmpty());
  }

  @Test
  void changed_observation_reported() throws Exception {
    assertTrue(
        Check.check(altered(), repetitions("EXECUTED_ALTERED", 2), judgements, baseline("filed"))
            .stream()
            .anyMatch(f -> f.kind().equals("CHANGED")));
  }

  @Test
  void flaky_cell_reported() throws Exception {
    assertTrue(
        Check.check(
                List.of(row(CELL, 1, "EXECUTED_ALTERED", 1), row(CELL, 2, "ERROR_UNTYPED", 1)),
                List.of(),
                judgements,
                baseline("filed"))
            .stream()
            .anyMatch(f -> f.kind().equals("FLAKY")));
  }

  @Test
  void withheld_is_unsupported_and_not_filed() throws Exception {
    assertEquals(Set.of(CELL), Check.withheld(altered(), judgements, baseline("unfiled")));
    Files.delete(root.resolve("local/target@1.json"));
    assertTrue(Check.withheld(altered(), judgements, baseline("filed")).isEmpty());
    assertTrue(
        Check.withheld(repetitions("ERROR_TYPED", 1), judgements, baseline("filed")).isEmpty());
  }

  @Test
  void filed_entry_covers_only_the_outcome_it_names() throws Exception {
    // The filed report describes EXECUTED_ALTERED; a later DROPPED is a different, unreported
    // unsupported outcome of the same cell.
    assertEquals(
        Set.of(CELL), Check.withheld(repetitions("DROPPED", 1), judgements, baseline("filed")));
  }

  static final String SIBLING = CELL.replace("loop-stream", "model-stream");

  @Test
  void record_only_sibling_of_a_withheld_cell_is_withheld() throws Exception {
    // Same target, module and scenario: the model-surface row shows the same behaviour.
    var run = new ArrayList<>(altered());
    run.add(row(SIBLING, 1, "SURFACED", 1));
    assertEquals(Set.of(CELL, SIBLING), Check.withheld(run, judgements, baseline(null)));
  }

  @Test
  void unfiled_entry_withholds_its_family_even_when_its_own_cell_did_not_run() throws Exception {
    assertEquals(
        Set.of(SIBLING),
        Check.withheld(List.of(row(SIBLING, 1, "SURFACED", 1)), judgements, baseline("unfiled")));
  }

  @Test
  void other_scenarios_and_modules_are_not_siblings() throws Exception {
    var run = new ArrayList<>(altered());
    String otherScenario = SIBLING.replace("truncation.name-only", "tools.single-call");
    String otherModule = SIBLING.replace("|module|", "|other|");
    run.add(row(otherScenario, 1, "EXECUTED_AS_SENT", 1));
    run.add(row(otherModule, 1, "SURFACED", 1));
    assertEquals(Set.of(CELL), Check.withheld(run, judgements, baseline(null)));
  }

  @Test
  void filed_cell_stays_public_beside_a_withheld_sibling() throws Exception {
    // CELL is filed as EXECUTED_ALTERED; its sibling shows an unfiled unsupported outcome.
    String sibling = CELL.replace("|record_note|", "|set_level|");
    var run = new ArrayList<>(altered());
    run.add(row(sibling, 1, "DROPPED", 1));
    assertEquals(Set.of(sibling), Check.withheld(run, judgements, baseline("filed")));
  }
}
