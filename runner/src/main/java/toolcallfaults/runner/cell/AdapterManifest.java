package toolcallfaults.runner.cell;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;

public record AdapterManifest(
    String target,
    List<String> coordinates,
    String versionProperty,
    JsonNode extraProperties,
    String mainClass,
    Map<String, Module> modules,
    List<String> surfaces,
    List<String> typedErrorPrefixes,
    JsonNode unsupported,
    List<ControlFailure> controlFailures,
    List<String> configNotes,
    List<String> docs) {
  public record Module(
      String wire, String httpClient, List<String> scenarios, List<String> httpClients) {
    public Module {
      scenarios = List.copyOf(scenarios);
      httpClients = httpClients.isEmpty() ? List.of(httpClient) : List.copyOf(httpClients);
      if (!httpClients.contains(httpClient)
          || new HashSet<>(httpClients).size() != httpClients.size())
        throw new IllegalArgumentException("HTTP clients must be unique and include the default");
    }

    public List<String> clientsFor(boolean axis) {
      return axis ? httpClients : List.of(httpClient);
    }

    public boolean supportsScenario(String scenario) {
      return scenarios.isEmpty() || scenarios.contains(scenario);
    }
  }

  public record ControlFailure(
      String module,
      String surface,
      String wire,
      String tool,
      String category,
      String errorClass,
      String basis,
      String notes,
      List<String> versions) {
    public boolean appliesToVersion(String version) {
      return versions.isEmpty() || versions.contains(version);
    }

    public boolean selects(Cell cell) {
      return appliesToVersion(cell.version())
          && module.equals(cell.module())
          && surface.equals(cell.surface())
          && wire.equals(cell.fixtureId().split("/", 2)[0])
          && tool.equals(cell.tool());
    }

    public boolean matches(String observedCategory, JsonNode result) {
      return !observedCategory.equals("EXECUTED_AS_SENT")
          && category.equals(observedCategory)
          && result != null
          && errorClass.equals(result.path("error").path("chain").path(0).path("type").asText());
    }
  }

  public Optional<ControlFailure> controlFailure(Cell cell) {
    return controlFailures.stream().filter(f -> f.selects(cell)).findFirst();
  }

  public static boolean isControl(String scenario) {
    return Set.of("tools.single-call", "tools.parallel-sequential", "tools.zero-arg-call")
        .contains(scenario);
  }

  private static List<String> strings(JsonNode node) {
    List<String> out = new ArrayList<>();
    node.forEach(n -> out.add(n.asText()));
    return List.copyOf(out);
  }

  public static AdapterManifest load(Path dir) throws IOException {
    JsonNode n = FixtureLoader.JSON.readTree(dir.resolve("adapter.json").toFile());
    if (!n.path("format").asText().equals("tcf.adapter/v1"))
      throw new IOException("Invalid adapter format: " + dir);
    Map<String, Module> modules = new LinkedHashMap<>();
    List<ControlFailure> failures = new ArrayList<>();
    for (JsonNode f : n.path("control_failures"))
      failures.add(
          new ControlFailure(
              f.path("module").asText(),
              f.path("surface").asText(),
              f.path("wire").asText(),
              f.path("tool").asText(),
              f.path("category").asText(),
              f.path("error_class").asText(),
              f.path("basis").asText(),
              f.path("notes").asText(),
              strings(f.path("versions"))));
    n.path("modules")
        .fields()
        .forEachRemaining(
            e ->
                modules.put(
                    e.getKey(),
                    new Module(
                        e.getValue().path("wire").asText(),
                        e.getValue().path("http_client").asText(),
                        strings(e.getValue().path("scenarios")),
                        strings(e.getValue().path("http_clients")))));
    return new AdapterManifest(
        n.path("target").asText(),
        strings(n.path("coordinates")),
        n.path("version_property").asText(),
        n.path("extra_properties"),
        n.path("main_class").asText(),
        Collections.unmodifiableMap(modules),
        strings(n.path("surfaces")),
        strings(n.path("typed_error_prefixes")),
        n.path("unsupported"),
        List.copyOf(failures),
        strings(n.path("config_notes")),
        strings(n.path("docs")));
  }
}
