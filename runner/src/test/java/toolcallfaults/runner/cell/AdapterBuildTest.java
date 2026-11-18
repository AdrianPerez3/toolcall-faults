package toolcallfaults.runner.cell;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterBuildTest {
  @TempDir Path root;

  @Test
  void frozen_anchor_builds_survive_current_pin_updates() throws Exception {
    for (String directory : List.of("suite/fixtures", "suite/scenarios")) {
      var sourceRoot = Path.of("..", directory);
      try (var files = Files.walk(sourceRoot)) {
        for (var source : files.filter(Files::isRegularFile).toList()) {
          var target = root.resolve(directory).resolve(sourceRoot.relativize(source));
          Files.createDirectories(target.getParent());
          Files.copy(source, target);
        }
      }
    }
    for (String target : List.of("spring-ai", "langchain4j", "openai-java", "anthropic-sdk-java")) {
      var dir = root.resolve("adapters/" + target);
      Files.createDirectories(dir);
      for (String name : List.of("adapter.json", "pom.xml"))
        Files.copy(Path.of("../adapters", target, name), dir.resolve(name));
    }
    Files.copy(Path.of("../matrix.json"), root.resolve("matrix.json"));
    Files.createDirectories(root.resolve("expectations"));
    Files.copy(Path.of("../expectations/anchors.json"), root.resolve("expectations/anchors.json"));
    var pom = root.resolve("adapters/spring-ai/pom.xml");
    Files.writeString(
        pom,
        Files.readString(pom)
            .replaceAll(
                "<target.version>[^<]+</target.version>",
                "<target.version>2.1.0-RC2</target.version>"));
    var builds = AdapterBuild.plannedBuilds(root);
    assertTrue(builds.contains(new AdapterBuild.Build("spring-ai", "2.1.0-RC2", "")));
    assertTrue(builds.contains(new AdapterBuild.Build("spring-ai", "2.0.0", "")));
    assertTrue(
        builds.contains(new AdapterBuild.Build("spring-ai", "2.0.2", "")),
        "frozen anchor must be available offline");
    assertTrue(
        builds.contains(
            new AdapterBuild.Build(
                "langchain4j",
                AdapterBuild.defaultVersion(root.resolve("adapters/langchain4j")),
                "okhttp")));
  }

  @Test
  void identity_is_validated_before_reading_an_adapter() {
    assertThrows(
        IllegalArgumentException.class, () -> AdapterBuild.validateIdentity("../adapter", "1"));
    assertThrows(
        IllegalArgumentException.class, () -> AdapterBuild.validateIdentity("langchain4j", ".."));
    assertDoesNotThrow(() -> AdapterBuild.validateIdentity("langchain4j", "1.23.0"));
  }
}
