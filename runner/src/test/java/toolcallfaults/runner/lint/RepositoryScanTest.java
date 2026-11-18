package toolcallfaults.runner.lint;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryScanTest {
  @TempDir Path root;
  private static final String ADDRESS = "someone" + "@" + "example.org";

  void git(String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Process p =
        new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    String output =
        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), output);
  }

  void init() throws Exception {
    git("init", "-q");
    Files.writeString(root.resolve("scan-allow.txt"), "");
    git("add", "scan-allow.txt");
  }

  int scan() throws Exception {
    return lint("--scan");
  }

  int lint(String... options) throws Exception {
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), "toolcallfaults.runner.Main", "lint"));
    command.addAll(List.of(options));
    ProcessBuilder b =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true);
    b.environment().clear();
    Process p = b.start();
    String output =
        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    int code = p.waitFor();
    assertTrue(output.contains("scan:"), output);
    assertFalse(output.contains(ADDRESS), "Scan must not expose matched content");
    if (options.length != 0) assertFalse(output.contains(PRIVATE_CELL), "Scan must not print a withheld key");
    return code;
  }

  @Test
  void staged_forbidden_content_is_rejected_after_worktree_cleanup() throws Exception {
    init();
    Files.writeString(root.resolve("sample.txt"), ADDRESS);
    git("add", "sample.txt");
    Files.writeString(root.resolve("sample.txt"), "clean");
    assertEquals(1, scan());
  }

  @Test
  void unstaged_allowlist_cannot_exempt_staged_content() throws Exception {
    init();
    Files.writeString(root.resolve("sample.txt"), ADDRESS);
    git("add", "sample.txt");
    Files.writeString(root.resolve("scan-allow.txt"), ADDRESS);
    assertEquals(1, scan());
  }

  @Test
  void staged_file_deleted_from_worktree_is_still_scanned() throws Exception {
    init();
    Files.writeString(root.resolve("sample.txt"), ADDRESS);
    git("add", "sample.txt");
    Files.delete(root.resolve("sample.txt"));
    assertEquals(1, scan());
  }

  @Test
  void clean_staged_and_worktree_files_pass() throws Exception {
    init();
    Files.writeString(root.resolve("sample.txt"), "clean");
    git("add", "sample.txt");
    assertEquals(0, scan());
  }

  @Test
  void staged_result_must_pass_disclosure_checks_even_if_working_copy_was_cleaned() throws Exception {
    init();
    Files.createDirectory(root.resolve("suite"));
    Files.copy(Path.of("../suite/judgements.json"), root.resolve("suite/judgements.json"));
    var cell = toolcallfaults.runner.results.Records.CELL;
    var store = new toolcallfaults.runner.results.ResultStore(
        root.resolve("results"), root.resolve(".local/results"));
    store.write("target", "1",
        List.of(toolcallfaults.runner.results.Records.row(cell, 1, "EXECUTED_ALTERED", 1)), Set.of());
    git("add", "suite", "results");
    Files.writeString(root.resolve("results/target/1.jsonl"), "");
    assertEquals(1, lint());
    git("add", "results");
    assertEquals(0, lint());
  }

  private static final String PRIVATE_CELL = toolcallfaults.runner.results.Records.CELL;

  private void privateBaseline() throws Exception {
    var dir = Files.createDirectories(root.resolve(".local/baseline"));
    var doc = toolcallfaults.runner.fixture.FixtureLoader.JSON.createObjectNode()
        .put("format", "tcf.baseline/v1");
    doc.putArray("entries").addObject().put("cell", PRIVATE_CELL)
        .put("category", "EXECUTED_ALTERED").put("disclosure", "unfiled");
    Files.writeString(dir.resolve("target@1.json"), doc.toString());
  }

  @Test
  void private_baseline_key_in_staged_document_is_rejected_after_working_copy_cleanup()
      throws Exception {
    init();
    privateBaseline();
    Files.writeString(root.resolve("notes.md"), "Cell `" + PRIVATE_CELL + "`");
    git("add", "notes.md");
    Files.writeString(root.resolve("notes.md"), "Updated");
    assertEquals(1, lint());
    git("add", "notes.md");
    assertEquals(0, lint());
  }

  @Test
  void escaped_private_result_key_in_a_working_table_is_rejected_until_rerouted() throws Exception {
    init();
    var store = new toolcallfaults.runner.results.ResultStore(root.resolve("results"),
        root.resolve(".local/results"));
    var rows = List.of(toolcallfaults.runner.results.Records.row(PRIVATE_CELL, 1, "SURFACED", 1));
    store.write("target", "1", rows, Set.of(PRIVATE_CELL));
    var doc = root.resolve("table.md");
    Files.writeString(doc, "Empty");
    git("add", "table.md");
    Files.writeString(doc, "| " + PRIVATE_CELL.replace("|", "\\|") + " |");
    assertEquals(1, scan());
    store.write("target", "1", rows, Set.of());
    assertEquals(0, scan());
  }

  @Test
  void saved_jdk_sibling_is_private_while_its_unfiled_family_is_active() throws Exception {
    init();
    privateBaseline();
    String sibling = PRIVATE_CELL.replace("loop-stream", "model-stream").replace("jdk21", "jdk17");
    var saved = root.resolve(".local/run-jdk17");
    new toolcallfaults.runner.results.ResultStore(saved, saved.resolve(".local"))
        .write("target", "1", List.of(toolcallfaults.runner.results.Records.row(sibling, 1, "SURFACED", 1)),
            Set.of(sibling));
    Files.writeString(root.resolve("export.jsonl"), "{\"result\":\"" + sibling + "\"}");
    git("add", "export.jsonl");
    assertEquals(1, scan());
    Files.delete(root.resolve(".local/baseline/target@1.json"));
    assertEquals(0, scan(), "an old private replay is no longer an active family");
  }

  @Test
  void matching_filed_category_allows_transition_and_a_different_category_stays_private()
      throws Exception {
    init();
    var store = new toolcallfaults.runner.results.ResultStore(root.resolve("results"),
        root.resolve(".local/results"));
    store.write("target", "1",
        List.of(toolcallfaults.runner.results.Records.row(PRIVATE_CELL, 1, "DROPPED", 1)),
        Set.of(PRIVATE_CELL));
    Files.writeString(root.resolve("notes.md"), PRIVATE_CELL);
    Path dir = Files.createDirectories(root.resolve("expectations/baseline"));
    Path file = dir.resolve("target@1.json");
    var doc = toolcallfaults.runner.fixture.FixtureLoader.JSON.createObjectNode()
        .put("format", "tcf.baseline/v1");
    var entry = doc.putArray("entries").addObject().put("cell", PRIVATE_CELL)
        .put("disclosure", "filed").put("category", "SURFACED")
        .put("issue", "https://example.org/issues/1").put("filedOn", "2026-11-18");
    Files.writeString(file, doc.toString());
    git("add", "notes.md", "expectations");
    entry.put("category", "DROPPED");
    Files.writeString(file, doc.toString());
    assertEquals(1, scan(), "working filing must not clear the staged view");
    git("add", "expectations");
    assertEquals(0, scan(), "the filed category may be named even before its private rows are rerun");
  }
}
