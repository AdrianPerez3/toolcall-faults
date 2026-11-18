package toolcallfaults.runner.judge;

import java.util.List;

/**
 * @param keys register sentences of the provider whose wire the cell replays
 * @param related source sentences about another wire, service or request mode
 * @param criterion whose criterion made the outcome unsupported: provider, suite,
 *     target-documentation or control
 */
public record Verdict(
    String level,
    boolean unsupported,
    boolean flagged,
    List<String> keys,
    List<String> related,
    String criterion,
    String basis) {

  /** Keys with a label for the report: the wire's own sentences, then the related ones. */
  public String citation() {
    String text = String.join(", ", keys);
    if (!related.isEmpty())
      text += (text.isEmpty() ? "" : "; ") + "related guidance: " + String.join(", ", related);
    return text;
  }

  public String criterionLabel() {
    return switch (criterion) {
      case "provider" -> "provider documentation";
      case "suite" -> "suite criterion";
      case "target-documentation" -> "the target's own documentation";
      case "control" -> "control";
      default -> criterion;
    };
  }
}
