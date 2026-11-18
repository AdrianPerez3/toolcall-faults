package toolcallfaults.runner.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import toolcallfaults.runner.cell.*;
import toolcallfaults.runner.fixture.*;

public final class Matrix {
  private final Path root;
  private final JsonNode config;
  private final Map<String, AdapterManifest> manifests;
  private final Map<String, JsonNode> scenarios;
  private final List<Fixture> fixtures;

  private Matrix(
      Path root,
      JsonNode config,
      Map<String, AdapterManifest> manifests,
      Map<String, JsonNode> scenarios,
      List<Fixture> fixtures) {
    this.root = root;
    this.config = config;
    this.manifests = manifests;
    this.scenarios = scenarios;
    this.fixtures = fixtures;
  }

  public static Matrix load(Path root) throws IOException {
    try {
      JsonNode config = FixtureLoader.JSON.readTree(root.resolve("matrix.json").toFile());
      if (config == null || !config.path("format").asText().equals("tcf.matrix/v1"))
        throw new IOException("Invalid matrix format");
      Map<String, AdapterManifest> manifests = new LinkedHashMap<>();
      var names = config.path("targets").fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        manifests.put(name, AdapterManifest.load(root.resolve("adapters/" + name)));
      }
      Map<String, JsonNode> scenarios = new LinkedHashMap<>();
      try (var files = Files.walk(root.resolve("suite/scenarios"))) {
        for (Path p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
          var s = FixtureLoader.JSON.readTree(p.toFile());
          scenarios.put(s.path("id").asText(), s);
        }
      }
      List<Fixture> fixtures = new ArrayList<>();
      try (var files = Files.walk(root.resolve("suite/fixtures"))) {
        for (Path p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
          var f = FixtureLoader.loadVerified(p);
          fixtures.add(f);
          if (f.scenario().equals("truncation.complete-args-under-limit")
              && f.tools().equals(List.of("set_level")))
            fixtures.add(ControlFixtures.levelControl(f));
        }
      }
      return new Matrix(
          root, config, Map.copyOf(manifests), Map.copyOf(scenarios), List.copyOf(fixtures));
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Cannot load matrix", e);
    }
  }

  public JsonNode config() {
    return config;
  }

  public Map<String, AdapterManifest> manifests() {
    return manifests;
  }

  public List<Cell> cells(String role, int jdk) {
    return selection(role, jdk).run();
  }

  public List<ControlFailures.NotRun> notRun(String role, int jdk) {
    return selection(role, jdk).notRun();
  }

  private ControlFailures.Selection selection(String role, int jdk) {
    if (!Set.of("current", "control", "next").contains(role))
      throw new IllegalArgumentException("Unknown version role " + role);
    List<Cell> cells = new ArrayList<>();
    for (String target : new TreeSet<>(manifests.keySet())) {
      var manifest = manifests.get(target);
      var targetConfig = config.path("targets").path(target);
      JsonNode versionNode = targetConfig.path("versions").path(role);
      if (versionNode.isMissingNode() || versionNode.isNull()) continue;
      String version =
          versionNode.isTextual() ? versionNode.asText() : versionNode.path("version").asText();
      if (version.equals("@pom"))
        try {
          version = AdapterBuild.defaultVersion(root.resolve("adapters/" + target));
        } catch (Exception e) {
          throw new IllegalArgumentException("Cannot resolve POM version for " + target, e);
        }
      for (var module : manifest.modules().entrySet())
        for (JsonNode surfaceNode : targetConfig.path("surfaces")) {
          String surface = surfaceNode.asText();
          if (!manifest.surfaces().contains(surface))
            throw new IllegalArgumentException("Undeclared surface " + surface + " for " + target);
          for (Fixture f : fixtures) {
            JsonNode scenario = scenarios.get(f.scenario());
            if (scenario == null)
              throw new IllegalArgumentException("Unknown scenario " + f.scenario());
            if (!module.getValue().wire().equals(f.wire())
                || !module.getValue().supportsScenario(f.scenario())
                || f.stream() != surface.endsWith("-stream")
                || !contains(scenario.path("surfaces"), surface)
                || f.fromRecording() && !surface.startsWith("model-")) continue;
            if (versionNode.path("scenarios").isArray()
                && !contains(versionNode.get("scenarios"), f.scenario())) continue;
            boolean primitiveControl =
                f.scenario().equals("tools.single-call") && f.tools().equals(List.of("set_level"));
            if (!f.fromRecording()
                && !primitiveControl
                && !contains(scenario.path("tools"), f.tools().get(0))) continue;
            if (unsupported(manifest, module.getKey(), surface, f)) continue;
            for (String client :
                module.getValue().clientsFor(scenario.path("http_client_axis").asBoolean()))
              cells.add(
                  new Cell(
                      target,
                      version,
                      module.getKey(),
                      surface,
                      f.id(),
                      f.tools().get(0),
                      jdk,
                      client));
          }
        }
    }
    cells.sort(Comparator.comparing(Cell::key));
    return ControlFailures.select(cells, manifests);
  }

  private static boolean unsupported(
      AdapterManifest manifest, String module, String surface, Fixture f) {
    for (JsonNode n : manifest.unsupported())
      if ((n.path("module").isMissingNode() || matches(n, "module", module))
          && matches(n, "surface", surface)
          && matches(n, "wire", f.wire())
          && matches(n, "scenario", f.scenario())) return true;
    return false;
  }

  private static boolean matches(JsonNode n, String field, String value) {
    return n.path(field).asText().equals("*") || n.path(field).asText().equals(value);
  }

  private static boolean contains(JsonNode values, String value) {
    for (JsonNode n : values) if (n.asText().equals(value)) return true;
    return false;
  }
}
