package toolcallfaults.runner.lint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PushHookTest {
  @TempDir Path repository;

  private ProcessBuilder command(List<String> arguments) {
    var builder = new ProcessBuilder(arguments).directory(repository.toFile()).redirectErrorStream(true);
    var environment = builder.environment();
    environment.keySet().removeIf(key -> key.startsWith("GIT_"));
    environment.put("GIT_CONFIG_NOSYSTEM", "1");
    environment.put("GIT_CONFIG_GLOBAL", repository.resolve("empty-global-config").toString());
    return builder;
  }

  private String git(String input, String... args) throws Exception {
    List<String> arguments = new ArrayList<>(List.of("git", "-c", "user.name=Test", "-c", "user.email=local",
        "-c", "tag.gpgSign=false", "-c", "commit.gpgSign=false"));
    arguments.addAll(List.of(args));
    Process process = command(arguments).start();
    try (var stdin = process.getOutputStream()) {
      stdin.write(input.getBytes(UTF_8));
    }
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output.strip();
  }

  private void check(boolean allowed, boolean authorized, String... oids) throws Exception {
    var builder = command(List.of("sh", "hook"));
    builder.environment().remove("TCF_ALLOW_PUSH");
    if (authorized) builder.environment().put("TCF_ALLOW_PUSH", "1");
    var process = builder.start();
    String zero = "0".repeat(40);
    try (var stdin = process.getOutputStream()) {
      for (String oid : oids) {
        String source = oid.equals(zero) ? "(delete)" : "refs/heads/source";
        stdin.write((source + " " + oid + " refs/heads/destination " + zero + "\n").getBytes(UTF_8));
      }
    }
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    int exit = process.waitFor();
    assertEquals(allowed, exit == 0, "Unexpected hook result for " + List.of(oids) + ": " + output);
  }

  @Test
  void every_pushed_object_must_lead_only_to_the_reviewed_root() throws Exception {
    git("", "init", "--quiet", "--template=", "--object-format=sha1");
    String tree = git("", "mktree");
    String root = git("reviewed", "commit-tree", tree);
    String next = git("regular change", "commit-tree", tree, "-p", root);
    String other = git("unrelated", "commit-tree", tree);
    String otherChild = git("unrelated child", "commit-tree", tree, "-p", other);
    String mixed = git("mixed ancestry", "commit-tree", tree, "-p", next, "-p", otherChild);
    git("", "tag", "-a", "release", "-m", "release", next);
    git("", "tag", "-a", "mixed", "-m", "mixed", mixed);

    // Use the real hook logic with the synthetic reviewed root in place of the published object ID.
    String hook = Files.readString(Path.of("../.githooks/pre-push"));
    Files.writeString(repository.resolve("hook"),
        hook.replaceFirst("(?m)^public_root=[0-9a-f]{40}$", "public_root=" + root));

    check(true, true, root, next, git("", "rev-parse", "refs/tags/release"));
    check(false, true, otherChild);
    check(false, true, mixed);
    check(false, true, git("", "rev-parse", "refs/tags/mixed"));
    check(false, true, next, other);
    check(false, true, tree);
    check(false, true, "f".repeat(40));
    check(true, true, "0".repeat(40));
    check(false, false, next);
  }
}
