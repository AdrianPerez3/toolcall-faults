package toolcallfaults.runner.lint;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Guards what the hosted workflows may print, upload and run. */
class WorkflowsTest {
  private final Path root = Path.of("..").toAbsolutePath().normalize();

  private List<String> lines(String name) throws Exception {
    return Files.readAllLines(root.resolve(".github/workflows").resolve(name));
  }

  private static final Pattern OBSERVING_COMMAND =
      Pattern.compile("\\./tcf .*\\b(selftest|run|check|cell|report)\\b");

  @Test
  void commands_that_print_observations_keep_their_output_in_the_workspace() throws Exception {
    for (String name : List.of("ci.yml", "weekly.yml"))
      for (String line : lines(name)) {
        if (!OBSERVING_COMMAND.matcher(line).find()) continue;
        assertTrue(
            line.matches(".*> \\.local/[a-z-]+\\.log 2>&1.*"),
            name + " prints cell observations to the public log: " + line.strip());
      }
  }

  private static final String LOG = "\\.local/[a-z-]+\\.log";

  /** The only uses a workflow may make of a workspace log: write it, print a summary or a count. */
  private static final List<Pattern> LOG_USES =
      List.of(
          Pattern.compile("> " + LOG + " 2>&1"),
          Pattern.compile("awk -f \\.github/scripts/summary\\.awk phase=(selftest-failed|selftest|run|check) " + LOG));

  private static boolean readsALogAnotherWay(String line) {
    String rest = line;
    for (Pattern use : LOG_USES) rest = use.matcher(rest).replaceAll("");
    return rest.contains(".log");
  }

  @Test
  void a_workspace_log_reaches_the_public_log_only_as_summary_lines_and_counts() throws Exception {
    assertTrue(readsALogAnotherWay("          cat .local/ci-run.log"));
    assertTrue(readsALogAnotherWay("x > .local/a.log 2>&1 || grep CHANGED .local/a.log"));
    assertTrue(readsALogAnotherWay("x > .local/a.log 2>&1 || grep HARNESS .local/a.log"));
    assertTrue(readsALogAnotherWay("x > .local/a.log 2>&1 || grep -E '^run: ' .local/a.log"));
    assertFalse(readsALogAnotherWay("awk -f .github/scripts/summary.awk phase=run .local/a.log"));
    for (String name : List.of("ci.yml", "weekly.yml")) {
      var text = lines(name);
      for (String line : text)
        assertFalse(readsALogAnotherWay(line), name + " prints from a log: " + line.strip());
      for (String phase : List.of("selftest-failed", "run", "check"))
        assertTrue(text.stream().anyMatch(line -> line.contains("summary.awk phase=" + phase + " ")),
            name + " has no summary for " + phase);
    }
  }

  @Test
  void only_the_run_stamp_is_uploaded() throws Exception {
    assertTrue(
        lines("ci.yml").stream().noneMatch(l -> l.contains("upload-artifact")),
        "ci.yml must not upload artifacts");
    var weekly = lines("weekly.yml");
    assertEquals(1, weekly.stream().filter(l -> l.contains("upload-artifact")).count());
    assertEquals(
        List.of("path: results/last-run.json"),
        weekly.stream().map(String::strip).filter(l -> l.startsWith("path:")).toList());
  }

  @Test
  void actions_are_pinned_and_permissions_are_read_only() throws Exception {
    for (String name : List.of("ci.yml", "weekly.yml")) {
      var text = lines(name);
      for (String line : text)
        if (line.strip().startsWith("- uses:") || line.strip().startsWith("uses:"))
          assertTrue(
              line.matches(".*uses: [A-Za-z0-9_./-]+@[0-9a-f]{40} # v[0-9.]+"),
              name + " action is not pinned by commit: " + line.strip());
      assertTrue(text.contains("permissions:") && text.contains("  contents: read"), name);
      assertTrue(text.stream().noneMatch(l -> l.contains(": write")), name + " requests write");
      assertTrue(text.stream().noneMatch(l -> l.contains("pull_request_target")), name);
    }
  }

  @Test
  void changes_to_files_the_checks_depend_on_trigger_ci() throws Exception {
    var ci = lines("ci.yml");
    for (String path : List.of("results/**", "scan-allow.txt", "compat/**", ".githooks/**"))
      assertTrue(ci.contains("      - '" + path + "'"), "ci.yml does not run for " + path);
  }

  @Test
  void a_change_runs_ci_once_and_a_run_on_main_is_never_cancelled() throws Exception {
    // A pull request branch in this repository would otherwise start a push run and a
    // pull_request run of the same commit.
    var ci = lines("ci.yml");
    int push = ci.indexOf("  push:");
    assertTrue(push > 0, "ci.yml has no push trigger");
    assertEquals("    branches: [main]", ci.get(push + 1));
    assertTrue(ci.contains("  workflow_dispatch:"), "ci.yml cannot be started by hand");
    // Only a pull request has a number; every other run is alone in its group, so a newer push
    // replaces a pull request's run and nothing replaces a run on main.
    int concurrency = ci.indexOf("concurrency:");
    assertTrue(concurrency > 0, "ci.yml has no concurrency group");
    assertEquals(
        "  group: ci-${{ github.event.pull_request.number || github.run_id }}",
        ci.get(concurrency + 1));
    assertEquals("  cancel-in-progress: true", ci.get(concurrency + 2));
  }

  /** The lines of each job, by job name. */
  private java.util.Map<String, List<String>> jobs(String name) throws Exception {
    var text = lines(name);
    var jobs = new java.util.LinkedHashMap<String, List<String>>();
    List<String> current = null;
    for (String line : text.subList(text.indexOf("jobs:") + 1, text.size())) {
      if (line.matches("  [a-z-]+:")) {
        current = new java.util.ArrayList<>();
        jobs.put(line.strip().replace(":", ""), current);
      } else if (current != null) current.add(line);
    }
    return jobs;
  }

  @Test
  void every_job_has_a_time_limit() throws Exception {
    for (String name : List.of("ci.yml", "weekly.yml")) {
      var jobs = jobs(name);
      assertFalse(jobs.isEmpty(), name);
      for (var job : jobs.entrySet())
        assertTrue(
            job.getValue().stream().anyMatch(l -> l.matches("    timeout-minutes: [0-9]+")),
            name + " job " + job.getKey() + " has no time limit");
    }
  }

  @Test
  void the_self_test_and_the_cells_run_as_separate_jobs() throws Exception {
    // Side by side on two runners, so that neither comes near its time limit; a cell still runs
    // alone on its runner.
    var jobs = jobs("ci.yml");
    assertEquals(List.of("selftest", "cells"), List.copyOf(jobs.keySet()));
    var selftest = String.join("\n", jobs.get("selftest"));
    var cells = String.join("\n", jobs.get("cells"));
    assertTrue(selftest.contains(" selftest > ") && selftest.contains("./tcf lint"));
    assertFalse(selftest.contains(" --published ") || selftest.contains(" check --run "));
    assertTrue(cells.contains(" run --jdk \"$JDK\" --published "));
    assertTrue(cells.contains(" check --run "));
    assertFalse(cells.contains(" selftest > "));
    for (String job : List.of(selftest, cells)) {
      assertFalse(job.contains("needs:"), "the jobs do not wait for each other");
      assertTrue(
          job.contains("docker build --build-arg JDK=\"$JDK\""), "each job builds the image");
      assertTrue(job.contains("        jdk: ['17', '21', '25']"), "each job runs on every JDK");
    }
  }

  @Test
  void the_weekly_run_does_not_overlap_itself_or_stamp_a_modified_tree() throws Exception {
    var weekly = lines("weekly.yml");
    int concurrency = weekly.indexOf("concurrency:");
    assertTrue(concurrency > 0, "weekly.yml has no concurrency group");
    assertEquals("  group: weekly", weekly.get(concurrency + 1));
    assertEquals("  cancel-in-progress: false", weekly.get(concurrency + 2));
    int refusal = -1, write = -1;
    for (int i = 0; i < weekly.size(); i++) {
      if (weekly.get(i).contains("endswith('-dirty')")) refusal = i;
      if (weekly.get(i).contains("results/last-run.json').write_text")) write = i;
    }
    assertTrue(refusal > 0 && refusal < write, "the stamp is written without a clean-tree check");
  }

  @Test
  void dependency_updates_watch_the_libraries_under_test_and_keep_major_updates_apart()
      throws Exception {
    var config = Files.readAllLines(root.resolve(".github/dependabot.yml"));
    // An update of a library under test is how a new release is noticed; an ignore rule that
    // covers one would end that. Lint reports the bump until its rows exist.
    List<String> ignored =
        config.stream()
            .filter(l -> l.contains("dependency-name:"))
            .map(l -> l.replaceFirst(".*dependency-name: '([^']*)'.*", "$1"))
            .toList();
    try (var adapters = Files.list(root.resolve("adapters"))) {
      for (Path adapter : adapters.filter(Files::isDirectory).sorted().toList()) {
        assertTrue(
            config.contains("    directory: '/adapters/" + adapter.getFileName() + "'"),
            "no updates for " + adapter.getFileName());
        var coordinates =
            toolcallfaults.runner.fixture.FixtureLoader.JSON
                .readTree(adapter.resolve("adapter.json").toFile())
                .path("coordinates");
        assertFalse(coordinates.isEmpty(), adapter.toString());
        for (var coordinate : coordinates)
          for (String rule : ignored)
            assertFalse(
                coordinate.asText().matches(rule.replace(".", "\\.").replace("*", ".*")),
                rule + " hides the library under test " + coordinate.asText());
      }
    }
    // A major update of the build's own tools needs its own review and its own run.
    int toolchain = config.indexOf("      toolchain:"),
        major = config.indexOf("      toolchain-major:");
    assertTrue(toolchain > 0 && major > toolchain, "no separate group for major updates");
    assertEquals("        update-types: ['minor', 'patch']", config.get(toolchain + 2));
    assertEquals("        update-types: ['major']", config.get(major + 2));
  }

  @Test
  void private_workspace_inputs_stay_out_of_the_reference_build_context() throws Exception {
    var excluded = Files.readAllLines(root.resolve(".dockerignore"));
    for (String pattern : List.of("**/.local", "AGENTS.md", "CLAUDE.md", ".claude", ".codex",
        ".codex-log", "**/.env", "**/probe", "**/research_notes", "**/evidence"))
      assertTrue(excluded.contains(pattern), "Missing private build exclusion: " + pattern);
  }

  private static final String THREE_ATTEMPTS =
      "; do n=$((n+1)); [ \"$n\" -lt 3 ] || exit 1; sleep 15; done";

  @Test
  void a_clean_checkout_and_image_include_the_verified_wrapper_bootstrap() throws Exception {
    var wrapper = root.resolve(".mvn/wrapper");
    var properties = new java.util.Properties();
    try (var input = Files.newInputStream(wrapper.resolve("maven-wrapper.properties"))) {
      properties.load(input);
    }
    assertEquals("bin", properties.getProperty("distributionType"));
    byte[] bytes = Files.readAllBytes(wrapper.resolve("maven-wrapper.jar"));
    var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
    assertEquals(properties.getProperty("wrapperSha256Sum"), java.util.HexFormat.of().formatHex(digest));
    for (String excludes : List.of(".gitignore", ".dockerignore"))
      assertFalse(Files.readAllLines(root.resolve(excludes)).contains(".mvn/wrapper/maven-wrapper.jar"));
  }

  @Test
  void the_first_maven_call_of_a_fresh_environment_only_downloads_and_is_tried_three_times()
      throws Exception {
    // The bootstrap retries distribution and dependency downloads before starting the tests.
    var image =
        Files.readAllLines(root.resolve("Dockerfile")).stream()
            .filter(l -> l.contains("./mvnw"))
            .toList();
    assertEquals(
        "RUN n=0; until ./mvnw -q -B dependency:go-offline" + THREE_ATTEMPTS, image.get(0));
    for (String name : List.of("ci.yml", "weekly.yml")) {
      var steps = lines(name).stream().map(String::strip).toList();
      int bootstrap = steps.indexOf("run: sh .github/scripts/prepare-maven.sh");
      int verify = steps.indexOf("- run: ./mvnw -B verify");
      assertTrue(bootstrap >= 0 && verify == bootstrap + 1, name);
      assertEquals(List.of("- run: ./mvnw -B verify"),
          steps.stream().filter(l -> l.contains("./mvnw")).toList(), name);
    }
    for (String name :
        List.of("Dockerfile", ".github/workflows/ci.yml", ".github/workflows/weekly.yml"))
      for (String line : Files.readAllLines(root.resolve(name)))
        if (line.contains("until ") && line.contains("./mvnw"))
          assertFalse(
              line.matches(".*\\b(verify|package|test|install)\\b.*"),
              name + " repeats a command that runs tests: " + line.strip());
  }

  @Test
  void reference_images_are_pinned_and_watched_for_digest_updates() throws Exception {
    var dockerfile = Files.readAllLines(root.resolve("Dockerfile"));
    for (String jdk : List.of("17", "21", "25")) {
      String image = "FROM maven:3.9-eclipse-temurin-" + jdk + "@sha256:";
      assertTrue(dockerfile.stream().anyMatch(line ->
          line.matches(Pattern.quote(image) + "[0-9a-f]{64} AS base" + jdk)));
    }
    var config = Files.readString(root.resolve(".github/dependabot.yml"));
    assertTrue(config.contains("  - package-ecosystem: docker\n    directory: '/'\n"));
  }

  @Test
  void hosted_check_requires_all_published_cells() throws Exception {
    for (String workflow : List.of("ci.yml", "weekly.yml"))
      assertTrue(lines(workflow).stream().anyMatch(line ->
          line.contains(" check --run .local/run --published ")), workflow);
  }

  @Test
  void hosted_runs_observe_only_published_cells() throws Exception {
    // A public checkout has no baselines for unpublished cells; it neither produces nor judges
    // their observations.
    for (String name : List.of("ci.yml", "weekly.yml"))
      for (String line : lines(name))
        if (line.matches(".*\\./tcf (--container \\S+ )?run .*"))
          assertTrue(line.contains(" --published "), name + ": " + line.strip());
  }

  private String step(String workflow, String name) throws Exception {
    var lines = lines(workflow);
    int start = lines.indexOf("      - name: " + name);
    assertTrue(start >= 0);
    int block = lines.subList(start, lines.size()).indexOf("        run: |");
    assertTrue(block >= 0);
    return String.join("\n", lines.stream().skip(start + block + 1L)
        .takeWhile(line -> line.startsWith("          "))
        .map(line -> line.substring(10)).toList());
  }

  private String failedStep(Path temp, String workflow, String name, String log) throws Exception {
    return stepOutput(temp, workflow, name, log, 17);
  }

  private String stepOutput(Path temp, String workflow, String name, String log, int status)
      throws Exception {
    Files.writeString(temp.resolve("input.txt"), log);
    var process = new ProcessBuilder("sh", "-e", "-c", step(workflow, name))
        .directory(temp.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(status, process.waitFor(), output);
    return output;
  }

  @Test
  void failed_steps_report_numeric_counts_and_keep_observation_text_private(
      @org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
    Files.createSymbolicLink(temp.resolve(".github"), root.resolve(".github"));
    Path command = temp.resolve("tcf");
    Files.writeString(command, "#!/bin/sh\ncat input.txt\nexit 17\n");
    assertTrue(command.toFile().setExecutable(true));
    String a = "example@1|module|model-stream|wire/test|tool|jdk21|jdk";
    String b = a.replace("model-stream", "loop-stream");
    for (String workflow : List.of("ci.yml", "weekly.yml")) {
      String run = failedStep(temp, workflow, "Run matrix",
          "controls: 3 unexpected repetitions; inspect local evidence\n"
          + "run: 2 cells x 3 repetitions, 0 HARNESS\n"
          + "FLAKY " + a + " {SURFACED=2, DROPPED=1}\n"
          + "run: 9 cells x 3 repetitions, 0 HARNESS private text\n");
      assertEquals("run: 2 cells x 3 repetitions, 0 HARNESS\n"
          + "unstable cells: 1\nunexpected control repetitions: 3\n"
          + "run failed; details stay in the workspace\n", run);
      String check = failedStep(temp, workflow, "Check observations",
          "INCOMPLETE " + a + " private details\nFLAKY " + a + " {DROPPED=1}\n"
          + "HARNESS " + b + " private details\nNEW " + b + " private details\n"
          + "Run does not contain the complete published current matrix\n");
      assertEquals("differing cells: 2\n"
          + "findings: CHANGED=0 NEW=1 STALE=0 INCOMPLETE=1 FLAKY=1 HARNESS=1\n"
          + "published coverage: incomplete\ncheck failed; details stay in the workspace\n", check);
      String self = failedStep(temp, workflow, "Self-test",
          "server: 5 of 5 fixtures byte-exact\ncontrols: private text\n"
          + "FLAKY " + b + " HARNESS\n");
      assertEquals("server: 5 of 5 fixtures byte-exact\nharness lines: 1\n"
          + "selftest failed; details stay in the workspace\n", self);
    }
  }

  @Test
  void successful_self_test_requires_exactly_one_summary_for_each_phase(
      @org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
    Files.createSymbolicLink(temp.resolve(".github"), root.resolve(".github"));
    Path command = temp.resolve("tcf");
    Files.writeString(command, "#!/bin/sh\ncat input.txt\n");
    assertTrue(command.toFile().setExecutable(true));
    String server = "server: 1 of 1 fixtures byte-exact\n";
    String rest = "controls: 1 of 1 EXECUTED_AS_SENT; 0 declared\n"
        + "anchors: 1 of 1 matched (JDK 21; probe JDK 21)\npins: 1 of 1 DIFFERS\n";
    for (String workflow : List.of("ci.yml", "weekly.yml")) {
      assertEquals(server + rest, stepOutput(temp, workflow, "Self-test", server + rest, 0));
      stepOutput(temp, workflow, "Self-test", server, 1);
      stepOutput(temp, workflow, "Self-test", server + rest + server, 1);
    }
  }
}
