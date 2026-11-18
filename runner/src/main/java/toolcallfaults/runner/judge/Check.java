package toolcallfaults.runner.judge;

import java.util.*;
import toolcallfaults.runner.oracle.Category;
import toolcallfaults.runner.results.*;

public final class Check {
  private Check() {}

  private static Map<String, List<RunRecord>> groups(List<RunRecord> rows) {
    Map<String, List<RunRecord>> groups = new TreeMap<>();
    for (var row : rows) groups.computeIfAbsent(row.cell(), k -> new ArrayList<>()).add(row);
    return groups;
  }

  public static List<Finding> check(
      List<RunRecord> run, List<RunRecord> stored, Judgements j, Baseline b) {
    List<Finding> findings = new ArrayList<>();
    var previous = groups(stored);
    for (var group : groups(run).entrySet()) {
      String cell = group.getKey();
      var repetitions = group.getValue();
      var repIds = new TreeSet<Integer>();
      repetitions.forEach(r -> repIds.add(r.rep()));
      if (!Stability.complete(repetitions))
        findings.add(
            new Finding(
                "INCOMPLETE", cell, "expected repetitions 1–3 or 1–10; observed " + repIds));
      if (repetitions.stream().anyMatch(r -> r.category().equals("HARNESS")))
        findings.add(new Finding("HARNESS", cell, "invalid harness observation"));
      var status = Stability.of(repetitions);
      if (!status.stable())
        findings.add(new Finding("FLAKY", cell, status.distribution().toString()));
      Set<String> unsupported = new TreeSet<>();
      for (var row : repetitions)
        if (j.judge(row.fixtureId(), row.surface(), Category.valueOf(row.category()), row.attributes())
            .unsupported()) unsupported.add(row.category());
      var baseline = b.entry(cell);
      if (baseline.isPresent() && !unsupported.remove(baseline.get().category()))
        findings.add(
            new Finding(
                "STALE", cell, "the baseline outcome " + baseline.get().category()
                    + " no longer meets its unsupported condition"));
      if (!unsupported.isEmpty())
        findings.add(new Finding("NEW", cell, "unsupported outcome lacks a baseline entry"));
      var old = previous.get(cell);
      if (old != null) {
        Set<Object> before = new HashSet<>(), after = new HashSet<>();
        old.forEach(r -> before.add(Stability.signature(r)));
        repetitions.forEach(r -> after.add(Stability.signature(r)));
        if (!before.equals(after))
          findings.add(
              new Finding(
                  "CHANGED",
                  cell,
                  "category, arguments, first error class or request count changed"));
      }
    }
    return List.copyOf(findings);
  }

  /** Cells that share target, version, module and scenario show one behaviour. */
  public static String family(String cell) {
    String[] parts = cell.split("\\|");
    return parts[0] + "|" + parts[1] + "|" + parts[3].split("/", 2)[1].split("@", 2)[0];
  }

  /**
   * Cells whose rows stay out of tracked results: an unsupported outcome without a filed entry
   * naming it, and every other cell of the same family that has no filed entry of its own. A
   * record-only row of the family would otherwise show the unreported behaviour.
   */
  public static Set<String> withheld(List<RunRecord> run, Judgements j, Baseline b) {
    Set<String> withheld = new TreeSet<>(), families = new HashSet<>();
    for (var r : run)
      if (j.judge(r.fixtureId(), r.surface(), Category.valueOf(r.category()), r.attributes())
              .unsupported()
          // A filed report covers the outcome it names, not any later outcome of the cell.
          && b.entry(r.cell())
              .filter(e -> "filed".equals(e.disclosure()) && r.category().equals(e.category()))
              .isEmpty()) {
        withheld.add(r.cell());
        families.add(family(r.cell()));
      }
    for (Entry e : b.entries()) if (!"filed".equals(e.disclosure())) families.add(family(e.cell()));
    for (var r : run)
      if (families.contains(family(r.cell()))
          && b.entry(r.cell()).filter(e -> "filed".equals(e.disclosure())).isEmpty())
        withheld.add(r.cell());
    return Collections.unmodifiableSet(withheld);
  }
}
