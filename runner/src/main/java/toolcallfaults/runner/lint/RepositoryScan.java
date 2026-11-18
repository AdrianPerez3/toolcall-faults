package toolcallfaults.runner.lint;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class RepositoryScan {
  private RepositoryScan() {}

  public static List<Problem> scan(Path root) throws IOException, InterruptedException {
    String names = new String(git(root, "ls-files", "--cached", "-z"), StandardCharsets.UTF_8);
    List<String> files =
        Arrays.stream(names.split("\0")).filter(s -> !s.isEmpty()).distinct().toList();
    Set<String> stagedAllow =
        files.contains("scan-allow.txt")
            ? allow(git(root, "cat-file", "blob", ":scan-allow.txt"))
            : Set.of();
    Set<String> workingAllow =
        Files.exists(root.resolve("scan-allow.txt"))
            ? allow(Files.readAllBytes(root.resolve("scan-allow.txt")))
            : Set.of();
    var inventory = new WithheldKeys(root);
    Map<String, String> stagedFiled = new HashMap<>(), workingFiled = new HashMap<>();
    if (inventory.hasObservations()) {
      for (String name : files) {
        if (!name.startsWith("expectations/baseline/") || !name.endsWith(".json")) continue;
        WithheldKeys.filed(git(root, "cat-file", "blob", ":" + name), stagedFiled);
        if (Files.isRegularFile(root.resolve(name)))
          WithheldKeys.filed(Files.readAllBytes(root.resolve(name)), workingFiled);
      }
    }
    var stagedKeys = inventory.keys(stagedFiled);
    var workingKeys = inventory.keys(workingFiled);
    var stagedMatcher = WithheldKeys.matcher(stagedKeys);
    var workingMatcher =
        workingKeys.equals(stagedKeys) ? stagedMatcher : WithheldKeys.matcher(workingKeys);
    List<Problem> problems = new ArrayList<>();
    for (String name : files) {
      byte[] staged = git(root, "cat-file", "blob", ":" + name);
      problems.addAll(SecretPathScan.scan(name + " (index)", staged, stagedAllow, stagedMatcher));
      Path file = root.resolve(name);
      if (Files.exists(file)) {
        byte[] working = Files.readAllBytes(file);
        if (!Arrays.equals(working, staged) || !workingAllow.equals(stagedAllow)
            || workingMatcher != stagedMatcher)
          problems.addAll(SecretPathScan.scan(name, working, workingAllow, workingMatcher));
      }
    }
    return List.copyOf(problems);
  }

  /** Lint the data a commit will contain, using local disclosure context if it is available. */
  public static List<Problem> stagedData(Path root) throws IOException, InterruptedException {
    // If the index and working inputs agree, the normal lint pass already checked them.
    if (git(root, "diff", "--name-only", "-z", "--", "adapters", "suite", "expectations",
            "results", "matrix.json").length == 0
        && git(root, "ls-files", "--others", "--exclude-standard", "-z", "--", "adapters",
                "suite", "expectations", "results", "matrix.json").length == 0)
      return List.of();

    Path staged = Files.createTempDirectory("tcf-index-");
    try {
      git(root, "checkout-index", "--all", "--prefix=" + staged.toAbsolutePath() + "/");
      Path local = root.resolve(".local").toAbsolutePath();
      if (Files.isDirectory(local)) Files.createSymbolicLink(staged.resolve(".local"), local);
      var problems = new ArrayList<>(FixtureLint.check(staged.resolve("suite")));
      problems.addAll(ManifestLint.check(staged));
      return problems.stream()
          .map(p -> new Problem(staged.relativize(Path.of(p.file())) + " (index)",
              p.jsonPath(), p.message()))
          .toList();
    } finally {
      try (var files = Files.walk(staged)) {
        for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static Set<String> allow(byte[] bytes) {
    return new LinkedHashSet<>(new String(bytes, StandardCharsets.UTF_8).lines().toList());
  }

  private static byte[] git(Path root, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Process p =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    byte[] result = p.getInputStream().readAllBytes();
    if (p.waitFor() != 0) throw new IOException("Cannot read repository index");
    return result;
  }
}
