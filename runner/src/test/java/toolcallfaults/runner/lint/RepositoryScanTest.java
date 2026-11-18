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
    ProcessBuilder b =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "toolcallfaults.runner.Main",
                "lint",
                "--scan")
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
}
