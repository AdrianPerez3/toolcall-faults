package toolcallfaults.runner.lint;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;
import toolcallfaults.runner.oracle.Category;

public final class ManifestLint {
  private static final Set<String> UPSTREAM_STATUSES =
      Set.of(
          "reported",
          "confirmed",
          "fix-merged",
          "released",
          "wontfix",
          "disputed",
          "documented-behavior");

  private ManifestLint() {}

  public static List<Problem> check(Path root) throws IOException {
    List<Problem> problems = new ArrayList<>();
    Path adapters = root.resolve("adapters");
    if (Files.isDirectory(adapters))
      try (var dirs = Files.list(adapters)) {
        for (Path dir : dirs.sorted().toList()) {
          Path file = dir.resolve("adapter.json");
          if (!Files.exists(file)) continue;
          JsonNode n;
          try {
            n = FixtureLoader.JSON.readTree(file.toFile());
            if (n == null || !n.isObject()) throw new IOException("Expected object");
          } catch (IOException e) {
            problems.add(new Problem(file.toString(), "$", "invalid JSON"));
            continue;
          }
          if (!n.path("format").asText().equals("tcf.adapter/v1"))
            problems.add(new Problem(file.toString(), "$.format", "expected tcf.adapter/v1"));
          for (String field : List.of("target", "version_property", "main_class"))
            if (n.path(field).asText().isBlank())
              problems.add(new Problem(file.toString(), "$." + field, "required nonempty string"));
          for (String field :
              List.of("coordinates", "surfaces", "typed_error_prefixes", "config_notes", "docs"))
            if (!n.path(field).isArray() || n.path(field).isEmpty())
              problems.add(new Problem(file.toString(), "$." + field, "required nonempty array"));
          if (!n.path("modules").isObject() || n.path("modules").isEmpty())
            problems.add(new Problem(file.toString(), "$.modules", "required module map"));
          if (!n.path("unsupported").isArray())
            problems.add(new Problem(file.toString(), "$.unsupported", "required array"));
          int i = 0;
          for (JsonNode entry : n.path("unsupported")) {
            for (String f : List.of("scenario", "wire", "surface", "reason", "docs"))
              if (entry.path(f).isMissingNode()
                  || entry.path(f).isNull()
                  || (entry.path(f).isTextual() && entry.path(f).asText().isBlank())
                  || entry.path(f).isArray() && entry.path(f).isEmpty())
                problems.add(
                    new Problem(
                        file.toString(),
                        "$.unsupported[" + i + "]." + f,
                        "required documentation"));
            i++;
          }
          controlFailures(file, n, problems);
        }
      }
    for (String dir : List.of("expectations", ".local/expectations")) {
      Path anchors = root.resolve(dir).resolve("anchors.json");
      if (!Files.exists(anchors)) continue;
      JsonNode n;
      try {
        n = FixtureLoader.JSON.readTree(anchors.toFile());
        if (n == null || !n.isObject()) throw new IOException("Expected object");
      } catch (IOException e) {
        problems.add(new Problem(anchors.toString(), "$", "invalid JSON object"));
        return List.copyOf(problems);
      }
      if (!n.path("entries").isArray())
        problems.add(new Problem(anchors.toString(), "$.entries", "required array"));
      if (!n.path("format").asText().equals("tcf.anchors/v1"))
        problems.add(new Problem(anchors.toString(), "$.format", "expected tcf.anchors/v1"));
      int i = 0;
      for (JsonNode entry : n.path("entries")) {
        try {
          toolcallfaults.runner.selftest.AnchorExpectation.validate(entry.path("expect"));
        } catch (IOException e) {
          problems.add(new Problem(anchors.toString(), "$.entries[" + i + "].expect", e.getMessage()));
        }
        String id = entry.path("fixture").asText();
        Path base = root.resolve("suite/fixtures").toAbsolutePath().normalize(),
            file = base.resolve(id + ".json").normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file))
          problems.add(
              new Problem(
                  anchors.toString(), "$.entries[" + i + "].fixture", "unknown fixture " + id));
        else if (dir.equals("expectations") && anchorIsWithheld(root, entry))
          problems.add(
              new Problem(
                  anchors.toString(),
                  "$.entries[" + i + "]",
                  "anchor of a family with an unfiled observation belongs in"
                      + " .local/expectations/anchors.json: "
                      + id));
        i++;
      }
    }
    predictionCommitment(root, problems);
    judgementFiles(root, problems);
    baselinesAndResults(root, problems, upstream(root, problems));
    matrix(root, problems);
    currentVersionsHaveRows(root, problems);
    return List.copyOf(problems);
  }

  /**
   * A target whose rows describe a version the matrix no longer names, and whose current version
   * has none, was bumped without being measured: a run of the published cells would skip it.
   */
  private static void currentVersionsHaveRows(Path root, List<Problem> problems) {
    Path file = root.resolve("matrix.json");
    if (!Files.exists(file)) return;
    try {
      var targets = FixtureLoader.JSON.readTree(file.toFile()).path("targets").fields();
      while (targets.hasNext()) {
        var target = targets.next();
        Set<String> named = new HashSet<>();
        String current = null;
        for (String role : List.of("current", "control", "next")) {
          JsonNode node = target.getValue().path("versions").path(role);
          if (node.isMissingNode() || node.isNull()) continue;
          String version = node.isTextual() ? node.asText() : node.path("version").asText();
          if (version.equals("@pom"))
            version =
                toolcallfaults.runner.cell.AdapterBuild.defaultVersion(
                    root.resolve("adapters/" + target.getKey()));
          named.add(version);
          if (role.equals("current")) current = version;
        }
        Set<String> measured = new TreeSet<>();
        for (String directory : List.of("results", ".local/results")) {
          Path dir = root.resolve(directory).resolve(target.getKey());
          if (!Files.isDirectory(dir)) continue;
          try (var files = Files.list(dir)) {
            for (Path rows : files.filter(p -> p.toString().endsWith(".jsonl")).toList())
              if (!toolcallfaults.runner.results.ResultStore.readFile(rows).isEmpty())
                measured.add(rows.getFileName().toString().replaceFirst("\\.jsonl$", ""));
          }
        }
        if (current == null || measured.contains(current)) continue;
        measured.removeAll(named);
        if (!measured.isEmpty())
          problems.add(
              new Problem(
                  file.toString(),
                  "$.targets." + target.getKey() + ".versions.current",
                  target.getKey()
                      + " has rows for "
                      + String.join(", ", measured)
                      + " and none for its current version "
                      + current));
      }
    } catch (Exception e) {
      problems.add(new Problem(file.toString(), "$.targets", String.valueOf(e.getMessage())));
    }
  }

  private static void matrix(Path root, List<Problem> problems) {
    Path file = root.resolve("matrix.json");
    if (!Files.exists(file)) return;
    try {
      var matrix = toolcallfaults.runner.matrix.Matrix.load(root);
      var n = matrix.config();
      if (n.path("repetitions").asInt() != 3)
        problems.add(new Problem(file.toString(), "$.repetitions", "expected 3 repetitions"));
      if (n.path("timeouts").path("t_ms").asLong() <= 0
          || n.path("timeouts").path("w_ms").asLong() <= n.path("timeouts").path("t_ms").asLong())
        problems.add(
            new Problem(
                file.toString(), "$.timeouts", "positive timeout and larger watchdog required"));
      var cells = matrix.cells("current", 21);
      if (cells.isEmpty())
        problems.add(new Problem(file.toString(), "$.targets", "empty current matrix"));
      Set<String> keys = new HashSet<>();
      for (var cell : cells)
        if (!keys.add(cell.key()))
          problems.add(new Problem(file.toString(), "$.targets", "duplicate cell " + cell.key()));
    } catch (Exception e) {
      problems.add(new Problem(file.toString(), "$", e.getMessage()));
    }
  }

  private static List<JsonNode> upstream(Path root, List<Problem> problems) {
    Path file = root.resolve("results/upstream.json");
    if (!Files.exists(file)) return List.of();
    List<JsonNode> rows = new ArrayList<>();
    try {
      var document = FixtureLoader.JSON.readTree(file.toFile());
      if (document == null || !document.isArray()) throw new IOException("expected upstream list");
      int i = 0;
      for (var row : document) {
        String path = "$[" + i++ + "]";
        rows.add(row);
        if (!row.path("library").isTextual() || row.path("library").asText().isBlank())
          problems.add(new Problem(file.toString(), path + ".library", "required library"));
        for (String field : List.of("scenarios", "providers")) {
          var values = row.path(field);
          boolean valid = values.isArray() && !values.isEmpty();
          for (var value : values) valid &= value.isTextual() && !value.asText().isBlank();
          if (!valid)
            problems.add(
                new Problem(file.toString(), path + "." + field, "required nonempty string array"));
        }
        if (!UPSTREAM_STATUSES.contains(row.path("status").asText()))
          problems.add(new Problem(file.toString(), path + ".status", "unknown upstream status"));
        if (row.path("status").asText().equals("released")
            && (!row.path("fixed_in").isTextual() || row.path("fixed_in").asText().isBlank()))
          problems.add(
              new Problem(
                  file.toString(), path + ".fixed_in", "released status requires fixed_in"));
        boolean linked = false;
        for (String field : List.of("issue", "pr")) {
          var link = row.path(field);
          if (link.isMissingNode() || link.isNull()) continue;
          if (link.isTextual() && link.asText().matches("https?://[^/\\s]+/\\S+")) linked = true;
          else
            problems.add(
                new Problem(file.toString(), path + "." + field, "expected issue or PR URL"));
        }
        if (!linked)
          problems.add(
              new Problem(file.toString(), path, "upstream row requires an issue or PR link"));
        try {
          java.time.LocalDate.parse(row.path("opened").asText());
        } catch (java.time.format.DateTimeParseException e) {
          problems.add(new Problem(file.toString(), path + ".opened", "expected opened date"));
        }
      }
    } catch (IOException e) {
      problems.add(new Problem(file.toString(), "$", e.getMessage()));
    }
    return List.copyOf(rows);
  }

  private static boolean coveredByUpstream(JsonNode entry, List<JsonNode> upstream) {
    String[] cell = entry.path("cell").asText().split("\\|", -1);
    if (cell.length != 7 || !cell[3].contains("/")) return false;
    String[] fixture = cell[3].split("/", 2);
    String issue = entry.path("issue").asText();
    for (var row : upstream)
      if ((issue.equals(row.path("issue").asText()) || issue.equals(row.path("pr").asText()))
          && row.path("library").asText().equals(cell[0].split("@", 2)[0])
          && contains(row.path("providers"), fixture[0])
          && contains(row.path("scenarios"), fixture[1].split("@", 2)[0])) return true;
    return false;
  }

  private static void baselinesAndResults(
      Path root, List<Problem> problems, List<JsonNode> upstream) throws IOException {
    for (String directory : List.of("expectations/baseline", ".local/baseline")) {
      Path dir = root.resolve(directory);
      if (!Files.isDirectory(dir)) continue;
      try (var files = Files.list(dir)) {
        for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList())
          try {
            var n = FixtureLoader.JSON.readTree(file.toFile());
            if (n == null
                || !n.path("format").asText().equals("tcf.baseline/v1")
                || !n.path("entries").isArray()) throw new IOException("invalid baseline");
            int i = 0;
            Set<String> keys = new HashSet<>();
            for (var entry : n.path("entries")) {
              String path = "$.entries[" + i++ + "]",
                  disclosure = entry.path("disclosure").asText();
              if (entry.path("cell").asText().isBlank() || !keys.add(entry.path("cell").asText()))
                problems.add(
                    new Problem(file.toString(), path + ".cell", "missing or duplicate cell"));
              categories(
                  file,
                  path + ".category",
                  FixtureLoader.JSON.createArrayNode().add(entry.path("category")),
                  false,
                  problems);
              if (!Set.of("private", "unfiled", "filed").contains(disclosure)
                  || directory.equals("expectations/baseline") && !disclosure.equals("filed"))
                problems.add(
                    new Problem(
                        file.toString(),
                        path + ".disclosure",
                        "public baselines require filed disclosure"));
              if (disclosure.equals("filed")) {
                if (!coveredByUpstream(entry, upstream))
                  problems.add(
                      new Problem(
                          file.toString(),
                          path + ".issue",
                          "filed baseline needs a matching upstream row for its library, scenario"
                              + " and provider"));
                String issue = entry.path("issue").asText();
                if (!issue.matches("https?://[^/\\s]+/.+"))
                  problems.add(
                      new Problem(
                          file.toString(),
                          path + ".issue",
                          "filed entry requires issue or PR link"));
                try {
                  java.time.LocalDate.parse(entry.path("filedOn").asText());
                } catch (java.time.format.DateTimeParseException e) {
                  problems.add(
                      new Problem(file.toString(), path + ".filedOn", "filed entry requires date"));
                }
                if (!UPSTREAM_STATUSES.contains(entry.path("status").asText()))
                  problems.add(
                      new Problem(file.toString(), path + ".status", "unknown upstream status"));
              }
            }
          } catch (Exception e) {
            problems.add(new Problem(file.toString(), "$", e.getMessage()));
          }
      }
    }
    Path results = root.resolve("results"), judgements = root.resolve("suite/judgements.json");
    if (!Files.isDirectory(results) || !Files.exists(judgements)) return;
    var judge = toolcallfaults.runner.judge.Judgements.load(judgements);
    var store = new toolcallfaults.runner.results.ResultStore(results, root.resolve(".local/results"));
    Map<String, List<String>> fixtureHashes = new HashMap<>();
    try (var files = Files.walk(results)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList())
        try {
          String target = file.getParent().getFileName().toString(),
              version = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
          var baseline =
              toolcallfaults.runner.judge.Baseline.load(
                  root.resolve("expectations/baseline"),
                  root.resolve(".local/baseline"),
                  target,
                  version);
          var rows = toolcallfaults.runner.results.ResultStore.readFile(file);
          Set<String> direct = new HashSet<>();
          for (var row : rows) {
            if (row.category().equals("HARNESS"))
              problems.add(
                  new Problem(
                      file.toString(), "$", "HARNESS row is not a publishable observation"));
            if (judge
                    .judge(
                        row.fixtureId(),
                        row.surface(),
                        Category.valueOf(row.category()),
                        row.attributes())
                    .unsupported()
                && baseline
                    .entry(row.cell())
                    .filter(
                        e ->
                            "filed".equals(e.disclosure())
                                && row.category().equals(e.category()))
                    .isEmpty())
            {
              direct.add(row.cell());
              problems.add(
                  new Problem(
                      file.toString(),
                      "$",
                      "unsupported row needs a filed baseline: " + row.cell()));
            }
          }
          // Known private rows also identify a withheld family before its baseline is recorded.
          var withheld = toolcallfaults.runner.judge.Check.withheld(
              store.read(target, version, true), judge, baseline);
          for (String cell : rows.stream().map(r -> r.cell()).distinct().toList())
            if (withheld.contains(cell) && !direct.contains(cell))
              problems.add(
                  new Problem(
                      file.toString(),
                      "$",
                      "row of a family with an unfiled observation must not be tracked: "
                          + cell));
          for (var row : rows) {
            if (!fixtureHashes.containsKey(row.fixtureId()))
              fixtureHashes.put(row.fixtureId(), toolcallfaults.runner.fixture.ControlFixtures
                  .load(root, row.fixtureId()).bodySha256());
            if (!fixtureHashes.get(row.fixtureId()).equals(row.bodySha256()))
              problems.add(new Problem(file.toString(), "$", "record body hashes differ from fixture "
                  + row.fixtureId()));
          }
        } catch (Exception e) {
          problems.add(new Problem(file.toString(), "$", e.getMessage()));
        }
    }
  }

  /** Predictions and their errata stay private until the behaviours they name are reported. */
  private static Path privateFirst(Path root, String name) {
    Path local = root.resolve(".local/expectations").resolve(name);
    return Files.exists(local) ? local : root.resolve("expectations").resolve(name);
  }

  /** An anchor is private while its family has an unfiled baseline entry and no filed one. */
  private static boolean anchorIsWithheld(Path root, JsonNode anchor) throws IOException {
    String target = anchor.path("target").asText(), version = anchor.path("version").asText();
    if (!target.matches("[a-z0-9-]+") || !version.matches("[a-zA-Z0-9_.-]+")) return false;
    var baseline =
        toolcallfaults.runner.judge.Baseline.load(
            root.resolve("expectations/baseline"), root.resolve(".local/baseline"), target, version);
    String cellPrefix =
        target
            + "@"
            + version
            + "|"
            + anchor.path("module").asText()
            + "|"
            + anchor.path("surface").asText()
            + "|"
            + anchor.path("fixture").asText()
            + "|"
            + anchor.path("tool").asText()
            + "|";
    String family = toolcallfaults.runner.judge.Check.family(cellPrefix + "jdk0|x");
    boolean unfiledInFamily = false, filedForCell = false;
    for (var e : baseline.entries()) {
      boolean filed = "filed".equals(e.disclosure());
      if (!filed && toolcallfaults.runner.judge.Check.family(e.cell()).equals(family))
        unfiledInFamily = true;
      if (filed && e.cell().startsWith(cellPrefix)) filedForCell = true;
    }
    return unfiledInFamily && !filedForCell;
  }

  /**
   * The frozen predictions are published as a hash until they can be published in full; the
   * private file, where present, must be the committed bytes.
   */
  private static void predictionCommitment(Path root, List<Problem> problems) throws IOException {
    Path commitment = root.resolve("expectations/predictions.commitment.json"),
        predictions = root.resolve(".local/expectations/predictions.json");
    if (!Files.exists(commitment)) {
      if (Files.exists(predictions))
        problems.add(
            new Problem(predictions.toString(), "$", "predictions have no public commitment"));
      return;
    }
    JsonNode n;
    try {
      n = FixtureLoader.JSON.readTree(commitment.toFile());
    } catch (IOException e) {
      n = null;
    }
    if (n == null
        || !n.path("format").asText().equals("tcf.prediction-commitment/v1")
        || !n.path("algorithm").asText().equals("SHA-256")
        || !n.path("sha256").asText().matches("[0-9a-f]{64}")
        || !n.path("bytes").canConvertToLong()) {
      problems.add(new Problem(commitment.toString(), "$", "invalid prediction commitment"));
      return;
    }
    if (!Files.exists(predictions)) return;
    byte[] bytes = Files.readAllBytes(predictions);
    String sha;
    try {
      sha =
          HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    if (!sha.equals(n.path("sha256").asText()) || bytes.length != n.path("bytes").asLong())
      problems.add(
          new Problem(predictions.toString(), "$", "predictions differ from the committed hash"));
  }

  private static void judgementFiles(Path root, List<Problem> problems) throws IOException {
    for (String name : List.of("suite/judgements.json", "expectations/predictions.json")) {
      Path file = name.contains("predictions") ? privateFirst(root, "predictions.json") : root.resolve(name);
      if (!Files.exists(file)) continue;
      boolean predictions = name.contains("predictions");
      JsonNode n;
      try {
        n = FixtureLoader.JSON.readTree(file.toFile());
        if (n == null || !n.isObject()) throw new IOException();
      } catch (IOException e) {
        problems.add(new Problem(file.toString(), "$", "invalid JSON object"));
        continue;
      }
      String format = predictions ? "tcf.predictions/v1" : "tcf.judgements/v1";
      if (!n.path("format").asText().equals(format))
        problems.add(new Problem(file.toString(), "$.format", "expected " + format));
      if (!n.path("entries").isArray())
        problems.add(new Problem(file.toString(), "$.entries", "required array"));
      if (predictions) predictionModules(root, file, n.path("entries"), problems);
      if (!predictions) {
        for (String key :
            List.of(
                "N-AN2", "N-AN3", "N-AN4", "N-AN6", "N-AN7", "N-AN8", "N-AN10", "N-AN11", "N-OA1",
                "N-OA3", "N-OA6", "N-OA8", "N-BR4")) {
          for (String field : List.of("text", "url"))
            if (n.path("register").path(key).path(field).asText().isBlank())
              problems.add(
                  new Problem(
                      file.toString(),
                      "$.register." + key + "." + field,
                      "required register source"));
          JsonNode applicability = n.path("register").path(key).path("applicability");
          if (!applicability.isMissingNode()
              && !Set.of("context-only", "streaming").contains(applicability.asText()))
            problems.add(new Problem(file.toString(), "$.register." + key + ".applicability",
                "expected context-only or streaming"));
          JsonNode wires = n.path("register").path(key).path("wires");
          boolean known = wires.isArray() && !wires.isEmpty();
          for (JsonNode w : wires)
            if (!Set.of("openai-chat", "anthropic").contains(w.asText())) known = false;
          if (!known)
            problems.add(
                new Problem(
                    file.toString(),
                    "$.register." + key + ".wires",
                    "register source needs the wires it is a basis for"));
        }
      }
      int i = 0;
      for (JsonNode entry : n.path("entries")) {
        String path = "$.entries[" + i++ + "]";
        List<String> fields =
            predictions
                ? List.of(
                    "scenario",
                    "wire",
                    "variant",
                    "target",
                    "module",
                    "surface",
                    "spec_text",
                    "label",
                    "source")
                : List.of("scenario", "wire", "variant", "level", "basis");
        for (String field : fields)
          if (!entry.path(field).isTextual() || entry.path(field).asText().isBlank())
            problems.add(
                new Problem(file.toString(), path + "." + field, "required nonempty string"));
        if (predictions) {
          if (!Set.of("observed", "predicted").contains(entry.path("label").asText()))
            problems.add(
                new Problem(file.toString(), path + ".label", "expected observed or predicted"));
          categories(file, path + ".predicted", entry.path("predicted"), false, problems);
        } else {
          if (!Set.of("required", "recommended", "record-only")
              .contains(entry.path("level").asText()))
            problems.add(new Problem(file.toString(), path + ".level", "unknown level"));
          if (!entry.path("keys").isArray())
            problems.add(new Problem(file.toString(), path + ".keys", "required array"));
          String criterion = entry.path("criterion").asText("");
          if (entry.has("criterion")
              && (!Set.of("provider", "suite", "target-documentation", "control")
                      .contains(criterion)
                  || criterion.equals("provider") && entry.path("keys").isEmpty()))
            problems.add(
                new Problem(
                    file.toString(),
                    path + ".criterion",
                    "criterion is provider (with keys), suite, target-documentation or control"));
          for (JsonNode key : entry.path("keys"))
            if (!n.path("register").has(key.asText()))
              problems.add(
                  new Problem(
                      file.toString(), path + ".keys", "undefined register key " + key.asText()));
          categories(file, path + ".flag", entry.path("flag"), true, problems);
          categories(file, path + ".defensible", entry.path("defensible"), false, problems);
          if (!entry.path("unsupported").isArray())
            problems.add(new Problem(file.toString(), path + ".unsupported", "required array"));
          for (JsonNode rule : entry.path("unsupported")) {
            categories(
                file,
                path + ".unsupported",
                FixtureLoader.JSON.createArrayNode().add(rule.path("category")),
                true,
                problems);
            if (!Set.of(
                    "always",
                    "no_limit_signal",
                    "two_terminal_signals",
                    "first_call_executed_and_turn_clean",
                    "beyond_configured_timeout")
                .contains(rule.path("when").asText()))
              problems.add(
                  new Problem(file.toString(), path + ".unsupported", "unknown condition"));
          }
        }
      }
    }
  }

  private static void predictionModules(
      Path root, Path file, JsonNode entries, List<Problem> problems) throws IOException {
    Path errataFile = privateFirst(root, "prediction-errata.json");
    Map<String, String> aliases = new HashMap<>();
    if (Files.exists(errataFile)) {
      var n = FixtureLoader.JSON.readTree(errataFile.toFile());
      if (n == null
          || !n.path("format").asText().equals("tcf.prediction-errata/v1")
          || !n.path("entries").isArray())
        problems.add(new Problem(errataFile.toString(), "$", "invalid selector errata"));
      else
        for (var e : n.path("entries")) {
          Set<String> fields = new HashSet<>();
          e.fieldNames().forEachRemaining(fields::add);
          if (!fields.equals(Set.of("target", "module", "correct_module", "basis"))
              || e.path("basis").asText().isBlank())
            problems.add(
                new Problem(
                    errataFile.toString(),
                    "$.entries",
                    "errata may change only the module selector and require a basis"));
          String key = e.path("target").asText() + "|" + e.path("module").asText();
          if (aliases.putIfAbsent(key, e.path("correct_module").asText()) != null)
            problems.add(
                new Problem(errataFile.toString(), "$.entries", "duplicate selector erratum"));
        }
    }
    Set<String> used = new HashSet<>();
    int i = 0;
    for (var entry : entries) {
      String path = "$.entries[" + i++ + "]",
          target = entry.path("target").asText(),
          module = entry.path("module").asText();
      String key = target + "|" + module;
      Path manifest = root.resolve("adapters/" + target + "/adapter.json");
      if (!Files.exists(manifest)) {
        problems.add(
            new Problem(file.toString(), path + ".target", "unknown prediction target " + target));
        continue;
      }
      var modules = FixtureLoader.JSON.readTree(manifest.toFile()).path("modules");
      if (aliases.containsKey(key)) {
        used.add(key);
        if (modules.has(module))
          problems.add(
              new Problem(
                  errataFile.toString(),
                  "$.entries",
                  "erratum cannot replace an existing module selector"));
        module = aliases.get(key);
      }
      if (!modules.has(module))
        problems.add(
            new Problem(file.toString(), path + ".module", "unknown prediction module " + module));
      else if (!entry.path("wire").asText().equals("*")
          && !entry.path("wire").equals(modules.path(module).path("wire")))
        problems.add(
            new Problem(file.toString(), path + ".wire", "prediction module uses another wire"));
    }
    if (!used.containsAll(aliases.keySet()))
      problems.add(new Problem(errataFile.toString(), "$.entries", "unused selector erratum"));
  }

  private static void categories(
      Path file, String path, JsonNode values, boolean wildcard, List<Problem> problems) {
    if (!values.isArray()) {
      problems.add(new Problem(file.toString(), path, "required array"));
      return;
    }
    for (JsonNode value : values) {
      if (wildcard && value.asText().equals("*")) continue;
      try {
        Category.valueOf(value.asText());
      } catch (IllegalArgumentException e) {
        problems.add(new Problem(file.toString(), path, "unknown category " + value.asText()));
      }
    }
  }

  private static void controlFailures(Path file, JsonNode manifest, List<Problem> problems) {
    JsonNode failures = manifest.path("control_failures");
    if (failures.isMissingNode()) return;
    if (!failures.isArray()) {
      problems.add(new Problem(file.toString(), "$.control_failures", "expected array"));
      return;
    }
    Set<String> selectors = new HashSet<>();
    int index = 0;
    for (JsonNode entry : failures) {
      String path = "$.control_failures[" + index++ + "]";
      for (String key : List.of("module", "surface", "wire", "tool", "category", "error_class"))
        if (!entry.path(key).isTextual() || entry.path(key).asText().isBlank())
          problems.add(new Problem(file.toString(), path + "." + key, "required nonempty string"));
      if (entry.has("versions")) {
        var versions = entry.path("versions");
        Set<String> values = new HashSet<>();
        boolean valid = versions.isArray() && !versions.isEmpty();
        for (var version : versions)
          valid &=
              version.isTextual()
                  && version.asText().matches("[a-zA-Z0-9_.-]+")
                  && values.add(version.asText());
        if (!valid)
          problems.add(
              new Problem(
                  file.toString(), path + ".versions", "nonempty unique version list required"));
      }
      String basis = entry.path("basis").asText();
      try {
        var uri = java.net.URI.create(basis);
        if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null)
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException | NullPointerException e) {
        problems.add(new Problem(file.toString(), path + ".basis", "required basis link"));
      }
      String module = entry.path("module").asText(), surface = entry.path("surface").asText();
      JsonNode configuration = manifest.path("modules").path(module);
      if (configuration.isMissingNode() || !configuration.path("wire").equals(entry.path("wire")))
        problems.add(new Problem(file.toString(), path + ".module", "unknown module or wire"));
      if (!contains(manifest.path("surfaces"), surface))
        problems.add(new Problem(file.toString(), path + ".surface", "undeclared surface"));
      try {
        Category category = Category.valueOf(entry.path("category").asText());
        if (Set.of(Category.EXECUTED_AS_SENT, Category.HARNESS, Category.UNSUPPORTED)
            .contains(category)) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        problems.add(
            new Problem(file.toString(), path + ".category", "expected library failure category"));
      }
      String selector =
          module
              + "|"
              + surface
              + "|"
              + entry.path("wire").asText()
              + "|"
              + entry.path("tool").asText();
      if (!selectors.add(selector))
        problems.add(new Problem(file.toString(), path, "duplicate control failure selector"));
    }
  }

  private static boolean contains(JsonNode array, String value) {
    for (JsonNode item : array) if (item.asText().equals(value)) return true;
    return false;
  }
}
