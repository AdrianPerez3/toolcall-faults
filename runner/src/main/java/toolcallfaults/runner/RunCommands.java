package toolcallfaults.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import toolcallfaults.runner.cell.*;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.runner.judge.*;
import toolcallfaults.runner.matrix.*;
import toolcallfaults.runner.oracle.*;
import toolcallfaults.runner.results.*;
import toolcallfaults.server.ReplayServer;

public final class RunCommands {
  private RunCommands() {}

  public static Path localResults(Path root, Path publicDir) {
    return defaultResults(root, publicDir)
        ? root.resolve(".local/results")
        : publicDir.resolve(".local");
  }

  private static boolean defaultResults(Path root, Path dir) {
    return dir.toAbsolutePath()
        .normalize()
        .equals(root.resolve("results").toAbsolutePath().normalize());
  }

  private static boolean selected(Cell c, Map<String, String> options) {
    return (!options.containsKey("--target") || c.target().equals(options.get("--target")))
        && (!options.containsKey("--http-client")
            || c.httpClient().equals(options.get("--http-client")))
        && (!options.containsKey("--surface") || c.surface().equals(options.get("--surface")))
        && (!options.containsKey("--wire") || c.fixtureId().startsWith(options.get("--wire") + "/"))
        && (!options.containsKey("--scenario")
            || c.fixtureId().split("/", 2)[1].split("@", 2)[0].equals(options.get("--scenario")));
  }

  /**
   * Cells run at once when no number is given: half the processors, at most eight. A cell is a
   * library JVM under a watchdog, so more cells than the host can start in time become HARNESS.
   */
  public static int defaultParallel(int processors) {
    return Math.max(1, Math.min(8, processors / 2));
  }

  /**
   * The cells whose observations are published: those with rows in the tracked results, on any
   * JDK. A public checkout has no baselines for the others and cannot judge what it would see.
   */
  static List<Cell> published(List<Cell> cells, Path trackedResults) throws IOException {
    Set<String> identities = new HashSet<>();
    if (Files.isDirectory(trackedResults))
      try (var files = Files.walk(trackedResults, 2)) {
        for (Path file : files.filter(p -> p.toString().endsWith(".jsonl")).toList())
          for (var row : ResultStore.readFile(file)) identities.add(withoutJdk(row.cell()));
      }
    return cells.stream().filter(c -> identities.contains(withoutJdk(c.key()))).toList();
  }

  private static String withoutJdk(String cell) {
    return cell.replaceFirst("\\|jdk[0-9]+\\|", "|");
  }

  public static void run(Path root, Map<String, String> options, boolean list, boolean rerunFlaky)
      throws Exception {
    run(root, options, list, rerunFlaky, false);
  }

  public static void run(
      Path root,
      Map<String, String> options,
      boolean list,
      boolean rerunFlaky,
      boolean publishedOnly)
      throws Exception {
    Matrix matrix = Matrix.load(root);
    String role = options.getOrDefault("--role", "current");
    int jdk =
        Integer.parseInt(
            options.getOrDefault("--jdk", Integer.toString(Runtime.version().feature())));
    var cells = matrix.cells(role, jdk).stream().filter(c -> selected(c, options)).toList();
    if (publishedOnly) cells = published(cells, root.resolve("results"));
    var notRun =
        matrix.notRun(role, jdk).stream().filter(n -> selected(n.cell(), options)).toList();
    if (list) {
      cells.forEach(c -> System.out.println(c.key()));
      return;
    }
    if (!Files.exists(Path.of("/.dockerenv")))
      throw new IllegalStateException("Use ./tcf --container " + jdk + " run");
    if (jdk != Runtime.version().feature())
      throw new IllegalArgumentException("Requested JDK differs from running JDK");
    if (cells.isEmpty()) throw new IllegalArgumentException("No cells selected");
    int parallel =
        options.containsKey("--parallel")
            ? Integer.parseInt(options.get("--parallel"))
            : defaultParallel(Runtime.getRuntime().availableProcessors());
    if (parallel < 1) throw new IllegalArgumentException("Parallelism must be positive");
    Path publicDir = Path.of(options.getOrDefault("--out", root.resolve("results").toString()));
    if (rerunFlaky) {
      int selectedCount = cells.size();
      cells = unstableCells(cells, readRecords(root, publicDir));
      System.out.println("flaky: " + cells.size() + " of " + selectedCount + " cells");
      if (cells.isEmpty()) return;
    }
    int reps = rerunFlaky ? 10 : matrix.config().path("repetitions").asInt();
    long timeout = matrix.config().path("timeouts").path("t_ms").asLong(),
        watchdog = matrix.config().path("timeouts").path("w_ms").asLong();
    String suiteVersion = Objects.requireNonNullElse(System.getenv("TCF_SUITE_VERSION"), "unknown"),
        imageDigest = System.getenv("TCF_IMAGE_DIGEST");
    Map<String, BuiltAdapter> adapters = new HashMap<>();
    for (Cell cell : cells) {
      String key = AdapterBuild.key(cell);
      if (!adapters.containsKey(key))
        adapters.put(
            key,
            AdapterBuild.ensure(root, cell.target(), cell.version(), AdapterBuild.variant(cell)));
    }
    ExecutorService workers = Executors.newFixedThreadPool(parallel);
    List<Future<List<RunRecord>>> pending = new ArrayList<>();
    List<RunRecord> records = new ArrayList<>();
    try {
      for (Cell cell : cells)
        pending.add(
            workers.submit(
                () -> {
                  List<RunRecord> rows = new ArrayList<>();
                  var fixture = ControlFixtures.load(root, cell.fixtureId());
                  var adapter = adapters.get(AdapterBuild.key(cell));
                  var manifest = matrix.manifests().get(cell.target());
                  for (int rep = 1; rep <= reps; rep++)
                    try (var server = ReplayServer.start(0)) {
                      var result =
                          new CellRunner(server, timeout, watchdog)
                              .run(cell, fixture, adapter, rep);
                      if (result.flagged())
                        throw new IOException(
                            "Private-reporting review required: request cap reached; raw evidence"
                                + " is local");
                      var classified =
                          Oracle.classify(fixture, result, adapter.typedErrorPrefixes());
                      ObjectNode attributes = (ObjectNode) classified.attributes().deepCopy();
                      attributes.put("timeout_ms", timeout);
                      manifest
                          .controlFailure(cell)
                          .ifPresent(
                              f -> {
                                attributes
                                    .put("control_failure_basis", f.basis())
                                    .put("control_failure_notes", f.notes());
                              });
                      var serverRecord =
                          FixtureLoader.JSON
                              .createObjectNode()
                              .put("request_count", result.requests().size())
                              .put("flagged", result.flagged());
                      var requests = serverRecord.putArray("requests");
                      for (var r : result.requests()) {
                        var row =
                            requests
                                .addObject()
                                .put("seq", r.seq())
                                .put("method", r.method())
                                .put("path", r.path())
                                .put("exchange", r.exchangeIndex())
                                .put("served_as_scripted", r.servedAsScripted())
                                .put("has_credential_header", r.hasCredentialHeader());
                        row.set("retry_headers", FixtureLoader.JSON.valueToTree(r.retryHeaders()));
                      }
                      rows.add(
                          new RunRecord(
                              cell.key(),
                              rep,
                              cell.surface(),
                              result.resultLine(),
                              serverRecord,
                              classified.category().name(),
                              attributes,
                              FixtureLoader.JSON.valueToTree(result.invocations()),
                              fixture.id(),
                              fixture.bodySha256(),
                              suiteVersion,
                              Instant.now().toString(),
                              imageDigest));
                    }
                  return rows;
                }));
      for (var future : pending) records.addAll(future.get());
    } finally {
      pending.forEach(f -> f.cancel(true));
      workers.shutdownNow();
    }
    saveResults(root, publicDir, records);
    saveNotRun(publicDir, cells, notRun, suiteVersion);
    if (rerunFlaky)
      for (var cell : cells) {
        var status =
            Stability.of(records.stream().filter(r -> r.cell().equals(cell.key())).toList());
        if (status.stable())
          System.out.println(cell.key() + " " + status.distribution() + " STABLE");
      }
    long harness =
        records.stream()
            .filter(r -> r.category().equals("HARNESS"))
            .map(RunRecord::cell)
            .distinct()
            .count();
    long invalidControls =
        records.stream()
            .filter(
                r -> {
                  String[] parts = r.cell().split("\\|");
                  var c =
                      new Cell(
                          parts[0].split("@", 2)[0],
                          parts[0].split("@", 2)[1],
                          parts[1],
                          parts[2],
                          parts[3],
                          parts[4],
                          jdk,
                          parts[6]);
                  if (!AdapterManifest.isControl(r.fixtureId().split("/", 2)[1].split("@", 2)[0]))
                    return false;
                  var declaration = matrix.manifests().get(c.target()).controlFailure(c);
                  return declaration.isPresent()
                      ? !declaration.get().matches(r.category(), r.adapter())
                      : !r.category().equals("EXECUTED_AS_SENT");
                })
            .count();
    if (invalidControls > 0)
      System.err.println(
          "controls: " + invalidControls + " unexpected repetitions; inspect local evidence");
    System.out.println(
        "run: " + cells.size() + " cells x " + reps + " repetitions, " + harness + " HARNESS");
    verifyRun(records, invalidControls);
  }

  static void verifyRun(List<RunRecord> records, long invalidControls) throws IOException {
    Map<String, List<RunRecord>> groups = new TreeMap<>();
    records.forEach(r -> groups.computeIfAbsent(r.cell(), k -> new ArrayList<>()).add(r));
    boolean flaky = false;
    for (var group : groups.entrySet()) {
      var status = Stability.of(group.getValue());
      if (!status.stable()) {
        flaky = true;
        System.out.println("FLAKY " + group.getKey() + " " + status.distribution());
      }
    }
    if (flaky
        || records.stream().anyMatch(r -> r.category().equals("HARNESS"))
        || invalidControls > 0) throw new IOException("Run failed verification");
  }

  static List<Cell> unstableCells(List<Cell> selected, List<RunRecord> records) {
    Map<String, List<RunRecord>> groups = new HashMap<>();
    records.forEach(r -> groups.computeIfAbsent(r.cell(), k -> new ArrayList<>()).add(r));
    List<Cell> unstable = new ArrayList<>();
    for (var cell : selected) {
      var rows = groups.getOrDefault(cell.key(), List.of());
      if (!Stability.complete(rows))
        throw new IllegalArgumentException(
            "Complete stored run required before --rerun-flaky: " + cell.key());
      if (!Stability.of(rows).stable()) unstable.add(cell);
    }
    return List.copyOf(unstable);
  }

  private static List<RunRecord> readRecords(Path root, Path dir) throws IOException {
    List<RunRecord> rows = new ArrayList<>();
    for (Path source : List.of(dir, localResults(root, dir)))
      if (Files.exists(source))
        try (var files = Files.walk(source, 2)) {
          for (Path file : files.filter(p -> p.toString().endsWith(".jsonl")).toList())
            rows.addAll(ResultStore.readFile(file));
        }
    return List.copyOf(rows);
  }

  public static void compareJdks(Path root, List<Path> directories) throws IOException {
    if (directories.size() != 3)
      throw new IllegalArgumentException("Expected JDK 17, 21 and 25 directories");
    Map<Integer, List<RunRecord>> byJdk = new TreeMap<>();
    var matrix = Matrix.load(root);
    int[] jdks = {17, 21, 25};
    for (int i = 0; i < jdks.length; i++) {
      var rows = readRecords(root, directories.get(i));
      Set<String> expected = new TreeSet<>(), actual = new TreeSet<>();
      matrix.cells("current", jdks[i]).forEach(c -> expected.add(c.key()));
      rows.forEach(r -> actual.add(r.cell()));
      if (!expected.equals(actual))
        throw new IOException(
            "Run directory does not contain the complete current matrix for JDK " + jdks[i]);
      byJdk.put(jdks[i], rows);
    }
    var differences = Stability.differingAcrossJdks(byJdk);
    differences.forEach(System.out::println);
    System.out.println("jdks: " + differences.size() + " differing cells");
  }

  static void saveResults(Path root, Path publicDir, List<RunRecord> records) throws Exception {
    var judgements = Judgements.load(root.resolve("suite/judgements.json"));
    var store = new ResultStore(publicDir, localResults(root, publicDir));
    var previous = ResultStore.previous(root);
    Map<String, List<RunRecord>> groups = new TreeMap<>();
    for (var r : records)
      groups.computeIfAbsent(r.cell().split("\\|", 2)[0], k -> new ArrayList<>()).add(r);
    for (var group : groups.entrySet()) {
      String[] identity = group.getKey().split("@", 2);
      var rows = group.getValue();
      var baseline =
          Baseline.load(
              root.resolve("expectations/baseline"),
              root.resolve(".local/baseline"),
              identity[0],
              identity[1]);
      Set<String> rerun = new HashSet<>();
      rows.forEach(r -> rerun.add(r.cell()));
      var existing = store.read(identity[0], identity[1], true);
      if (defaultResults(root, publicDir)) {
        previous.write(
            identity[0],
            identity[1],
            existing.stream()
                .filter(r -> rerun.contains(r.cell()))
                .toList(),
            Set.of());
      }
      // Disclosure applies to the family after this update, even when a filter ran one cell.
      var combined = new ArrayList<>(rows);
      existing.stream().filter(r -> !rerun.contains(r.cell())).forEach(combined::add);
      Set<String> withheld = new HashSet<>(Check.withheld(combined, judgements, baseline));
      combined.stream()
          .filter(r -> r.category().equals("HARNESS"))
          .forEach(r -> withheld.add(r.cell()));
      store.write(identity[0], identity[1], rows, withheld);
    }
  }

  private static void saveNotRun(
      Path dir, List<Cell> ran, List<ControlFailures.NotRun> notRun, String version)
      throws IOException {
    Path file = dir.resolve("not-run.json");
    Map<String, JsonNode> entries = new TreeMap<>();
    if (Files.exists(file))
      for (var n : FixtureLoader.JSON.readTree(file.toFile()).path("entries")) {
        var entry = FixtureLoader.JSON.treeToValue(n, ControlFailures.NotRun.class);
        entries.put(entry.cell().key(), n);
      }
    ran.forEach(c -> entries.remove(c.key()));
    notRun.forEach(n -> entries.put(n.cell().key(), FixtureLoader.JSON.valueToTree(n)));
    var document =
        FixtureLoader.JSON
            .createObjectNode()
            .put("format", "tcf.not-run/v1")
            .put("suiteVersion", version)
            .put("date", Instant.now().toString());
    var rows = document.putArray("entries");
    entries.values().forEach(rows::add);
    Files.createDirectories(dir);
    Files.writeString(
        file,
        FixtureLoader.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
  }

  public static void check(Path root, Path runDir) throws Exception {
    boolean current = runDir == null;
    if (current) runDir = root.resolve("results");
    var store = new ResultStore(runDir, localResults(root, runDir));
    var stored =
        current
            ? ResultStore.previous(root)
            : new ResultStore(root.resolve("results"), root.resolve(".local/results"));
    Set<String> identities = new TreeSet<>();
    for (Path dir : List.of(runDir, localResults(root, runDir)))
      if (Files.exists(dir))
        try (var files = Files.walk(dir)) {
          for (Path p : files.filter(f -> f.toString().endsWith(".jsonl")).toList())
            identities.add(
                p.getParent().getFileName()
                    + "@"
                    + p.getFileName().toString().replaceFirst("\\.jsonl$", ""));
        }
    if (identities.isEmpty()) throw new IOException("No run records to check");
    var judgements = Judgements.load(root.resolve("suite/judgements.json"));
    int count = 0;
    for (String identity : identities) {
      String[] parts = identity.split("@", 2);
      var baseline =
          Baseline.load(
              root.resolve("expectations/baseline"),
              root.resolve(".local/baseline"),
              parts[0],
              parts[1]);
      for (var f :
          Check.check(
              store.read(parts[0], parts[1], true),
              stored.read(parts[0], parts[1], true),
              judgements,
              baseline)) {
        System.out.println(f.kind() + " " + f.cell() + " " + f.detail());
        count++;
      }
    }
    if (count > 0) throw new IOException("Check found differences");
  }
}
