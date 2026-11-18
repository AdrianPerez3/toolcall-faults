package toolcallfaults.runner.results;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.results.Records.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class StabilityTest {
  private List<RunRecord> onJdk(int jdk) {
    return java.util.stream.IntStream.rangeClosed(1, 3)
        .mapToObj(i -> row(CELL.replace("jdk21", "jdk" + jdk), i, "ERROR_TYPED", 1))
        .toList();
  }

  @Test
  void identical_cells_across_jdks_yield_empty_list() {
    assertEquals(
        List.of(),
        Stability.differingAcrossJdks(Map.of(17, onJdk(17), 21, onJdk(21), 25, onJdk(25))));
  }

  @Test
  void differing_exception_class_is_reported() {
    var rows = onJdk(21);
    rows.forEach(
        r ->
            ((com.fasterxml.jackson.databind.node.ObjectNode)
                    r.adapter().path("error").path("chain").path(0))
                .put("type", "java.io.IOException"));
    assertEquals(
        List.of(CELL.replace("|jdk21", "")),
        Stability.differingAcrossJdks(Map.of(17, onJdk(17), 21, rows)));
  }

  @Test
  void cross_jdk_comparison_rejects_missing_incomplete_or_flaky_evidence() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Stability.differingAcrossJdks(Map.of(17, onJdk(17), 21, List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () -> Stability.differingAcrossJdks(Map.of(17, onJdk(17), 21, onJdk(21).subList(0, 2))));
    var rows = new ArrayList<>(onJdk(21));
    rows.set(2, row(CELL, 3, "HANG", 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> Stability.differingAcrossJdks(Map.of(17, onJdk(17), 21, rows)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Stability.differingAcrossJdks(Map.of(17, onJdk(21), 21, onJdk(21))));
  }

  @Test
  void flaky_percentage_counts_cells_not_runs() {
    assertEquals(1.5, Stability.flakyPercent(3, 200));
    assertEquals(0.0, Stability.flakyPercent(0, 0));
    assertThrows(IllegalArgumentException.class, () -> Stability.flakyPercent(2, 1));
  }

  private RunRecord model(String value, boolean parsed) {
    var r = row(CELL.replace("loop-stream", "model-stream"), 1, "EXECUTED_ALTERED", 1);
    ((com.fasterxml.jackson.databind.node.ArrayNode) r.invocations()).removeAll();
    var call =
        ((com.fasterxml.jackson.databind.node.ObjectNode) r.adapter())
            .putObject("caller")
            .putArray("tool_calls")
            .addObject();
    call.put("arguments_parsed_ok", parsed).put("arguments_raw", value);
    if (parsed) call.putObject("arguments").put("title", value);
    return r;
  }

  @Test
  void equal_model_arguments_are_stable() {
    assertTrue(
        Stability.of(List.of(model("A", true), model("A", true), model("A", true))).stable());
  }

  @Test
  void model_argument_changes_are_unstable() {
    assertFalse(
        Stability.of(List.of(model("A", true), model("B", true), model("A", true))).stable());
  }

  @Test
  void model_unparsed_argument_changes_are_unstable() {
    assertFalse(Stability.of(List.of(model("{a", false), model("{b", false))).stable());
  }

  @Test
  void stable_when_four_fields_agree() {
    assertTrue(
        Stability.of(
                List.of(
                    row(CELL, 1, "EXECUTED_ALTERED", 1),
                    row(CELL, 2, "EXECUTED_ALTERED", 1),
                    row(CELL, 3, "EXECUTED_ALTERED", 1)))
            .stable());
  }

  @Test
  void unstable_reports_distribution() {
    var status =
        Stability.of(
            List.of(
                row(CELL, 1, "EXECUTED_ALTERED", 1),
                row(CELL, 2, "EXECUTED_ALTERED", 1),
                row(CELL, 3, "ERROR_UNTYPED", 1)));
    assertFalse(status.stable());
    assertEquals(Map.of("EXECUTED_ALTERED", 2, "ERROR_UNTYPED", 1), status.distribution());
  }

  @Test
  void same_category_with_different_requests_is_unstable() {
    assertFalse(
        Stability.of(
                List.of(row(CELL, 1, "EXECUTED_ALTERED", 1), row(CELL, 2, "EXECUTED_ALTERED", 2)))
            .stable());
  }
}
