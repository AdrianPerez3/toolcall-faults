package toolcallfaults.runner.judge;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;
import toolcallfaults.runner.oracle.Category;

public final class Judgements {
  private final JsonNode entries, register;

  private Judgements(JsonNode entries, JsonNode register) {
    this.entries = entries;
    this.register = register;
  }

  public static Judgements load(Path file) throws IOException {
    JsonNode document = FixtureLoader.JSON.readTree(file.toFile());
    if (document == null
        || !document.path("format").asText().equals("tcf.judgements/v1")
        || !document.path("entries").isArray()) throw new IOException("Invalid judgements");
    return new Judgements(document.get("entries"), document.path("register"));
  }

  public Verdict judge(String fixtureId, String surface, Category category, JsonNode attributes) {
    String[] parts = fixtureId.split("/", 2), variant = parts[1].split("@", 2);
    Set<String> tokens =
        variant.length == 1 ? Set.of() : new HashSet<>(List.of(variant[1].split("\\+")));
    JsonNode chosen = null;
    int specificity = -1;
    for (JsonNode entry : entries) {
      String wire = entry.path("wire").asText(), shape = entry.path("variant").asText();
      if (!entry.path("scenario").asText().equals(variant[0])
          || !(wire.equals("*") || wire.equals(parts[0]))
          || !(shape.equals("*") || tokens.contains(shape))) continue;
      int score = (wire.equals("*") ? 0 : 1) + (shape.equals("*") ? 0 : 2);
      if (score > specificity) {
        chosen = entry;
        specificity = score;
      }
    }
    if (chosen == null)
      return new Verdict(
          "record-only", false, false, List.of(), List.of(), "suite", "No judgement entry.");
    String level = surface.startsWith("model-") ? "record-only" : chosen.path("level").asText();
    boolean unsupported = false, flagged = false;
    // A rule may rest on another criterion than its entry; a provider rule outranks a suite one.
    String criterion = chosen.path("criterion").asText("suite"), matched = null;
    for (JsonNode rule : chosen.path("unsupported"))
      if ((rule.path("category").asText().equals("*")
              || rule.path("category").asText().equals(category.name()))
          && when(rule.path("when").asText(), attributes)) {
        unsupported = true;
        String own = rule.path("criterion").asText(criterion);
        if (matched == null || own.equals(criterion)) matched = own;
      }
    if (matched != null) criterion = matched;
    for (JsonNode flag : chosen.path("flag"))
      if (flag.asText().equals("*") || flag.asText().equals(category.name())) flagged = true;
    // A provider's sentence is a basis on that provider's wire; elsewhere it is context.
    List<String> keys = new ArrayList<>(), related = new ArrayList<>();
    for (JsonNode k : chosen.path("keys")) {
      JsonNode wires = register.path(k.asText()).path("wires");
      boolean own = !wires.isArray() || wires.isEmpty();
      for (JsonNode w : wires) if (w.asText().equals(parts[0])) own = true;
      (own ? keys : related).add(k.asText());
    }
    String basis = chosen.path("basis").asText();
    if (attributes.has("control_failure_basis"))
      basis += " Declared control failure: " + attributes.path("control_failure_basis").asText();
    return new Verdict(
        level,
        !level.equals("record-only") && unsupported,
        flagged,
        List.copyOf(keys),
        List.copyOf(related),
        criterion,
        basis);
  }

  private static boolean when(String condition, JsonNode a) {
    return switch (condition) {
      case "always" -> true;
      case "no_limit_signal" -> !nonCompletion(a.path("caller_finish_reasons"));
      case "two_terminal_signals" -> a.path("terminal_signals").size() > 1;
      case "first_call_executed_and_turn_clean" ->
          a.path("invocation_count").asInt() >= 1
              && contains(a.path("terminal_signals"), "complete")
              && !a.path("watchdog_killed").asBoolean()
              && !contains(a.path("terminal_signals"), "error")
              && !nonCompletion(a.path("caller_finish_reasons"));
      case "beyond_configured_timeout" ->
          a.path("elapsed_ms").asLong() > a.path("timeout_ms").asLong(3000);
      default -> throw new IllegalArgumentException("Unknown judgement condition " + condition);
    };
  }

  private static boolean contains(JsonNode n, String value) {
    for (JsonNode child : n) if (child.asText().equals(value)) return true;
    return false;
  }

  private static boolean nonCompletion(JsonNode n) {
    if (n.isTextual())
      return !Set.of(
              "stop", "end_turn", "tool_calls", "tool_use", "tool_execution", "stop_sequence")
          .contains(n.asText().toLowerCase(Locale.ROOT));
    for (JsonNode child : n) if (nonCompletion(child)) return true;
    return false;
  }
}
