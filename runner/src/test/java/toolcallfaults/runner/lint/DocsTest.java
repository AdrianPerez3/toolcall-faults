package toolcallfaults.runner.lint;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DocsTest {
  private final Path root = Path.of("..").toAbsolutePath().normalize();

  private String read(String name) throws Exception {
    Path file = root.resolve(name);
    assertTrue(Files.isRegularFile(file), "Required document missing: " + name);
    return Files.readString(file);
  }

  private List<Path> publicDocuments() throws Exception {
    read("CONTRIBUTING.md");
    var files = new ArrayList<>(List.of(root.resolve("README.md"), root.resolve("CONTRIBUTING.md")));
    try (var docs = Files.list(root.resolve("docs"))) {
      files.addAll(docs.filter(p -> p.toString().endsWith(".md")).sorted().toList());
    }
    return files;
  }

  @Test
  void readme_has_required_statements() throws Exception {
    String readme = read("README.md");
    for (String statement :
        List.of(
            "to our knowledge", "not a ranking", "also reports to upstream projects",
            "AI assistance", "wiretruth", "agent-faultbench", "aimock", "Reference adapters")) {
      assertTrue(readme.contains(statement), "README missing: " + statement);
    }
  }

  @Test
  void unconfirmed_results_are_not_called_defects_and_status_matches_the_tree() throws Exception {
    String readme = read("README.md");
    assertFalse(
        Pattern.compile("\\b(defects?|bugs?|vulnerabilit(y|ies))\\b", Pattern.CASE_INSENSITIVE)
            .matcher(readme)
            .find(),
        "README uses a verdict word for results upstream has not confirmed");
    // The reference image is built without results/ (see .dockerignore).
    if (!Files.isDirectory(root.resolve("results"))) return;
    try (var rows = Files.walk(root.resolve("results"))) {
      if (rows.anyMatch(p -> p.toString().endsWith(".jsonl")))
        assertFalse(
            readme.contains("no results published"),
            "README says no results are published while results/ holds tracked rows");
    }
  }

  @Test
  void no_forbidden_claims_in_public_documents() throws Exception {
    var forbidden =
        Pattern.compile(
            "first cross-framework matrix|first client-side conformance suite|first to test truncated tool calls",
            Pattern.CASE_INSENSITIVE);
    for (Path file : publicDocuments()) {
      assertFalse(
          forbidden.matcher(Files.readString(file)).find(),
          "Unsupported novelty claim in " + file.getFileName());
    }
  }

  @Test
  void no_ranking_words_in_public_documents() throws Exception {
    var forbidden =
        Pattern.compile(
            "\\bleague table\\b|\\bpass rate\\b|\\branking of\\b", Pattern.CASE_INSENSITIVE);
    for (Path file : publicDocuments()) {
      boolean nonGoals = false;
      for (String line : Files.readAllLines(file)) {
        if (line.startsWith("## ")) nonGoals = line.substring(3).trim().equalsIgnoreCase("Non-goals");
        if (!nonGoals)
          assertFalse(
              forbidden.matcher(line).find(),
              "Ranking language in " + file.getFileName() + ": " + line);
      }
    }
  }

  @Test
  void adapter_guide_names_the_four_things() throws Exception {
    String guide = read("docs/adding-an-adapter.md").toLowerCase(Locale.ROOT);
    for (String requirement :
        List.of(
            "adapter.json", "pom.xml", "version", "property", "one source file", "kit",
            "expectations/baseline/", "s01", "executed_as_sent", "every declared surface")) {
      assertTrue(guide.contains(requirement), "Adapter guide missing: " + requirement);
    }
  }
}
