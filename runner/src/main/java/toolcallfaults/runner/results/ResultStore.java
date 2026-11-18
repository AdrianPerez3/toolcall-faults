package toolcallfaults.runner.results;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;

public final class ResultStore {
  private final Path publicDir, localDir;

  public ResultStore(Path publicDir, Path localDir) {
    this.publicDir = publicDir;
    this.localDir = localDir;
  }

  private Path file(Path dir, String target, String version) {
    if (!target.matches("[a-z0-9-]+")
        || !version.matches("[a-zA-Z0-9_.-]+")
        || version.equals(".."))
      throw new IllegalArgumentException("Invalid result target/version");
    return dir.resolve(target).resolve(version + ".jsonl");
  }

  public List<RunRecord> read(String target, String version, boolean includeWithheld)
      throws IOException {
    List<RunRecord> rows = new ArrayList<>(readFile(file(publicDir, target, version)));
    if (includeWithheld) rows.addAll(readFile(file(localDir, target, version)));
    Set<String> keys = new HashSet<>();
    for (var row : rows)
      if (!keys.add(row.cell() + "|rep" + row.rep()))
        throw new IOException("Duplicate repetition " + row.cell());
    return List.copyOf(rows);
  }

  public static List<RunRecord> readFile(Path file) throws IOException {
    if (!Files.exists(file)) return List.of();
    List<RunRecord> rows = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      if (line.isBlank()) continue;
      var n = FixtureLoader.JSON.readTree(line);
      if (n == null || !n.isObject() || !n.path("format").asText().equals("tcf.record/v1"))
        throw new IOException("Invalid result record in " + file);
      ((ObjectNode) n).remove("format");
      rows.add(FixtureLoader.JSON.treeToValue(n, RunRecord.class));
    }
    return List.copyOf(rows);
  }

  public void write(
      String target, String version, List<RunRecord> records, Set<String> withheldCells)
      throws IOException {
    Set<String> ran = new HashSet<>();
    records.forEach(r -> ran.add(r.cell()));
    List<RunRecord> visible = new ArrayList<>(readFile(file(publicDir, target, version)));
    List<RunRecord> withheld = new ArrayList<>(readFile(file(localDir, target, version)));
    visible.removeIf(r -> ran.contains(r.cell()));
    withheld.removeIf(r -> ran.contains(r.cell()));
    for (var row : records) (withheldCells.contains(row.cell()) ? withheld : visible).add(row);
    // Remove public copies before recording withheld results.
    writeFile(file(publicDir, target, version), visible, "rw-r--r--");
    writeFile(file(localDir, target, version), withheld, "rw-------");
  }

  private static void writeFile(Path file, List<RunRecord> records, String permissions)
      throws IOException {
    Files.createDirectories(file.getParent());
    var sorted = new ArrayList<>(records);
    sorted.sort(Comparator.comparing(RunRecord::cell).thenComparingInt(RunRecord::rep));
    StringBuilder text = new StringBuilder();
    for (var row : sorted) {
      ObjectNode n = FixtureLoader.JSON.valueToTree(row);
      n.put("format", "tcf.record/v1");
      text.append(n).append('\n');
    }
    Path temporary = Files.createTempFile(file.getParent(), "records-", ".tmp");
    try {
      Files.writeString(temporary, text);
      // Public rows are read by later steps running as another user; withheld rows stay
      // owner-only.
      if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(
            temporary, java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
      try {
        Files.move(
            temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
