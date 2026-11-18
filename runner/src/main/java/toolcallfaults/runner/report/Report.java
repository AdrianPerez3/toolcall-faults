package toolcallfaults.runner.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.runner.judge.*;
import toolcallfaults.runner.matrix.ControlFailures;
import toolcallfaults.runner.oracle.Category;
import toolcallfaults.runner.results.*;

public final class Report {
  private Report() {}

  private record View(
      String key, List<RunRecord> rows, Fixture fixture, Verdict verdict, Entry entry) {}

  public static void generate(Path root, Path siteDir, boolean includeWithheld, LocalDate today)
      throws Exception {
    var json = FixtureLoader.JSON;
    var config = json.readTree(root.resolve("matrix.json").toFile());
    var judgements = Judgements.load(root.resolve("suite/judgements.json"));
    JsonNode judgementDocument =
        FixtureLoader.JSON.readTree(root.resolve("suite/judgements.json").toFile());
    Map<String, JsonNode> scenarios = new TreeMap<>(), upstream = new HashMap<>();
    try (var files = Files.walk(root.resolve("suite/scenarios"))) {
      for (var path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
        var n = json.readTree(path.toFile());
        scenarios.put(n.path("id").asText(), n);
      }
    }
    Path upstreamFile = root.resolve("results/upstream.json");
    if (Files.exists(upstreamFile))
      for (var row : json.readTree(upstreamFile.toFile()))
        for (String field : List.of("issue", "pr"))
          if (row.path(field).isTextual()) upstream.put(row.path(field).asText(), row);
    var store = new ResultStore(root.resolve("results"), root.resolve(".local/results"));
    Set<String> identities = new TreeSet<>(), targets = new TreeSet<>();
    config.path("targets").fieldNames().forEachRemaining(targets::add);
    for (String directory :
        includeWithheld ? List.of("results", ".local/results") : List.of("results")) {
      Path dir = root.resolve(directory);
      if (Files.exists(dir))
        try (var files = Files.walk(dir)) {
          for (var path : files.filter(p -> p.toString().endsWith(".jsonl")).toList())
            identities.add(
                path.getParent().getFileName()
                    + "@"
                    + path.getFileName().toString().replaceFirst("\\.jsonl$", ""));
        }
    }
    List<View> views = new ArrayList<>();
    List<RunRecord> all = new ArrayList<>();
    for (String identity : identities) {
      String[] parts = identity.split("@", 2);
      targets.add(parts[0]);
      var baseline =
          Baseline.load(
              root.resolve("expectations/baseline"),
              root.resolve(".local/baseline"),
              parts[0],
              parts[1]);
      Map<String, List<RunRecord>> groups = new TreeMap<>();
      var rowsOfVersion = store.read(parts[0], parts[1], includeWithheld);
      // Private records provide disclosure context for their public siblings. Only rowsOfVersion
      // can be rendered. This also protects a newly discovered family before it has a baseline.
      Set<String> withheldCells = includeWithheld ? Set.of()
          : Check.withheld(store.read(parts[0], parts[1], true), judgements, baseline);
      for (var row : rowsOfVersion) {
        if (withheldCells.contains(row.cell())) continue;
        all.add(row);
        groups.computeIfAbsent(row.cell(), k -> new ArrayList<>()).add(row);
      }
      for (var group : groups.entrySet()) {
        var rows = group.getValue();
        rows.sort(Comparator.comparingInt(RunRecord::rep));
        var first = rows.get(0);
        var verdict =
            judgements.judge(
                first.fixtureId(),
                first.surface(),
                Category.valueOf(first.category()),
                first.attributes());
        for (var row : rows) {
          var candidate =
              judgements.judge(
                  row.fixtureId(),
                  row.surface(),
                  Category.valueOf(row.category()),
                  row.attributes());
          if (candidate.unsupported()) verdict = candidate;
        }
        var entry = baseline.entry(group.getKey()).orElse(null);
        if (!includeWithheld
            && (rows.stream().anyMatch(r -> r.category().equals("HARNESS"))
                || verdict.unsupported()
                    && !publishable(entry, config.path("window_days").asInt(14), today)))
          continue;
        views.add(
            new View(
                group.getKey(),
                List.copyOf(rows),
                ControlFixtures.load(root, first.fixtureId()),
                verdict,
                entry));
      }
    }
    views.sort(
        Comparator.comparing((View v) -> v.fixture().scenario())
            .thenComparing(v -> v.rows().get(0).surface())
            .thenComparing(View::key));
    List<ControlFailures.NotRun> notRun = new ArrayList<>();
    Path notRunFile = root.resolve("results/not-run.json");
    if (Files.exists(notRunFile))
      for (var n : json.readTree(notRunFile.toFile()).path("entries")) {
        var row = json.treeToValue(n, ControlFailures.NotRun.class);
        notRun.add(row);
        targets.add(row.cell().target());
      }
    notRun.sort(Comparator.comparing(n -> n.cell().key()));
    String banner = banner(root, all);
    String dispute =
        config.path("repository").isTextual()
            ? config.path("repository").asText().replaceFirst("/$", "") + "/issues/new"
            : "../../docs/disputing-a-cell.md";
    Files.createDirectories(siteDir.resolve("targets"));
    Files.createDirectories(siteDir.resolve("scenarios"));
    // Regeneration removes obsolete generated pages, including newly withheld cells.
    for (String directory : List.of("targets", "scenarios"))
      try (var files = Files.list(siteDir.resolve(directory))) {
        for (var path : files.filter(p -> p.toString().endsWith(".md")).toList())
          Files.delete(path);
      }
    StringBuilder index =
        new StringBuilder(
                "# toolcall-faults\n\n"
                    + "what JVM LLM clients do when a streamed tool call arrives damaged\n\n")
            .append(banner)
            .append("\n## Targets\n\n");
    for (String target : targets) {
      index
          .append("- [")
          .append(target)
          .append("](")
          .append("targets/")
          .append(target)
          .append(".md)\n");
      var selected = views.stream().filter(v -> v.key().startsWith(target + "@")).toList();
      var skipped = notRun.stream().filter(n -> n.cell().target().equals(target)).toList();
      write(
          siteDir.resolve("targets/" + target + ".md"),
          page(target, banner, selected, skipped, scenarios, upstream, dispute));
    }
    index.append("\n## Scenarios\n\n");
    for (var scenario : scenarios.entrySet()) {
      String id = scenario.getKey();
      index
          .append("- [")
          .append(escape(scenario.getValue().path("title").asText(id)))
          .append("](scenarios/")
          .append(id)
          .append(".md)\n");
      var selected = views.stream().filter(v -> v.fixture().scenario().equals(id)).toList();
      var skipped =
          notRun.stream()
              .filter(n -> n.cell().fixtureId().split("/", 2)[1].split("@", 2)[0].equals(id))
              .toList();
      write(
          siteDir.resolve("scenarios/" + id + ".md"),
          page(
                  scenario.getValue().path("title").asText(id),
                  banner,
                  selected,
                  skipped,
                  scenarios,
                  upstream,
                  dispute)
              + scenarioNotes(id, scenario.getValue(), judgementDocument));
    }
    index.append("\n## Judgements\n\n- [Register of cited provider sentences](register.md)\n");
    write(siteDir.resolve("register.md"), register(judgementDocument));
    write(siteDir.resolve("index.md"), index.toString());
    System.out.println(
        "report: " + (2 + targets.size() + scenarios.size()) + " pages written to " + siteDir);
  }

  /** Where the shape was seen, and the rules that judge it: printed once per scenario. */
  private static String scenarioNotes(String id, JsonNode scenario, JsonNode judgements) {
    StringBuilder out = new StringBuilder("\n## Evidence and judgement\n\n");
    out.append("Sighting: ").append(escape(scenario.path("sighting").asText("not stated")));
    out.append(".\n\n");
    for (JsonNode url : scenario.path("evidence"))
      out.append("- ").append(escape(url.asText())).append("\n");
    if (scenario.path("evidence_notes").isTextual())
      out.append("\n").append(escape(scenario.path("evidence_notes").asText())).append("\n");
    for (JsonNode entry : judgements.path("entries")) {
      if (!entry.path("scenario").asText().equals(id)) continue;
      out.append("\nWire `")
          .append(entry.path("wire").asText())
          .append("`, variant `")
          .append(entry.path("variant").asText())
          .append("`: level ")
          .append(entry.path("level").asText())
          .append(". ");
      List<String> rules = new ArrayList<>();
      for (JsonNode rule : entry.path("unsupported"))
        rules.add(
            outcome(rule.path("category").asText())
                + (rule.path("when").asText().equals("always")
                    ? ""
                    : " when " + rule.path("when").asText().replace('_', ' ')));
      out.append("No documentary support: ")
          .append(rules.isEmpty() ? "none listed" : String.join("; ", rules))
          .append(". ");
      List<String> defensible = new ArrayList<>();
      entry.path("defensible").forEach(d -> defensible.add(outcome(d.asText())));
      if (!defensible.isEmpty())
        out.append("Defensible: ").append(String.join(", ", defensible)).append(". ");
      out.append("Criterion: ")
          .append(
              new Verdict(
                      "", false, false, List.of(), List.of(), entry.path("criterion").asText(), "")
                  .criterionLabel())
          .append(". ")
          .append(escape(entry.path("basis").asText()));
      List<String> keys = new ArrayList<>();
      entry.path("keys").forEach(k -> keys.add(k.asText()));
      if (!keys.isEmpty())
        out.append(" Keys: ").append(String.join(", ", keys)).append(" ([register](../register.md)).");
      out.append("\n");
    }
    return out.toString();
  }

  /** A category in plain words, for the rules of a scenario; observations keep the names. */
  private static String outcome(String category) {
    return switch (category) {
      case "*" -> "any outcome";
      case "HARNESS" -> "an invalid run";
      case "EXECUTED_ALTERED" -> "the tool ran with other arguments than a complete call carried";
      case "HANG" -> "no terminal signal before the watchdog";
      case "ERROR_TYPED" -> "an error of the target's own type";
      case "ERROR_UNTYPED" -> "an error of a generic type";
      case "FED_BACK_HIDDEN" -> "the error was sent back to the model and hidden from the caller";
      case "SURFACED" -> "not run, and the caller can tell";
      case "DROPPED" -> "not run, and the caller cannot tell";
      case "EXECUTED_AS_SENT" -> "the tool ran with the arguments as sent";
      case "UNSUPPORTED" -> "the target cannot express the cell";
      default -> category;
    };
  }

  /** Every cited sentence with its source, so that a key in a cell can be resolved. */
  private static String register(JsonNode judgements) {
    StringBuilder out =
        new StringBuilder(
            "# Register of cited provider sentences\n\n"
                + "A source applies to cells whose wire, service and request mode it describes."
                + " Other sources are listed as related guidance.\n\n");
    var keys = judgements.path("register").fields();
    while (keys.hasNext()) {
      var key = keys.next();
      List<String> wires = new ArrayList<>();
      key.getValue().path("wires").forEach(w -> wires.add(w.asText()));
      out.append("## ")
          .append(key.getKey())
          .append("\n\n")
          .append(escape(key.getValue().path("text").asText()))
          .append("\n\nSource: ")
          .append(escape(key.getValue().path("url").asText()))
          .append(switch (key.getValue().path("applicability").asText()) {
            case "context-only" -> "; context for: ";
            case "streaming" -> "; basis on streaming calls for: ";
            default -> "; basis on: ";
          })
          .append(String.join(", ", wires))
          .append(".");
      if (key.getValue().path("scope").isTextual())
        out.append(" Scope: ").append(escape(key.getValue().path("scope").asText()));
      out.append("\n\n");
    }
    return out.toString();
  }

  private static boolean publishable(Entry e, int window, LocalDate today) {
    return e != null
        && "filed".equals(e.disclosure())
        && e.issue() != null
        && e.filedOn() != null
        && (e.maintainerAnswered()
            || !today.isBefore(LocalDate.parse(e.filedOn()).plusDays(window)));
  }

  private static String banner(Path root, List<RunRecord> rows) throws Exception {
    Path stamp = root.resolve("results/last-run.json");
    String date = "not recorded", version = "not recorded", observed = "";
    if (Files.exists(stamp)) {
      var n = FixtureLoader.JSON.readTree(stamp.toFile());
      date = n.path("date").asText();
      version = n.path("suite_version").asText();
    } else if (!rows.isEmpty()) {
      var latest = Collections.max(rows, Comparator.comparing(RunRecord::date));
      observed = "; Latest observation: " + latest.date().substring(0, 10);
      version = latest.suiteVersion();
    }
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var files = Files.walk(root.resolve("suite/fixtures"))) {
      for (var p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList())
        for (var hash : FixtureLoader.loadVerified(p).bodySha256())
          digest.update((hash + "\n").getBytes(StandardCharsets.UTF_8));
    }
    return "Last successful run: "
        + escape(date)
        + observed
        + "; suite version: `"
        + escape(version)
        + "`.\n\nFixture version: `"
        + HexFormat.of().formatHex(digest.digest())
        + "`.\n\n"
        + "observations of behaviour on replayed bytes; not a ranking\n\n"
        + "older than 14 days: treat as stale\n";
  }

  private static String page(
      String title,
      String banner,
      List<View> views,
      List<ControlFailures.NotRun> notRun,
      Map<String, JsonNode> scenarios,
      Map<String, JsonNode> upstream,
      String dispute) {
    StringBuilder out =
        new StringBuilder("# ")
            .append(escape(title))
            .append("\n\n")
            .append(banner)
            .append("\n## Cells with an outcome no provider document supports\n\n");
    var judged = views.stream().filter(v -> v.verdict().unsupported()).toList();
    if (judged.isEmpty()) out.append("No eligible observations in this section.\n");
    else {
      out.append(
          "| Scenario | Wire damage | Observation | Category | Basis | Upstream | Reproduce |\n"
              + "|---|---|---|---|---|---|---|\n");
      for (var v : judged) {
        var row = v.rows().get(0);
        out.append("| ")
            .append(table(scenarios.get(v.fixture().scenario()).path("title").asText()))
            .append(" | ")
            .append(table(v.fixture().truth().damage()))
            .append(" | ")
            .append(
                table(
                    String.join(
                        "\n",
                        v.rows().stream()
                            .map(r -> "Repetition " + r.rep() + ": " + observation(r))
                            .toList())))
            .append(" | ")
            .append(
                String.join(" / ", v.rows().stream().map(RunRecord::category).distinct().toList()))
            .append(" | ")
            .append(
                table(
                    v.verdict().basis()
                        + " Criterion: "
                        + v.verdict().criterionLabel()
                        + ". "
                        + v.verdict().citation()))
            .append(" | ")
            .append(table(upstream(v.entry(), upstream)))
            .append(" | `")
            .append(table(command(row)))
            .append("` |\n");
      }
    }
    out.append("\n## Observations\n\n");
    for (var v : views) {
      out.append("### ")
          .append(escape(v.fixture().scenario()))
          .append(" — ")
          .append(v.rows().get(0).surface())
          .append("\n\nCell: `")
          .append(v.key())
          .append("`\n\n");
      out.append("Fixture: `")
          .append(v.fixture().id())
          .append("`; ")
          .append(v.fixture().tier())
          .append(". Wire damage: ")
          .append(escape(v.fixture().truth().damage()))
          .append(".\n\n");
      if (v.verdict().level().equals("record-only")) out.append("record-only\n\n");
      else
        out.append("Basis: ")
            .append(escape(v.verdict().basis()))
            .append(" Criterion: ")
            .append(v.verdict().criterionLabel())
            .append(". ")
            .append(escape(v.verdict().citation()))
            .append("\n\n");
      for (var row : v.rows()) {
        out.append("Repetition ")
            .append(row.rep())
            .append(": `")
            .append(row.category())
            .append("`; ")
            .append(escape(observation(row)))
            .append("\n\n");
        if (row.adapter() != null)
          out.append("Resolved versions: `")
              .append(escape(row.adapter().path("versions").toString()))
              .append("`.\n\n");
        if (row.attributes().has("control_failure_notes"))
          out.append(escape(row.attributes().path("control_failure_notes").asText()))
              .append("\n\n");
      }
      if (v.entry() != null) out.append(escape(upstream(v.entry(), upstream))).append("\n\n");
      out.append("Reproduce: `")
          .append(command(v.rows().get(0)))
          .append("`\n\n[Dispute this cell](")
          .append(dispute)
          .append(")\n\n");
    }
    if (!notRun.isEmpty()) {
      out.append("## Not run\n\n");
      for (var n : notRun)
        out.append("Cell: `")
            .append(n.cell().key())
            .append(
                "`\n\n"
                    + "Not run: a declared control failure on this module and surface prevents"
                    + " interpreting the observation. Basis: ")
            .append(String.join(" ", n.basis()))
            .append("\n\n")
            .append(escape(String.join(" ", n.notes())))
            .append("\n\n[Dispute this cell](")
            .append(dispute)
            .append(")\n\n");
    }
    return out.toString();
  }

  private static String observation(RunRecord row) {
    var adapter = row.adapter();
    String result =
        row.surface().startsWith("model-")
            ? "handed up: "
                + (adapter == null ? "unavailable" : adapter.path("caller").path("tool_calls"))
            : "tool received: " + row.invocations();
    if (adapter != null) {
      JsonNode caller = adapter.path("caller").deepCopy();
      JsonNode callback = caller.path("callback_only");
      if (caller.isObject())
        ((com.fasterxml.jackson.databind.node.ObjectNode) caller).remove("callback_only");
      result +=
          "; caller: "
              + caller
              + "; callback-only: "
              + callback
              + "; terminal signals: "
              + adapter.path("terminal_signals")
              + "; error: "
              + adapter.path("error");
    }
    return result;
  }

  private static String upstream(Entry entry, Map<String, JsonNode> upstream) {
    if (entry == null || !"filed".equals(entry.disclosure()))
      return "unfiled; withheld from the public report";
    var live = upstream.get(entry.issue());
    String status = live == null ? entry.status() : live.path("status").asText(entry.status());
    String text = entry.issue() + " (" + status + ")";
    if (entry.maintainerPosition() != null && !entry.maintainerPosition().isBlank())
      text += " — “" + entry.maintainerPosition() + "”";
    return text;
  }

  private static String command(RunRecord row) {
    String[] c = row.cell().split("\\|");
    String[] f = row.fixtureId().split("/", 2), s = f[1].split("@", 2);
    StringBuilder command =
        new StringBuilder("./tcf --container ")
            .append(c[5].substring(3))
            .append(" cell ")
            .append(c[0])
            .append(" ")
            .append(s[0])
            .append(" --wire ")
            .append(f[0])
            .append(" --surface ")
            .append(c[2])
            .append(" --module ")
            .append(c[1])
            .append(" --tools ")
            .append(c[4])
            .append(" --http-client ")
            .append(c[6]);
    if (s.length > 1) {
      var tokens =
          Arrays.stream(s[1].split("\\+"))
              .filter(t -> !t.equals("nonstream") && !t.equals(c[4]))
              .toList();
      if (!tokens.isEmpty()) command.append(" --variant ").append(String.join("+", tokens));
    }
    return command.toString();
  }

  private static String escape(String text) {
    return Objects.requireNonNullElse(text, "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("`", "&#96;");
  }

  private static String table(String text) {
    return escape(text).replace("|", "&#124;").replace("\r", "").replace("\n", "<br>");
  }

  private static void write(Path path, String text) throws IOException {
    Files.writeString(path, text);
  }
}
