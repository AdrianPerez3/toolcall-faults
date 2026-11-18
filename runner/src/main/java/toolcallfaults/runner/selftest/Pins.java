package toolcallfaults.runner.selftest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import toolcallfaults.runner.cell.*;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.runner.matrix.Matrix;
import toolcallfaults.runner.oracle.*;
import toolcallfaults.server.ReplayServer;

public final class Pins {
  private Pins() {}

  public record Observation(String category, JsonNode arguments, JsonNode signals) {}

  public static boolean differs(List<Observation> control, List<Observation> current) {
    for (var rows : List.of(control, current))
      if (rows.size() != 3 || new HashSet<>(rows).size() != 1)
        throw new IllegalStateException("Pin needs three matching repetitions on each version");
    return !control.get(0).equals(current.get(0));
  }

  public static void run(Path root) throws Exception {
    var matrix = Matrix.load(root);
    int jdk = Runtime.version().feature();
    var controls = matrix.cells("control", jdk);
    if (controls.isEmpty()) throw new IOException("no control versions in matrix.json");
    if (!Files.exists(Path.of("/.dockerenv")))
      throw new IOException("Use ./tcf --container " + jdk + " selftest --pins");
    Map<String, Cell> current = new HashMap<>();
    for (var cell : matrix.cells("current", jdk)) current.put(identity(cell), cell);
    Map<String, BuiltAdapter> builds = new HashMap<>();
    List<Cell> selected = new ArrayList<>(controls);
    for (var cell : controls) {
      var match = current.get(identity(cell));
      if (match == null) throw new IOException("No current counterpart for " + cell.key());
      selected.add(match);
    }
    for (var cell : selected) {
      String key = AdapterBuild.key(cell);
      if (!builds.containsKey(key))
        builds.put(
            key,
            AdapterBuild.ensure(root, cell.target(), cell.version(), AdapterBuild.variant(cell)));
    }
    ExecutorService pool =
        Executors.newFixedThreadPool(
            toolcallfaults.runner.RunCommands.defaultParallel(
                Runtime.getRuntime().availableProcessors()));
    Map<String, Future<List<Observation>>> pending = new HashMap<>();
    Map<String, List<Observation>> rows = new HashMap<>();
    try {
      for (var cell : selected)
        pending.put(
            cell.key(),
            pool.submit(() -> observe(root, matrix, cell, builds.get(AdapterBuild.key(cell)))));
      for (var entry : pending.entrySet()) rows.put(entry.getKey(), entry.getValue().get());
    } finally {
      pending.values().forEach(f -> f.cancel(true));
      pool.shutdownNow();
    }
    Map<String, Boolean> differences = new TreeMap<>();
    for (var cell : controls) {
      var match = current.get(identity(cell));
      boolean different = differs(rows.get(cell.key()), rows.get(match.key()));
      String scenario = cell.fixtureId().split("/", 2)[1].split("@", 2)[0];
      if (!AdapterManifest.isControl(scenario)) {
        String label =
            "pin "
                + cell.target()
                + " "
                + cell.version()
                + " vs "
                + match.version()
                + " on "
                + scenario;
        differences.merge(label, different, (a, b) -> a || b);
      }
    }
    differences.forEach(
        (label, different) -> System.out.println(label + ": " + (different ? "DIFFERS" : "SAME")));
    long passed = differences.values().stream().filter(Boolean::booleanValue).count();
    System.out.println("pins: " + passed + " of " + differences.size() + " DIFFERS");
    if (differences.isEmpty() || passed != differences.size())
      throw new IOException("Pin did not distinguish versions");
  }

  private static String identity(Cell c) {
    return c.target() + c.key().substring(c.key().indexOf('|'));
  }

  private static List<Observation> observe(
      Path root, Matrix matrix, Cell cell, BuiltAdapter adapter) throws Exception {
    var fixture = ControlFixtures.load(root, cell.fixtureId());
    var manifest = matrix.manifests().get(cell.target());
    List<Observation> observations = new ArrayList<>();
    for (int rep = 1; rep <= 3; rep++)
      try (var server = ReplayServer.start(0)) {
        var result =
            new CellRunner(
                    server,
                    matrix.config().path("timeouts").path("t_ms").asLong(),
                    matrix.config().path("timeouts").path("w_ms").asLong())
                .run(cell, fixture, adapter, rep);
        if (result.flagged())
          throw new IOException(
              "Private-reporting review required: pin request cap reached; evidence is local");
        var classified = Oracle.classify(fixture, result, adapter.typedErrorPrefixes());
        if (classified.category() == Category.HARNESS)
          throw new IOException("Pin HARNESS: " + cell.key() + " " + classified.reason());
        var args =
            toolcallfaults.runner.results.Stability.arguments(
                cell.surface(),
                result.resultLine(),
                FixtureLoader.JSON.valueToTree(result.invocations()));
        var observation =
            new Observation(
                classified.category().name(),
                args,
                classified.attributes().path("terminal_signals"));
        observations.add(observation);
        Path file = evidencePath(root, cell, rep);
        Files.createDirectories(file.getParent());
        Files.writeString(file, FixtureLoader.JSON.writeValueAsString(observation) + "\n");
        if (AdapterManifest.isControl(fixture.scenario())) {
          var declaration = manifest.controlFailure(cell);
          boolean valid =
              declaration.isPresent()
                  ? declaration.get().matches(observation.category(), result.resultLine())
                  : classified.category() == Category.EXECUTED_AS_SENT;
          if (!valid)
            throw new IOException(
                "Unexpected pin control: " + cell.key() + " " + observation.category());
        }
      }
    return observations;
  }

  static Path evidencePath(Path root, Cell cell, int rep) {
    return root.resolve(
        ".local/pins/"
            + cell.target()
            + "/"
            + cell.version()
            + "/"
            + cell.module()
            + "/"
            + cell.surface()
            + "/"
            + "jdk"
            + cell.jdk()
            + "/"
            + cell.httpClient()
            + "/"
            + cell.tool()
            + "/"
            + cell.fixtureId()
            + "-"
            + rep
            + ".json");
  }
}
