package toolcallfaults.runner.lint;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestLintTest {
  @TempDir Path root;

  private com.fasterxml.jackson.databind.node.ObjectNode upstream(String status) {
    var n = toolcallfaults.runner.fixture.FixtureLoader.JSON.createObjectNode();
    n.put("library", "target")
        .put("issue", "https://example.org/issues/1")
        .putNull("pr")
        .put("status", status)
        .putNull("fixed_in")
        .put("opened", "2026-11-10")
        .put("notes", "Observed on synthetic replay.");
    n.putArray("scenarios").add("truncation.name-only");
    n.putArray("providers").add("openai-chat");
    return n;
  }

  private void writeUpstream(com.fasterxml.jackson.databind.JsonNode entry) throws Exception {
    Path file = root.resolve("results/upstream.json");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        toolcallfaults.runner.fixture.FixtureLoader.JSON.createArrayNode().add(entry).toString());
  }

  @Test
  void upstream_status_must_be_in_vocabulary() throws Exception {
    writeUpstream(upstream("fixed"));
    assertTrue(ManifestLint.check(root).stream().anyMatch(p -> p.jsonPath().equals("$[0].status")));
    for (String status :
        java.util.List.of(
            "reported",
            "confirmed",
            "fix-merged",
            "released",
            "wontfix",
            "disputed",
            "documented-behavior")) {
      writeUpstream(upstream(status).put("fixed_in", "1.2"));
      assertTrue(ManifestLint.check(root).isEmpty(), ManifestLint.check(root).toString());
    }
  }

  @Test
  void released_needs_fixed_in() throws Exception {
    var row = upstream("released");
    writeUpstream(row);
    assertTrue(
        ManifestLint.check(root).stream().anyMatch(p -> p.jsonPath().equals("$[0].fixed_in")));
    writeUpstream(row.put("fixed_in", "1.2"));
    assertTrue(ManifestLint.check(root).isEmpty(), ManifestLint.check(root).toString());
  }

  @Test
  void upstream_link_cannot_contain_whitespace() throws Exception {
    writeUpstream(upstream("reported").put("issue", "https://example.org/issues/not a link"));
    assertTrue(ManifestLint.check(root).stream().anyMatch(p -> p.jsonPath().equals("$[0].issue")));
  }

  @Test
  void every_filed_baseline_entry_has_an_upstream_row() throws Exception {
    Path file = root.resolve("expectations/baseline/target@1.json");
    Files.createDirectories(file.getParent());
    var json = toolcallfaults.runner.fixture.FixtureLoader.JSON;
    var baseline = json.createObjectNode().put("format", "tcf.baseline/v1");
    baseline
        .putArray("entries")
        .addObject()
        .put("cell", toolcallfaults.runner.results.Records.CELL)
        .put("category", "EXECUTED_ALTERED")
        .put("disclosure", "filed")
        .put("issue", "https://example.org/issues/1")
        .put("status", "reported")
        .put("filedOn", "2026-11-10");
    Files.writeString(file, baseline.toString());
    assertTrue(
        ManifestLint.check(root).stream().anyMatch(p -> p.message().contains("upstream row")));
    var row = upstream("reported").putNull("issue").put("pr", "https://example.org/issues/1");
    writeUpstream(row);
    assertTrue(ManifestLint.check(root).isEmpty(), ManifestLint.check(root).toString());
    row.putArray("providers").add("anthropic");
    writeUpstream(row);
    assertTrue(
        ManifestLint.check(root).stream().anyMatch(p -> p.message().contains("upstream row")));
  }

  @Test
  void frozen_prediction_selector_requires_an_explicit_erratum() throws Exception {
    for (String target :
        java.util.List.of("spring-ai", "langchain4j", "openai-java", "anthropic-sdk-java")) {
      Path dir = root.resolve("adapters/" + target);
      Files.createDirectories(dir);
      Files.copy(Path.of("../adapters", target, "adapter.json"), dir.resolve("adapter.json"));
    }
    Path predictions = root.resolve("expectations/predictions.json");
    Files.createDirectories(predictions.getParent());
    // A frozen prediction whose module selector lacks a hyphen, as in the registered file.
    Files.writeString(
        predictions,
        """
        {"format":"tcf.predictions/v1","entries":[{"scenario":"tools.single-call","wire":"openai-chat",
        "variant":"*","target":"langchain4j","module":"langchain4j-openai","surface":"loop-stream",
        "spec_text":"executes","label":"predicted","source":"registered before the first run",
        "predicted":["EXECUTED_AS_SENT"]}]}
        """);
    String frozen = Files.readString(predictions);
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(p -> p.message().contains("unknown prediction module")));
    Files.writeString(
        root.resolve("expectations/prediction-errata.json"),
        """
        {"format":"tcf.prediction-errata/v1","entries":[{"target":"langchain4j","module":"langchain4j-openai",
        "correct_module":"langchain4j-open-ai","basis":"Manifest already declared this name before prediction registration."}]}
        """);
    assertTrue(ManifestLint.check(root).isEmpty(), ManifestLint.check(root).toString());
    assertEquals(frozen, Files.readString(predictions));
  }

  @Test
  void public_baseline_cannot_be_unfiled() throws Exception {
    Path p = root.resolve("expectations/baseline/target@1.json");
    Files.createDirectories(p.getParent());
    Files.writeString(
        p,
        "{\"format\":\"tcf.baseline/v1\",\"entries\":[{\"cell\":\"target@1|module|loop-stream|openai-chat/truncation.name-only|record_note|jdk21|jdk\",\"category\":\"EXECUTED_ALTERED\",\"disclosure\":\"unfiled\"}]}");
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(x -> x.jsonPath().equals("$.entries[0].disclosure")));
  }

  @Test
  void filed_baseline_requires_issue_and_date() throws Exception {
    Path p = root.resolve("expectations/baseline/target@1.json");
    Files.createDirectories(p.getParent());
    Files.writeString(
        p,
        "{\"format\":\"tcf.baseline/v1\",\"entries\":[{\"cell\":\"cell\",\"category\":\"EXECUTED_ALTERED\",\"disclosure\":\"filed\"}]}");
    var problems = ManifestLint.check(root);
    assertTrue(problems.stream().anyMatch(x -> x.jsonPath().equals("$.entries[0].issue")));
    assertTrue(problems.stream().anyMatch(x -> x.jsonPath().equals("$.entries[0].filedOn")));
  }

  @Test
  void public_unsupported_result_requires_filed_baseline() throws Exception {
    Files.createDirectories(root.resolve("suite"));
    Files.copy(Path.of("../suite/judgements.json"), root.resolve("suite/judgements.json"));
    var store =
        new toolcallfaults.runner.results.ResultStore(
            root.resolve("results"), root.resolve(".local/results"));
    store.write(
        "target",
        "1",
        java.util.List.of(
            toolcallfaults.runner.results.Records.row(
                toolcallfaults.runner.results.Records.CELL, 1, "EXECUTED_ALTERED", 1)),
        java.util.Set.of());
    assertTrue(
        ManifestLint.check(root).stream().anyMatch(x -> x.message().contains("filed baseline")));
  }

  private void rowsFor(String version, boolean withheld) throws Exception {
    var row =
        toolcallfaults.runner.results.Records.row(
            toolcallfaults.runner.results.Records.CELL, 1, "EXECUTED_AS_SENT", 1);
    new toolcallfaults.runner.results.ResultStore(
            root.resolve("results"), root.resolve(".local/results"))
        .write(
            "target",
            version,
            java.util.List.of(row),
            withheld ? java.util.Set.of(row.cell()) : java.util.Set.of());
  }

  private void matrixVersions(String versions) throws Exception {
    Files.writeString(
        root.resolve("matrix.json"),
        "{\"format\":\"tcf.matrix/v1\",\"targets\":{\"target\":{\"versions\":" + versions + "}}}");
  }

  private boolean currentVersionLacksRows() throws Exception {
    return ManifestLint.check(root).stream()
        .anyMatch(
            x ->
                x.file().endsWith("matrix.json")
                    && x.message().equals("target has rows for 1 and none for its current version 2"));
  }

  @Test
  void a_new_current_version_without_rows_is_reported() throws Exception {
    // A version bump of a library under test: the rows still describe the previous version.
    rowsFor("1", false);
    matrixVersions("{\"current\":\"2\"}");
    assertTrue(currentVersionLacksRows(), ManifestLint.check(root).toString());
    rowsFor("2", false);
    assertFalse(currentVersionLacksRows());
  }

  @Test
  void withheld_rows_of_the_current_version_count() throws Exception {
    rowsFor("1", false);
    rowsFor("2", true);
    matrixVersions("{\"current\":\"2\"}");
    assertFalse(currentVersionLacksRows(), ManifestLint.check(root).toString());
  }

  @Test
  void rows_of_the_control_and_next_versions_alone_are_not_a_stale_current_version()
      throws Exception {
    // A public checkout may hold only those rows when the current version's are withheld.
    rowsFor("1", false);
    matrixVersions("{\"current\":\"2\",\"control\":{\"version\":\"1\"}}");
    assertFalse(currentVersionLacksRows(), ManifestLint.check(root).toString());
    matrixVersions("{\"current\":\"2\",\"next\":{\"version\":\"1\",\"blocking\":false}}");
    assertFalse(currentVersionLacksRows(), ManifestLint.check(root).toString());
  }

  private void trackedRow(String category) throws Exception {
    Files.createDirectories(root.resolve("suite"));
    Files.copy(Path.of("../suite/judgements.json"), root.resolve("suite/judgements.json"));
    new toolcallfaults.runner.results.ResultStore(
            root.resolve("results"), root.resolve(".local/results"))
        .write(
            "target",
            "1",
            java.util.List.of(
                toolcallfaults.runner.results.Records.row(
                    toolcallfaults.runner.results.Records.CELL, 1, category, 1)),
            java.util.Set.of());
  }

  private void baselineEntry(String dir, String disclosure, String category) throws Exception {
    Path file = root.resolve(dir).resolve("target@1.json");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "{\"format\":\"tcf.baseline/v1\",\"entries\":[{\"cell\":\""
            + toolcallfaults.runner.results.Records.CELL
            + "\",\"category\":\""
            + category
            + "\",\"issue\":null,\"status\":null,\"disclosure\":\""
            + disclosure
            + "\",\"filedOn\":null,\"maintainerAnswered\":false,\"maintainerPosition\":null}]}");
  }

  private boolean needsFiledBaseline() throws Exception {
    return ManifestLint.check(root).stream()
        .anyMatch(x -> x.message().startsWith("unsupported row needs a filed baseline"));
  }

  @Test
  void tracked_harness_row_is_reported() throws Exception {
    trackedRow("HARNESS");
    assertTrue(
        ManifestLint.check(root).stream().anyMatch(x -> x.message().contains("HARNESS row")));
  }

  @Test
  void unfiled_local_entry_does_not_make_a_tracked_unsupported_row_acceptable() throws Exception {
    trackedRow("EXECUTED_ALTERED");
    baselineEntry(".local/baseline", "unfiled", "EXECUTED_ALTERED");
    assertTrue(needsFiledBaseline());
  }

  @Test
  void filed_entry_for_another_outcome_does_not_cover_a_tracked_unsupported_row()
      throws Exception {
    trackedRow("DROPPED");
    baselineEntry("expectations/baseline", "filed", "EXECUTED_ALTERED");
    assertTrue(needsFiledBaseline());
  }

  @Test
  void tracked_record_only_sibling_of_an_unfiled_entry_is_reported() throws Exception {
    Files.createDirectories(root.resolve("suite"));
    Files.copy(Path.of("../suite/judgements.json"), root.resolve("suite/judgements.json"));
    String sibling =
        toolcallfaults.runner.results.Records.CELL.replace("loop-stream", "model-stream");
    new toolcallfaults.runner.results.ResultStore(
            root.resolve("results"), root.resolve(".local/results"))
        .write(
            "target",
            "1",
            java.util.List.of(toolcallfaults.runner.results.Records.row(sibling, 1, "SURFACED", 1)),
            java.util.Set.of());
    baselineEntry(".local/baseline", "unfiled", "EXECUTED_ALTERED");
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(x -> x.message().startsWith("row of a family with an unfiled observation")));
  }

  private static final String PREDICTIONS =
      "{\"format\":\"tcf.predictions/v1\",\"entries\":[]}";

  private void commitment(String sha256) throws Exception {
    Files.createDirectories(root.resolve("expectations"));
    Files.writeString(
        root.resolve("expectations/predictions.commitment.json"),
        "{\"format\":\"tcf.prediction-commitment/v1\",\"algorithm\":\"SHA-256\",\"sha256\":\""
            + sha256
            + "\",\"bytes\":"
            + PREDICTIONS.length()
            + "}");
  }

  private static String sha256(String text) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  void private_predictions_must_match_the_public_commitment() throws Exception {
    Path file = root.resolve(".local/expectations/predictions.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, PREDICTIONS);
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(x -> x.message().equals("predictions have no public commitment")));
    commitment(sha256(PREDICTIONS));
    assertTrue(ManifestLint.check(root).isEmpty(), ManifestLint.check(root).toString());
    commitment("0".repeat(64));
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(x -> x.message().equals("predictions differ from the committed hash")));
  }

  @Test
  void commitment_alone_is_valid_in_a_public_checkout() throws Exception {
    commitment(sha256(PREDICTIONS));
    assertTrue(ManifestLint.check(root).isEmpty(), ManifestLint.check(root).toString());
    Files.writeString(root.resolve("expectations/predictions.commitment.json"), "{}");
    assertFalse(ManifestLint.check(root).isEmpty());
  }

  private void anchor(String dir, String fixture) throws Exception {
    Path p = root.resolve(dir).resolve("anchors.json");
    Files.createDirectories(p.getParent());
    Files.createDirectories(root.resolve("suite/fixtures/openai-chat"));
    Files.copy(
        Path.of("../suite/fixtures/" + fixture + ".json"),
        root.resolve("suite/fixtures/" + fixture + ".json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(
        p,
        "{\"format\":\"tcf.anchors/v1\",\"entries\":[{\"target\":\"target\",\"version\":\"1\","
            + "\"module\":\"module\",\"surface\":\"model-stream\",\"fixture\":\""
            + fixture
            + "\",\"tool\":\"record_note\",\"jdk\":21,\"expect\":{\"category\":\"SURFACED\"}}]}");
  }

  @Test
  void public_anchor_of_a_family_with_an_unfiled_observation_is_reported() throws Exception {
    // Records.CELL is target@1|module|loop-stream|openai-chat/truncation.name-only|...
    anchor("expectations", "openai-chat/truncation.name-only");
    assertTrue(ManifestLint.check(root).isEmpty(), ManifestLint.check(root).toString());
    baselineEntry(".local/baseline", "unfiled", "EXECUTED_ALTERED");
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(x -> x.message().startsWith("anchor of a family with an unfiled observation")));
  }

  @Test
  void private_anchors_are_linted_like_public_ones() throws Exception {
    anchor(".local/expectations", "openai-chat/truncation.name-only");
    Files.delete(root.resolve("suite/fixtures/openai-chat/truncation.name-only.json"));
    assertTrue(
        ManifestLint.check(root).stream().anyMatch(x -> x.message().startsWith("unknown fixture")));
  }

  @Test
  void judgement_keys_cannot_be_silently_omitted() throws Exception {
    Path p = root.resolve("suite/judgements.json");
    Files.createDirectories(p.getParent());
    Files.writeString(
        p,
        """
        {"format":"tcf.judgements/v1","register":{},"entries":[{"scenario":"tools.single-call",
          "wire":"*","variant":"*","level":"required","basis":"normal",
          "unsupported":[],"flag":[],"defensible":[]}]}
        """);
    assertTrue(
        ManifestLint.check(root).stream().anyMatch(x -> x.jsonPath().equals("$.entries[0].keys")));
  }

  @Test
  void judgement_keys_must_exist_in_register() throws Exception {
    Path p = root.resolve("suite/judgements.json");
    Files.createDirectories(p.getParent());
    Files.writeString(
        p,
        """
        {"format":"tcf.judgements/v1","register":{},"entries":[{"scenario":"tools.single-call",
        "wire":"*","variant":"*","level":"required","keys":["N-AN7"],"basis":"normal",
        "unsupported":[],"flag":[],"defensible":[]}]}
        """);
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(p1 -> p1.jsonPath().equals("$.entries[0].keys")));
  }

  @Test
  void prediction_categories_must_be_known() throws Exception {
    Path p = root.resolve("expectations/predictions.json");
    Files.createDirectories(p.getParent());
    Files.writeString(
        p,
        """
        {"format":"tcf.predictions/v1","entries":[{"scenario":"tools.single-call","wire":"*",
        "variant":"*","target":"example","module":"example","surface":"loop-stream",
        "spec_text":"normal","label":"predicted","source":"spec","predicted":["PASSED"]}]}
        """);
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(p1 -> p1.jsonPath().equals("$.entries[0].predicted")));
  }

  void declaredFailure(String basis) throws Exception {
    manifest(
        """
        "modules":{"module":{"wire":"openai-chat","http_client":"jdk"}},
        "surfaces":["loop-call"],"control_failures":[{"module":"module",
        "surface":"loop-call","wire":"openai-chat","tool":"record_note",
        "category":"ERROR_UNTYPED","error_class":"java.lang.IllegalStateException"
        """
            + basis
            + "}]");
  }

  @Test
  void declared_control_failure_requires_a_basis_link() throws Exception {
    declaredFailure("");
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(p -> p.jsonPath().equals("$.control_failures[0].basis")));
  }

  @Test
  void declared_control_failure_rejects_a_non_link_basis() throws Exception {
    declaredFailure(",\"basis\":\"known upstream\"");
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(p -> p.jsonPath().equals("$.control_failures[0].basis")));
  }

  void manifest(String fields) throws Exception {
    Path p = root.resolve("adapters/example/adapter.json");
    Files.createDirectories(p.getParent());
    Files.writeString(p, "{\"format\":\"tcf.adapter/v1\",\"target\":\"example\"," + fields + "}");
  }

  @Test
  void manifest_needs_reason_and_docs_for_unsupported() throws Exception {
    manifest("\"config_notes\":[\"none\"],\"unsupported\":[{\"reason\":\"no surface\"}]");
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(p -> p.jsonPath().equals("$.unsupported[0].docs")));
  }

  @Test
  void manifest_needs_config_notes() throws Exception {
    manifest("\"unsupported\":[]");
    assertTrue(
        ManifestLint.check(root).stream().anyMatch(p -> p.jsonPath().equals("$.config_notes")));
  }

  @Test
  void anchors_reference_existing_fixtures() throws Exception {
    Path p = root.resolve("expectations/anchors.json");
    Files.createDirectories(p.getParent());
    Files.writeString(
        p, "{\"format\":\"tcf.anchors/v1\",\"entries\":[{\"fixture\":\"openai-chat/missing\"}]}");
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(x -> x.jsonPath().equals("$.entries[0].fixture")));
  }

  @Test
  void empty_manifest_is_a_problem() throws Exception {
    manifest("\"unsupported\":[]");
    Files.writeString(root.resolve("adapters/example/adapter.json"), "");
    assertTrue(ManifestLint.check(root).stream().anyMatch(p -> p.jsonPath().equals("$")));
  }

  @Test
  void malformed_anchors_are_a_problem() throws Exception {
    Path p = root.resolve("expectations/anchors.json");
    Files.createDirectories(p.getParent());
    Files.writeString(p, "{");
    assertTrue(ManifestLint.check(root).stream().anyMatch(x -> x.jsonPath().equals("$")));
  }

  @Test
  void explicit_control_failure_version_scope_cannot_be_empty() throws Exception {
    Path p = root.resolve("adapters/spring-ai/adapter.json");
    Files.createDirectories(p.getParent());
    var n =
        toolcallfaults.runner.fixture.FixtureLoader.JSON.readTree(
            Path.of("../adapters/spring-ai/adapter.json").toFile());
    ((com.fasterxml.jackson.databind.node.ObjectNode) n.path("control_failures").get(0))
        .putArray("versions");
    Files.writeString(p, n.toString());
    assertTrue(
        ManifestLint.check(root).stream()
            .anyMatch(x -> x.jsonPath().equals("$.control_failures[0].versions")));
  }
}
