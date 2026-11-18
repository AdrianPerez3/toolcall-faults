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
}
