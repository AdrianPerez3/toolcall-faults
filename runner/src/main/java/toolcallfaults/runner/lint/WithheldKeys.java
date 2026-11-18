package toolcallfaults.runner.lint;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import toolcallfaults.runner.fixture.FixtureLoader;
import toolcallfaults.runner.judge.Check;
import toolcallfaults.runner.results.ResultStore;

/** Exact keys known to the maintainer's active private stores. */
final class WithheldKeys {
  private final Set<String> unfiled = new HashSet<>();
  private final Map<String, Set<String>> observed = new HashMap<>();

  WithheldKeys(Path root) throws IOException {
    Path local = root.resolve(".local");
    for (Path file : files(local.resolve("baseline"), 1, ".json")) {
      for (var entry : FixtureLoader.JSON.readTree(file.toFile()).path("entries"))
        if (!"filed".equals(entry.path("disclosure").asText()))
          unfiled.add(entry.path("cell").asText());
    }
    for (Path file : files(local.resolve("results"), 2, ".jsonl"))
      for (var row : ResultStore.readFile(file))
        observed.computeIfAbsent(row.cell(), ignored -> new HashSet<>()).add(row.category());

    Set<String> families = unfiled.stream().map(Check::family).collect(Collectors.toSet());
    observed.keySet().forEach(cell -> families.add(Check.family(cell)));
    if (families.isEmpty()) return;
    // A saved cross-JDK run can still hold a family that has since been filed and rerouted.
    for (String run : List.of("run-jdk17", "run-jdk21", "run-jdk25"))
      for (Path file : files(local.resolve(run).resolve(".local"), 2, ".jsonl"))
        for (var row : ResultStore.readFile(file))
          if (families.contains(Check.family(row.cell())))
            observed.computeIfAbsent(row.cell(), ignored -> new HashSet<>()).add(row.category());
  }

  private static List<Path> files(Path directory, int depth, String suffix) throws IOException {
    if (!Files.isDirectory(directory)) return List.of();
    try (var entries = Files.walk(directory.toRealPath(), depth)) {
      return entries.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
    }
  }

  boolean hasObservations() {
    return !observed.isEmpty();
  }

  static void filed(byte[] baseline, Map<String, String> categories) throws IOException {
    for (var entry : FixtureLoader.JSON.readTree(baseline).path("entries"))
      if ("filed".equals(entry.path("disclosure").asText()))
        categories.put(entry.path("cell").asText(), entry.path("category").asText());
  }

  Set<String> keys(Map<String, String> filed) {
    Set<String> keys = new TreeSet<>(unfiled);
    observed.forEach((cell, categories) -> {
      if (categories.size() != 1 || !categories.contains(filed.get(cell)))
        keys.add(cell);
    });
    return keys;
  }

  static Pattern matcher(Set<String> keys) {
    if (keys.isEmpty()) return null;
    String alternatives = keys.stream().map(Pattern::quote).collect(Collectors.joining("|"));
    return Pattern.compile("(?<![A-Za-z0-9_-])(?:" + alternatives + ")(?![A-Za-z0-9_-])");
  }
}
