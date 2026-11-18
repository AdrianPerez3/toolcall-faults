package toolcallfaults.runner.cell;

import java.util.List;
import java.util.StringJoiner;
import toolcallfaults.runner.fixture.Fixture;
import toolcallfaults.runner.oracle.Classified;

public final class CellPrinter {
  private CellPrinter() {}

  public static List<String> render(Fixture fixture, CellResult result, Classified classified) {
    var line = result.resultLine();
    StringJoiner versions = new StringJoiner(" ");
    if (line != null) {
      line.path("versions").fields().forEachRemaining(
          entry -> versions.add(entry.getKey() + "=" + entry.getValue().asText()));
    }

    StringBuilder invocations = new StringBuilder(Integer.toString(result.invocations().size()));
    for (var invocation : result.invocations()) {
      invocations.append("  ")
          .append(invocation.path("name").asText())
          .append(' ')
          .append(invocation.path("args"));
    }

    return List.of(
        row("target:", result.cell().key()),
        row("versions:", versions.toString()),
        row("fixture:", fixture.file().toString()),
        row("invocations:", invocations.toString()),
        row("caller:", line == null ? "null" : line.path("caller").toString()),
        row("requests:", Integer.toString(result.requests().size())),
        row("category:", classified.category().name()));
  }

  private static String row(String label, String value) {
    return String.format("%-15s%s", label, value);
  }
}
