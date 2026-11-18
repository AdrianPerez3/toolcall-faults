package toolcallfaults.runner.oracle;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import toolcallfaults.kit.Signals;
import toolcallfaults.runner.cell.*;
import toolcallfaults.runner.fixture.*;

public final class Oracle {
  private Oracle() {}

  private static final Set<String> COMPLETION =
      Set.of("stop", "end_turn", "tool_calls", "tool_use", "tool_execution", "stop_sequence");

  private static boolean nonCompletion(JsonNode n) {
    if (n.isTextual()) return !COMPLETION.contains(n.asText().toLowerCase(Locale.ROOT));
    for (JsonNode child : n) if (nonCompletion(child)) return true;
    return false;
  }

  private static boolean verbatimIncomplete(Fixture fixture, JsonNode call) {
    if (!call.path("arguments_raw").isTextual()) return false;
    String raw = call.path("arguments_raw").textValue(), name = call.path("name").asText();
    for (TruthCall truth : fixture.truth().calls()) {
      if (truth.complete() || !truth.name().equals(name)) continue;
      if (truth.argumentsRaw().equals(raw)) return true;
      try {
        JsonNode wire = FixtureLoader.JSON.readTree(truth.argumentsRaw()),
            handedUp = FixtureLoader.JSON.readTree(raw);
        if (wire != null && !wire.isMissingNode() && wire.equals(handedUp)) return true;
      } catch (Exception notJson) {
        // Different text that is not JSON on both sides is a different value.
      }
    }
    return false;
  }

  public static Classified classify(
      Fixture fixture, CellResult result, List<String> typedErrorPrefixes) {
    JsonNode line = result.resultLine() == null ? MissingNode.getInstance() : result.resultLine(),
        caller = line.path("caller"),
        signals = line.path("terminal_signals");
    boolean error = false;
    for (JsonNode signal : signals) if (signal.asText().equals("error")) error = true;
    boolean limit = nonCompletion(caller.path("finish_reasons"));
    boolean timeout = false;
    for (JsonNode cause : line.path("error").path("chain"))
      if (cause.toString().toLowerCase(Locale.ROOT).matches("(?s).*(timeout|timed out).*"))
        timeout = true;
    List<JsonNode> effectiveInvocations = result.invocations();
    if (result.cell().surface().startsWith("model-")) {
      effectiveInvocations = new ArrayList<>();
      for (JsonNode call : caller.path("tool_calls")) {
        // A call handed up exactly as the wire carried it, for a call the wire left incomplete,
        // is the wire's own damage and not an alteration by the client. Whether the caller can
        // tell is decided below, from the finish reasons and the parse flag.
        if (verbatimIncomplete(fixture, call)) continue;
        boolean emptyZeroArgumentCall =
            call.path("name").asText().equals("flush_notes")
                && call.path("arguments_raw").isTextual()
                && call.path("arguments_raw").textValue().isEmpty();
        if (call.path("arguments_parsed_ok").asBoolean() || emptyZeroArgumentCall) {
          ObjectNode invocation =
              FixtureLoader.JSON.createObjectNode().put("name", call.path("name").asText());
          invocation.set(
              "args",
              emptyZeroArgumentCall
                  ? FixtureLoader.JSON.createObjectNode()
                  : call.path("arguments"));
          effectiveInvocations.add(invocation);
        }
      }
    }
    List<TruthCall> unmatched = new ArrayList<>(fixture.truth().calls());
    boolean altered = false;
    for (JsonNode invocation : effectiveInvocations) {
      TruthCall match = null;
      for (TruthCall call : unmatched)
        if (call.complete()
            && call.name().equals(invocation.path("name").asText())
            && Objects.equals(call.intendedArguments(), invocation.get("args"))) {
          match = call;
          break;
        }
      if (match == null) altered = true;
      else unmatched.remove(match);
    }
    List<ToolResult> feedback = new ArrayList<>();
    boolean feedbackUndecidable = false;
    for (int i = 1; i < result.requests().size(); i++) {
      String body = result.requests().get(i).body();
      // The server logs bodies up to 16 kB; a cut or non-JSON body cannot show a tool result.
      if (body != null && !body.isBlank() && !RequestBodies.parseable(body))
        feedbackUndecidable = true;
      for (ToolResult tool : RequestBodies.toolResults(fixture.wire(), body))
        if (unmatched.stream().anyMatch(c -> c.id().equals(tool.callId()))) feedback.add(tool);
    }
    ObjectNode attr = FixtureLoader.JSON.createObjectNode();
    attr.put("request_count", result.requests().size())
        .put("invocation_count", effectiveInvocations.size())
        .put("fed_back", !feedback.isEmpty())
        .put(
            "fed_back_text",
            Signals.cut(String.join("\n", feedback.stream().map(ToolResult::text).toList())));
    attr.set(
        "terminal_signals", signals.isArray() ? signals : FixtureLoader.JSON.createArrayNode());
    attr.put("tools_ran_before_error", error && !result.invocations().isEmpty());
    attr.set(
        "caller_finish_reasons",
        caller.path("finish_reasons").isArray()
            ? caller.get("finish_reasons")
            : FixtureLoader.JSON.createArrayNode());
    attr.put(
            "signal_only_in_callback",
            !limit
                && nonCompletion(caller.path("callback_only").path("intermediate_finish_reasons")))
        .put("elapsed_ms", result.elapsedMs())
        .put("timeout_fired", timeout)
        .put("watchdog_killed", result.watchdogKilled());
    boolean served =
        !result.requests().isEmpty()
            && result.requests().get(result.requests().size() - 1).servedAsScripted();
    if (result.requests().isEmpty())
      return new Classified(Category.HARNESS, "no request reached server", attr);
    if (result.recorderTorn()) return new Classified(Category.HARNESS, "recorder file torn", attr);
    if (!result.watchdogKilled() && result.exitCode() != null && result.exitCode() != 0)
      return new Classified(Category.HARNESS, "adapter exited " + result.exitCode(), attr);
    if ((result.resultLine() == null || !line.path("format").asText().equals("tcf.result/v1"))
        && !(result.watchdogKilled() && served))
      return new Classified(Category.HARNESS, "missing or malformed result line", attr);
    if (altered)
      return new Classified(
          Category.EXECUTED_ALTERED, "invocation differs from complete wire call", attr);
    if (result.watchdogKilled() && served)
      return new Classified(Category.HANG, "watchdog after scripted response", attr);
    if (error) {
      String type = line.path("error").path("chain").path(0).path("type").asText();
      return new Classified(
          typedErrorPrefixes.stream().anyMatch(type::startsWith)
              ? Category.ERROR_TYPED
              : Category.ERROR_UNTYPED,
          type,
          attr);
    }
    if (!unmatched.isEmpty() && feedbackUndecidable)
      return new Classified(Category.HARNESS, "later request body is not parseable JSON", attr);
    if (!unmatched.isEmpty()
        && !feedback.isEmpty()
        && signals.size() == 1
        && signals.path(0).asText().equals("complete")
        && !limit)
      return new Classified(
          Category.FED_BACK_HIDDEN, "unexecuted call returned as tool result", attr);
    boolean invalid = false;
    for (JsonNode call : caller.path("tool_calls"))
      if (call.path("arguments_parsed_ok").isBoolean()
          && !call.get("arguments_parsed_ok").asBoolean()) invalid = true;
    if (!unmatched.isEmpty() && (limit || invalid))
      return new Classified(Category.SURFACED, "damage visible to caller", attr);
    if (!unmatched.isEmpty())
      return new Classified(Category.DROPPED, "call not executed or surfaced", attr);
    return new Classified(Category.EXECUTED_AS_SENT, "all complete calls executed as sent", attr);
  }
}
