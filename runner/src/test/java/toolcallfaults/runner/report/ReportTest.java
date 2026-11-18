package toolcallfaults.runner.report;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.cell.Cell;
import toolcallfaults.runner.fixture.FixtureLoader;
import toolcallfaults.runner.matrix.ControlFailures;
import toolcallfaults.runner.results.*;

class ReportTest {
  @TempDir Path root;
  static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
  static final String ISSUE = "https://github.com/spring-projects/spring-ai/issues/7360";
  static final String BASIS = "https://github.com/spring-projects/spring-ai/pull/6775";
  static final String CELL =
      "spring-ai@2.0.2|spring-ai-anthropic|loop-stream|anthropic/truncation.name-only@no-block-stop|record_note|jdk21|okhttp";

  @BeforeEach
  void setup() throws Exception {
    for (String dir : List.of("suite/fixtures", "suite/scenarios")) {
      try (var files = Files.walk(Path.of("..", dir))) {
        for (var source : files.filter(Files::isRegularFile).toList()) {
          var dest = root.resolve(dir).resolve(Path.of("..", dir).relativize(source));
          Files.createDirectories(dest.getParent());
          Files.copy(source, dest);
        }
      }
    }
    Files.copy(Path.of("../suite/judgements.json"), root.resolve("suite/judgements.json"));
    Files.writeString(root.resolve("matrix.json"), "{\"window_days\":14,\"repository\":null}");
    Files.createDirectories(root.resolve("results"));
    Files.writeString(
        root.resolve("results/last-run.json"),
        "{\"date\":\"2026-11-16\",\"suite_version\":\"suite-test\"}");
    writeRow(CELL, "EXECUTED_ALTERED", false);
    baseline(CELL, TODAY.minusDays(15), false, "filed", ISSUE);
  }

  RunRecord writeRow(String cell, String category, boolean withheld) throws Exception {
    var row = Records.row(cell, 1, category, 2);
    ((ObjectNode) row.adapter())
        .putObject("versions")
        .put("com.anthropic:anthropic-java-core", "2.72.0");
    new ResultStore(root.resolve("results"), root.resolve(".local/results"))
        .write("spring-ai", "2.0.2", List.of(row), withheld ? Set.of(cell) : Set.of());
    return row;
  }

  void baseline(String cell, LocalDate date, boolean answered, String disclosure, String issue)
      throws Exception {
    var file =
        root.resolve(
            (disclosure.equals("filed") ? "expectations/baseline/" : ".local/baseline/")
                + "spring-ai@2.0.2.json");
    Files.createDirectories(file.getParent());
    var n =
        Files.exists(file)
            ? (ObjectNode) FixtureLoader.JSON.readTree(file.toFile())
            : FixtureLoader.JSON.createObjectNode().put("format", "tcf.baseline/v1");
    var entries = n.withArray("entries");
    for (int i = entries.size() - 1; i >= 0; i--)
      if (entries.get(i).path("cell").asText().equals(cell)) entries.remove(i);
    entries
        .addObject()
        .put("cell", cell)
        .put("category", cell.contains("tools.single-call") ? "ERROR_UNTYPED" : "EXECUTED_ALTERED")
        .put("issue", issue)
        .put("status", "reported")
        .put("disclosure", disclosure)
        .put("filedOn", date.toString())
        .put("maintainerAnswered", answered)
        .put("maintainerPosition", answered ? "We are reviewing the replay." : null);
    Files.writeString(file, n.toString());
  }

  String generate(boolean local) throws Exception {
    Path dir = root.resolve(local ? ".local/site" : "site");
    Report.generate(root, dir, local, TODAY);
    return Files.readString(dir.resolve("targets/spring-ai.md"));
  }

  String pages() throws Exception {
    StringBuilder text = new StringBuilder();
    try (var files = Files.walk(root.resolve("site"))) {
      for (var f : files.filter(Files::isRegularFile).toList()) text.append(Files.readString(f));
    }
    return text.toString();
  }

  @Test
  void banner_has_date_versions_and_sentence() throws Exception {
    String p = generate(false);
    for (String text :
        List.of(
            "2026-11-16",
            "suite-test",
            "Fixture version:",
            "observations of behaviour on replayed bytes; not a ranking",
            "older than 14 days: treat as stale")) assertTrue(p.contains(text), text);
    assertTrue(Pattern.compile("Fixture version: `[a-f0-9]{64}`").matcher(p).find());
  }

  @Test
  void no_totals_percentages_or_orderings() throws Exception {
    generate(false);
    String p = pages().replace("observations of behaviour on replayed bytes; not a ranking", "");
    assertFalse(
        Pattern.compile("(?i)\\d+\\s?%|\\btotal\\b|\\bscore\\b|\\brank|\\bpass rate\\b")
            .matcher(p)
            .find());
  }

  @Test
  void the_word_bug_is_not_used() throws Exception {
    generate(false);
    assertFalse(Pattern.compile("(?i)\\bbugs?\\b|\\bvulnerabilit").matcher(pages()).find());
  }

  @Test
  void unsupported_row_has_basis_issue_and_command() throws Exception {
    String p = generate(false);
    for (String text :
        List.of(
            "N-AN7",
            ISSUE,
            "./tcf --container 21 cell spring-ai@2.0.2 truncation.name-only",
            "docs/disputing-a-cell.md",
            "2.72.0")) assertTrue(p.contains(text), text);
  }

  @Test
  void cell_inside_reply_window_is_absent_from_every_public_page() throws Exception {
    baseline(CELL, TODAY.minusDays(3), false, "filed", ISSUE);
    generate(false);
    assertFalse(pages().contains(CELL));
    assertFalse(pages().contains("EXECUTED_ALTERED"));
  }

  @Test
  void cell_with_maintainer_answer_is_present() throws Exception {
    baseline(CELL, TODAY.minusDays(3), true, "filed", ISSUE);
    String p = generate(false);
    assertTrue(p.contains(CELL));
    assertTrue(p.contains("“We are reviewing the replay.”"));
  }

  @Test
  void model_surface_says_handed_up() throws Exception {
    writeRow(CELL.replace("loop-stream", "model-stream"), "SURFACED", false);
    String p = generate(false);
    assertTrue(p.contains("handed up"));
  }

  @Test
  void record_only_cells_carry_no_verdict() throws Exception {
    baseline(CELL, TODAY, false, "filed", ISSUE);
    writeRow(CELL.replace("loop-stream", "model-stream"), "SURFACED", false);
    String p = generate(false);
    assertTrue(p.contains("record-only"));
    assertFalse(p.contains("Basis:"));
    assertFalse(p.contains("N-AN7"));
  }

  @Test
  void unfiled_cells_appear_only_in_the_local_report() throws Exception {
    Files.delete(root.resolve("expectations/baseline/spring-ai@2.0.2.json"));
    baseline(CELL, TODAY, false, "unfiled", null);
    writeRow(CELL, "EXECUTED_ALTERED", true);
    assertFalse(generate(false).contains(CELL));
    assertTrue(generate(true).contains(CELL));
  }

  @Test
  void declared_controls_and_not_run_cells_keep_basis_and_factual_notes() throws Exception {
    var notRun =
        new Cell(
            "spring-ai",
            "2.0.2",
            "spring-ai-deepseek",
            "loop-call",
            "openai-chat/finish.unknown-value-with-tool-call@nonstream",
            "record_note",
            21,
            "reactor-netty");
    var doc = FixtureLoader.JSON.createObjectNode().put("format", "tcf.not-run/v1");
    doc.putArray("entries")
        .add(
            FixtureLoader.JSON.valueToTree(
                new ControlFailures.NotRun(
                    notRun,
                    List.of(BASIS),
                    List.of(
                        "Synthetic fixture; API reference permits null; live API null or empty"
                            + " string is unverified until Task R."))));
    Files.writeString(root.resolve("results/not-run.json"), doc.toString());
    for (String tool : List.of("record_note", "set_level")) {
      String cell =
          "spring-ai@2.0.2|spring-ai-deepseek|loop-call|openai-chat/tools.single-call@nonstream"
              + (tool.equals("set_level") ? "+set_level" : "")
              + "|"
              + tool
              + "|jdk21|reactor-netty";
      var r = writeRow(cell, "ERROR_UNTYPED", false);
      ((ObjectNode) r.attributes())
          .put("control_failure_basis", BASIS)
          .put(
              "control_failure_notes",
              "Synthetic fixture; live API null or empty string is unverified until Task R.");
      new ResultStore(root.resolve("results"), root.resolve(".local/results"))
          .write("spring-ai", "2.0.2", List.of(r), Set.of());
      baseline(cell, TODAY.minusDays(30), false, "filed", BASIS);
    }
    String p = generate(false);
    for (String text :
        List.of(
            "Not run",
            notRun.key(),
            BASIS,
            "ERROR_UNTYPED",
            "java.lang.IllegalStateException",
            "Synthetic fixture",
            "unverified until Task R",
            "set_level")) assertTrue(p.contains(text), text);
  }

  @Test
  void missing_success_stamp_does_not_call_an_observation_a_successful_run() throws Exception {
    Files.delete(root.resolve("results/last-run.json"));
    String page = generate(false);
    assertTrue(page.contains("Last successful run: not recorded"));
    assertTrue(page.contains("Latest observation: 2026-11-16"));
  }

  @Test
  void judged_row_preserves_each_repetition_instead_of_selecting_the_first() throws Exception {
    var first = Records.row(CELL, 1, "DROPPED", 1);
    var second = Records.row(CELL, 2, "EXECUTED_ALTERED", 2);
    new ResultStore(root.resolve("results"), root.resolve(".local/results"))
        .write("spring-ai", "2.0.2", List.of(first, second), Set.of());
    // Both unsupported outcomes are visible in the local report; one has no filed entry.
    String local = generate(true).split("## Observations", 2)[0];
    assertTrue(local.contains("DROPPED"));
    assertTrue(local.contains("EXECUTED_ALTERED"));
    String[] localSections = local.split("## Other outcomes marked unsupported by the judgement register", 2);
    assertEquals(2, localSections.length, "Mixed criteria must be identified separately.");
    String mixed = localSections[1];
    assertTrue(mixed.contains("Criterion: suite criterion"));
    assertTrue(mixed.contains("Criterion: provider documentation"));

    // An eligible public cell must still show both the supported and the filed repetition.
    var supported = Records.row(CELL, 1, "SURFACED", 1);
    new ResultStore(root.resolve("results"), root.resolve(".local/results"))
        .write("spring-ai", "2.0.2", List.of(supported, second), Set.of());
    String table = generate(false).split("## Observations", 2)[0];
    assertTrue(table.contains("SURFACED"));
    assertTrue(table.contains("EXECUTED_ALTERED"));
  }

  @Test
  void callback_only_values_are_not_labelled_as_caller_visible() throws Exception {
    var row = writeRow(CELL, "EXECUTED_ALTERED", false);
    var caller = ((ObjectNode) row.adapter()).putObject("caller");
    caller.putArray("finish_reasons").add("stop");
    caller.putObject("callback_only").putArray("finish_reasons").add("HIDDEN_REASON");
    new ResultStore(root.resolve("results"), root.resolve(".local/results"))
        .write("spring-ai", "2.0.2", List.of(row), Set.of());
    String observation =
        generate(false)
            .lines()
            .filter(l -> l.startsWith("Repetition 1:"))
            .findFirst()
            .orElseThrow();
    assertTrue(observation.contains("callback-only: "));
    assertFalse(
        observation.split("; caller: ", 2)[1].split("; callback-only: ", 2)[0].contains(
            "HIDDEN_REASON"));
  }

  @Test
  void maintainer_quote_is_escaped_once_in_both_sections() throws Exception {
    var p = root.resolve("expectations/baseline/spring-ai@2.0.2.json");
    var n = FixtureLoader.JSON.readTree(p.toFile());
    ((ObjectNode) n.path("entries").get(0)).put("maintainerPosition", "A & B");
    Files.writeString(p, n.toString());
    String page = generate(false);
    assertTrue(page.contains("“A &amp; B”"));
    assertFalse(page.contains("&amp;amp;"));
  }

  @Test
  void filed_entry_does_not_publish_a_different_unsupported_outcome() throws Exception {
    // setup() files EXECUTED_ALTERED for this cell, past its reply window.
    writeRow(CELL, "DROPPED", false);
    generate(false);
    assertFalse(pages().contains(CELL));
  }

  @Test
  void register_page_resolves_every_key_to_its_sentence_source_and_wires() throws Exception {
    generate(false);
    String register = Files.readString(root.resolve("site/register.md"));
    assertTrue(register.contains("N-AN6"));
    assertTrue(register.contains("With fine-grained tool streaming,"));
    assertTrue(register.contains("https://platform.claude.com/docs/en/build-with-claude/streaming"));
    assertTrue(register.contains("basis on: anthropic"));
    String structuredOutputs = register.split("## N-OA6", 2)[1].split("## ", 2)[0];
    assertTrue(structuredOutputs.contains("context for: openai-chat"));
    assertTrue(Files.readString(root.resolve("site/index.md")).contains("(register.md)"));
  }

  @Test
  void scenario_rules_identify_the_criterion_of_each_outcome() throws Exception {
    generate(false);
    var scenarios = root.resolve("site/scenarios");
    String cut = Files.readString(scenarios.resolve("truncation.args-cut-mid-string.md"));
    assertTrue(cut.contains("the tool ran with other arguments than a complete call carried (provider documentation)"));
    assertTrue(cut.contains("not run, and the caller cannot tell (suite criterion)"));
    String stall = Files.readString(scenarios.resolve("stream.stall-mid-args.md"));
    assertFalse(stall.contains("No documentary support:"));
    assertTrue(stall.contains("when beyond configured timeout (suite criterion)"));
    assertTrue(stall.contains("Model surfaces are record-only"));
  }

  @Test
  void a_control_failure_is_listed_separately_from_provider_based_observations() throws Exception {
    String control = "spring-ai@2.0.2|spring-ai-deepseek|loop-call|openai-chat/tools.single-call@nonstream"
        + "|record_note|jdk21|reactor-netty";
    writeRow(control, "ERROR_UNTYPED", false);
    baseline(control, TODAY.minusDays(30), false, "filed", BASIS);
    String table = generate(false).split("## Observations", 2)[0];
    String[] sections = table.split("## Other outcomes marked unsupported by the judgement register", -1);
    assertEquals(2, sections.length);
    assertTrue(sections[0].contains("EXECUTED_ALTERED"));
    assertFalse(sections[0].contains("ERROR_UNTYPED"));
    assertTrue(sections[1].contains("ERROR_UNTYPED"));
    assertFalse(sections[1].contains("EXECUTED_ALTERED"));
  }

  @Test
  void model_only_observations_explain_the_execution_category() throws Exception {
    String model = "spring-ai@2.0.2|spring-ai-openai|model-stream|openai-chat/tools.single-call"
        + "|record_note|jdk21|okhttp";
    writeRow(model, "EXECUTED_AS_SENT", false);
    String page = generate(false);
    String modelSection = page.substring(page.indexOf(model));
    assertTrue(modelSection.contains("Execution categories describe the tool calls handed up to the caller"));
    assertTrue(modelSection.contains("EXECUTED_AS_SENT"));
  }

  @Test
  void scenario_page_states_sighting_and_judgement_rules() throws Exception {
    generate(false);
    String page = Files.readString(root.resolve("site/scenarios/tools.parallel-interleaved.md"));
    assertTrue(page.contains("Sighting: protocol-legal, no sighting"), page);
    assertTrue(page.contains("Criterion: provider documentation"));
    // Reports by this project's maintainer are marked as such where they are cited as evidence.
    String nameOnly = Files.readString(root.resolve("site/scenarios/truncation.name-only.md"));
    assertTrue(nameOnly.contains("filed by this project's maintainer"), nameOnly);
    String cut = Files.readString(root.resolve("site/scenarios/truncation.args-cut-mid-string.md"));
    assertTrue(
        cut.contains("Defensible: the error was sent back to the model and hidden from the caller"));
  }
}
