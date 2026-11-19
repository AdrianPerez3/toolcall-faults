package toolcallfaults.runner.lint;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises host and image preparation with a deterministic, network-free wrapper. */
class MavenBootstrapTest {
  private final Path root = Path.of("..").toAbsolutePath().normalize();
  private static final String ALTERNATE = "https://repo1.maven.org/maven2";
  private static final String SETTINGS = ".github/scripts/maven-central-fallback.xml";

  private static void executable(Path file, String body) throws Exception {
    Files.writeString(file, "#!/bin/sh\n" + body);
    assertTrue(file.toFile().setExecutable(true));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 0})
  void retries_only_root_model_resolution_and_keeps_the_successful_configuration(
      int successAt, @TempDir Path temp) throws Exception {
    var workflow = Files.readAllLines(root.resolve(".github/workflows/ci.yml"));
    int step = workflow.indexOf("      - name: Fetch Maven");
    assertTrue(step >= 0, "missing bootstrap step");
    var run = workflow.get(step + 1).strip();
    assertTrue(run.startsWith("run: "));
    checkPreparation(temp, successAt, run.substring(5), false);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 0})
  void image_retries_only_dependency_downloads_and_preserves_the_chosen_repository(
      int successAt, @TempDir Path temp) throws Exception {
    checkPreparation(temp, successAt, "sh .github/scripts/prepare-maven.sh --image mavenrc", true);
  }

  private void checkPreparation(Path temp, int successAt, String command, boolean image)
      throws Exception {
    Files.createSymbolicLink(temp.resolve(".github"), root.resolve(".github"));
    Path trace = Files.createFile(temp.resolve("calls"));
    Path exports = Files.createFile(temp.resolve("github-env"));
    executable(temp.resolve("mvnw"), """
        printf 'maven|%s|%s|%s|%s\\n' "${MVNW_REPOURL:-}" "$MAVEN_CONFIG" "$MAVEN_ARGS" "$*" >> calls
        [ "$(grep -c '^maven|' calls)" -eq "$SUCCESS_AT" ]
        """);
    executable(temp.resolve("sleep"), "echo sleep \"$@\" >> calls\n");
    Path startup = temp.resolve("mavenrc");
    String existing = "export ALREADY_CONFIGURED=yes\n";
    Files.writeString(startup, existing);
    var process = new ProcessBuilder("sh", "-ec", command).directory(temp.toFile())
        .redirectErrorStream(true);
    var env = process.environment();
    env.put("PATH", temp + ":" + env.get("PATH"));
    env.put("GITHUB_ENV", exports.toString());
    env.put("SUCCESS_AT", Integer.toString(successAt));
    env.put("MAVEN_ARGS", "-ntp");
    env.put("MAVEN_CONFIG", "-Dstyle.color=never");
    env.remove("MVNW_REPOURL");
    var child = process.start();
    String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(successAt == 0 ? 1 : 0, child.waitFor(), output);

    String original = env.get("MAVEN_CONFIG");
    String alternateConfig = original + " --global-settings " + SETTINGS;
    String goals = "|-ntp|-q -B -U " + (image ? "dependency:go-offline" : "-N validate");
    String usual = "maven||" + original + goals;
    String alternate = "maven|" + ALTERNATE + "|" + alternateConfig + goals;
    var attempts = List.of(usual, "sleep 15", alternate, "sleep 15", usual);
    int count = successAt == 0 ? 3 : successAt;
    assertEquals(attempts.subList(0, 2 * count - 1), Files.readAllLines(trace));
    assertEquals(!image && successAt == 2
            ? List.of("MVNW_REPOURL=" + ALTERNATE, "MAVEN_CONFIG=" + alternateConfig) : List.of(),
        Files.readAllLines(exports));
    if (image && successAt == 2) {
      // A new shell sees the endpoint without recording any previous build arguments.
      String fixedSettings = "\nexport MVNW_REPOURL=" + ALTERNATE
          + "\nexport MAVEN_CONFIG=\"${MAVEN_CONFIG:+$MAVEN_CONFIG }--global-settings "
          + SETTINGS + "\"\n";
      assertEquals(existing + fixedSettings, Files.readString(startup));
      var later = new ProcessBuilder("sh", "-ec", """
          . ./mavenrc
          printf '%s\\n' "$ALREADY_CONFIGURED" "$MVNW_REPOURL" "$MAVEN_CONFIG"
          """).directory(temp.toFile());
      later.environment().put("MAVEN_CONFIG", "-o");
      var shell = later.start();
      String selected = new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, shell.waitFor());
      assertEquals("yes\n" + ALTERNATE + "\n-o --global-settings " + SETTINGS + "\n", selected);
    } else {
      assertEquals(existing, Files.readString(startup));
    }
  }

  @Test
  void a_failing_package_step_is_run_only_once(@TempDir Path temp) throws Exception {
    var line = Files.readAllLines(root.resolve("Dockerfile")).stream()
        .filter(s -> s.startsWith("RUN ") && s.contains(" package ")).findFirst().orElseThrow();
    executable(temp.resolve("mvnw"), "echo called >> calls\nexit 17\n");
    var shell = new ProcessBuilder("sh", "-ec", line.substring(4)).directory(temp.toFile()).start();
    assertEquals(17, shell.waitFor());
    assertEquals(List.of("called"), Files.readAllLines(temp.resolve("calls")));
  }

  @Test
  void test_goals_cannot_be_passed_to_the_retry_helper(@TempDir Path temp) throws Exception {
    executable(temp.resolve("mvnw"), "echo called >> calls\n");
    var shell = new ProcessBuilder("sh", root.resolve(".github/scripts/prepare-maven.sh").toString(),
        "--image", "mavenrc", "verify").directory(temp.toFile()).start();
    assertEquals(2, shell.waitFor());
    assertFalse(Files.exists(temp.resolve("calls")));
  }

  @Test
  void alternate_global_settings_redirect_only_central_and_keep_mavens_http_blocker()
      throws Exception {
    var mirrors = readXml(SETTINGS);
    var xpath = XPathFactory.newInstance().newXPath();
    assertEquals("2", xpath.evaluate("count(/settings/mirrors/mirror)", mirrors));
    assertEquals(ALTERNATE,
        xpath.evaluate("/settings/mirrors/mirror[mirrorOf='central']/url", mirrors));
    assertEquals("true",
        xpath.evaluate("/settings/mirrors/mirror[mirrorOf='external:http:*']/blocked", mirrors));
  }

  @Test
  void root_validate_cannot_inherit_or_run_a_plugin_execution() throws Exception {
    var pom = readXml("pom.xml");
    var xpath = XPathFactory.newInstance().newXPath();
    assertEquals("pom", xpath.evaluate("/project/packaging", pom));
    assertEquals("0", xpath.evaluate("count(/project/parent)", pom));
    assertEquals("0", xpath.evaluate(
        "count(//build/plugins/plugin/executions/execution[not(phase) or phase='validate'])", pom));
  }

  private org.w3c.dom.Document readXml(String path) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder().parse(root.resolve(path).toFile());
  }
}
