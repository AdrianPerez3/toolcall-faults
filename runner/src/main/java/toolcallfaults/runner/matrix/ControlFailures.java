package toolcallfaults.runner.matrix;

import java.util.*;
import toolcallfaults.runner.cell.*;

/** Retains controls and explains cells whose outcome cannot be interpreted. */
public final class ControlFailures {
  private ControlFailures() {}

  public record NotRun(Cell cell, List<String> basis, List<String> notes) {}

  public record Selection(List<Cell> run, List<NotRun> notRun) {}

  public static Selection select(List<Cell> candidates, Map<String, AdapterManifest> manifests) {
    List<Cell> run = new ArrayList<>();
    List<NotRun> notRun = new ArrayList<>();
    for (Cell cell : candidates) {
      String scenario = cell.fixtureId().split("/", 2)[1].split("@", 2)[0];
      var failures =
          manifests.get(cell.target()).controlFailures().stream()
              .filter(
                  f ->
                      f.appliesToVersion(cell.version())
                          && f.module().equals(cell.module())
                          && f.surface().equals(cell.surface()))
              .toList();
      if (AdapterManifest.isControl(scenario) || failures.isEmpty()) run.add(cell);
      else
        notRun.add(
            new NotRun(
                cell,
                failures.stream().map(AdapterManifest.ControlFailure::basis).distinct().toList(),
                failures.stream()
                    .map(AdapterManifest.ControlFailure::notes)
                    .filter(n -> !n.isBlank())
                    .distinct()
                    .toList()));
    }
    return new Selection(List.copyOf(run), List.copyOf(notRun));
  }
}
