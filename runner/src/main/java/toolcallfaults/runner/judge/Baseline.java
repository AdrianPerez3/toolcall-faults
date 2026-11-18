package toolcallfaults.runner.judge;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;

public final class Baseline {
  private final Map<String, Entry> entries;

  private Baseline(Map<String, Entry> entries) {
    this.entries = Collections.unmodifiableMap(entries);
  }

  public Optional<Entry> entry(String cell) {
    return Optional.ofNullable(entries.get(cell));
  }

  public Collection<Entry> entries() {
    return entries.values();
  }

  public static Baseline load(Path publicDir, Path localDir, String target, String version)
      throws IOException {
    Map<String, Entry> entries = new LinkedHashMap<>();
    for (Path dir : List.of(publicDir, localDir)) {
      Path file = dir.resolve(target + "@" + version + ".json");
      if (!Files.exists(file)) continue;
      var n = FixtureLoader.JSON.readTree(file.toFile());
      if (n == null
          || !n.path("format").asText().equals("tcf.baseline/v1")
          || !n.path("entries").isArray()) throw new IOException("Invalid baseline " + file);
      for (var row : n.get("entries")) {
        var entry = FixtureLoader.JSON.treeToValue(row, Entry.class);
        if (entries.putIfAbsent(entry.cell(), entry) != null)
          throw new IOException("Duplicate baseline cell " + entry.cell());
      }
    }
    return new Baseline(entries);
  }
}
