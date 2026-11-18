package toolcallfaults.runner.results;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;

public final class ResultStore {
  private final Path publicDir, localDir;
  private final String visiblePermissions;

  public ResultStore(Path publicDir, Path localDir) {
    this(publicDir, localDir, "rw-r--r--");
  }

  private ResultStore(Path publicDir, Path localDir, String visiblePermissions) {
    this.visiblePermissions = visiblePermissions;
    this.publicDir = publicDir;
    this.localDir = localDir;
  }

  /** Both sides of the local comparison history can contain formerly withheld evidence. */
  public static ResultStore previous(Path root) {
    return new ResultStore(root.resolve(".local/previous-results"),
        root.resolve(".local/previous-withheld"), "rw-------");
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
      RunRecord row = FixtureLoader.JSON.treeToValue(n, RunRecord.class);
      String[] cell = row.cell() == null ? new String[0] : row.cell().split("\\|", -1);
      String identity = file.getParent().getFileName() + "@"
          + file.getFileName().toString().replaceFirst("\\.jsonl$", "");
      if (cell.length != 7 || !cell[0].equals(identity)
          || !cell[2].equals(row.surface()) || !cell[3].equals(row.fixtureId()))
        throw new IOException("Result cell, file, surface and fixture must agree in " + file);
      rows.add(row);
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
    // A new observation can withhold an older public sibling even if it did not run again.
    for (var it = visible.iterator(); it.hasNext(); ) {
      var row = it.next();
      if (withheldCells.contains(row.cell())) {
        withheld.add(row);
        it.remove();
      }
    }
    for (var row : records) (withheldCells.contains(row.cell()) ? withheld : visible).add(row);
    // Remove public copies before recording withheld results.
    writeFile(file(publicDir, target, version), visible, visiblePermissions);
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
