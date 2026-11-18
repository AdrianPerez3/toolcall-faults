package toolcallfaults.runner.results;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;

public final class Stability {
  private Stability() {}

  public static boolean complete(List<RunRecord> rows) {
    int size = rows.size();
    if (size != 3 && size != 10) return false;
    var ids = new TreeSet<Integer>();
    rows.forEach(r -> ids.add(r.rep()));
    return ids.size() == size && ids.first() == 1 && ids.last() == size;
  }

  public static double flakyPercent(int unstableCells, int cells) {
    if (unstableCells < 0 || cells < unstableCells)
      throw new IllegalArgumentException("Invalid cell counts");
    return cells == 0 ? 0.0 : 100.0 * unstableCells / cells;
  }

  public static List<String> differingAcrossJdks(Map<Integer, List<RunRecord>> byJdk) {
    if (byJdk.size() < 2) throw new IllegalArgumentException("At least two JDK runs required");
    Map<String, Set<Object>> signatures = new TreeMap<>();
    Set<String> expected = null;
    for (var run : new TreeMap<>(byJdk).entrySet()) {
      Map<String, List<RunRecord>> groups = new TreeMap<>();
      for (var r : run.getValue()) {
        String[] parts = r.cell().split("\\|", -1);
        if (parts.length != 7 || !parts[5].equals("jdk" + run.getKey()))
          throw new IllegalArgumentException(
              "Cell does not match JDK " + run.getKey() + ": " + r.cell());
        var identity = new ArrayList<>(List.of(parts));
        identity.remove(5);
        groups.computeIfAbsent(String.join("|", identity), k -> new ArrayList<>()).add(r);
      }
      if (groups.isEmpty() || expected != null && !expected.equals(groups.keySet()))
        throw new IllegalArgumentException("JDK runs must contain the same nonempty set of cells");
      expected = new TreeSet<>(groups.keySet());
      for (var group : groups.entrySet()) {
        var rows = group.getValue();
        if (!complete(rows)
            || !of(rows).stable()
            || rows.stream().anyMatch(r -> r.category().equals("HARNESS")))
          throw new IllegalArgumentException(
              "Incomplete, flaky or HARNESS cell on JDK " + run.getKey() + ": " + group.getKey());
        signatures
            .computeIfAbsent(group.getKey(), k -> new HashSet<>())
            .add(signature(rows.get(0)));
      }
    }
    return signatures.entrySet().stream()
        .filter(e -> e.getValue().size() > 1)
        .map(Map.Entry::getKey)
        .toList();
  }

  public static Object signature(RunRecord record) {
    var signature = FixtureLoader.JSON.createObjectNode().put("category", record.category());
    signature.set(
        "invocations", arguments(record.surface(), record.adapter(), record.invocations()));
    signature.put(
        "error_class",
        record.adapter() == null
            ? ""
            : record.adapter().path("error").path("chain").path(0).path("type").asText());
    signature.put("request_count", record.server().path("request_count").asInt());
    return signature;
  }

  public static ArrayNode arguments(String surface, JsonNode adapter, JsonNode invocations) {
    var args = FixtureLoader.JSON.createArrayNode();
    if (surface.startsWith("model-")) {
      if (adapter != null)
        for (var call : adapter.path("caller").path("tool_calls"))
          args.add(
              call.path("arguments_parsed_ok").asBoolean()
                  ? call.path("arguments")
                  : call.path("arguments_raw"));
    } else {
      for (var invocation : invocations) args.add(invocation.path("args"));
    }
    return args;
  }

  public static Status of(List<RunRecord> repetitions) {
    Map<String, Integer> distribution = new TreeMap<>();
    Set<Object> signatures = new HashSet<>();
    for (var row : repetitions) {
      distribution.merge(row.category(), 1, Integer::sum);
      signatures.add(signature(row));
    }
    return new Status(
        !repetitions.isEmpty() && signatures.size() == 1,
        Collections.unmodifiableMap(distribution));
  }
}
