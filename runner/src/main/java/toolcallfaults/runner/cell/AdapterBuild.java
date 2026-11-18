package toolcallfaults.runner.cell;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

public final class AdapterBuild {
  private AdapterBuild() {}

  public static BuiltAdapter ensure(Path root, String target, String version, String variant)
      throws Exception {
    validateIdentity(target, version);
    root = root.toAbsolutePath().normalize();
    Path dir = root.resolve("adapters").resolve(target);
    if (!Files.exists(dir.resolve("adapter.json")))
      throw new IllegalArgumentException("unknown target " + target);
    variant = variant == null ? "" : variant;
    if (!variant.isEmpty() && !(target.equals("langchain4j") && variant.equals("okhttp")))
      throw new IllegalArgumentException("Build variant not supported: " + variant);
    AdapterManifest manifest = AdapterManifest.load(dir);
    Path out = dir.resolve("target").resolve(version + (variant.isEmpty() ? "" : "-" + variant)),
        classes = out.resolve("classes"),
        cp = out.resolve("cp.txt"),
        deps = out.resolve("deps.txt"),
        stamp = out.resolve(".built");
    List<Path> sources;
    try (var stream = Files.walk(dir.resolve("src/main/java"))) {
      sources = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
    List<Path> inputs = new ArrayList<>(sources);
    inputs.add(dir.resolve("pom.xml"));
    inputs.add(dir.resolve("adapter.json"));
    try (var stream = Files.walk(root.resolve("kit/target/classes"))) {
      inputs.addAll(stream.filter(Files::isRegularFile).toList());
    }
    boolean fresh =
        Files.exists(stamp) && Files.exists(cp) && Files.exists(classes.resolve("tcf-deps.txt"));
    if (fresh)
      for (Path p : inputs)
        if (Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(stamp)) > 0) {
          fresh = false;
          break;
        }
    if (!fresh) {
      Files.createDirectories(classes);
      List<String> maven =
          new ArrayList<>(
              List.of(
                  root.resolve("mvnw").toString(),
                  "-q",
                  "-B",
                  "-f",
                  dir.resolve("pom.xml").toString(),
                  "-D" + manifest.versionProperty() + "=" + version));
      manifest
          .extraProperties()
          .path(version)
          .fields()
          .forEachRemaining(e -> maven.add("-D" + e.getKey() + "=" + e.getValue().asText()));
      if (!variant.isEmpty()) maven.add("-P" + variant);
      List<String> command = new ArrayList<>(maven);
      command.addAll(
          List.of(
              "org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath",
              "-Dmdep.outputFile=" + cp));
      run(command, root);
      command = new ArrayList<>(maven);
      command.addAll(
          List.of(
              "org.apache.maven.plugins:maven-dependency-plugin:3.9.0:list",
              "-DoutputFile=" + deps,
              "-DincludeScope=runtime"));
      run(command, root);
      command =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                  "-parameters",
                  "--release",
                  "17",
                  "-cp",
                  Files.readString(cp).strip()
                      + File.pathSeparator
                      + root.resolve("kit/target/classes"),
                  "-d",
                  classes.toString()));
      for (Path source : sources) command.add(source.toString());
      run(command, root);
      Files.copy(deps, classes.resolve("tcf-deps.txt"), StandardCopyOption.REPLACE_EXISTING);
      Files.writeString(stamp, "built\n");
    }
    List<String> classpath =
        new ArrayList<>(List.of(classes.toString(), root.resolve("kit/target/classes").toString()));
    classpath.addAll(
        List.of(
            Files.readString(cp).strip().split(java.util.regex.Pattern.quote(File.pathSeparator))));
    return new BuiltAdapter(
        target,
        version,
        manifest.mainClass(),
        List.copyOf(classpath),
        manifest.typedErrorPrefixes());
  }

  private static void run(List<String> command, Path root) throws Exception {
    Process p =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    if (p.waitFor() != 0) throw new IOException("Adapter build failed: " + command.get(0));
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(".");
    for (var build : plannedBuilds(root))
      ensure(root, build.target(), build.version(), build.variant());
  }

  record Build(String target, String version, String variant) {}

  static List<Build> plannedBuilds(Path root) throws Exception {
    var matrix = toolcallfaults.runner.matrix.Matrix.load(root);
    Set<Build> builds = new LinkedHashSet<>();
    for (String role : List.of("current", "control", "next"))
      for (var cell : matrix.cells(role, Runtime.version().feature())) {
        builds.add(new Build(cell.target(), cell.version(), variant(cell)));
      }
    var anchors =
        toolcallfaults.runner.fixture.FixtureLoader.JSON.readTree(
            root.resolve("expectations/anchors.json").toFile());
    for (var entry : anchors.path("entries"))
      builds.add(new Build(entry.path("target").asText(), entry.path("version").asText(), ""));
    return List.copyOf(builds);
  }

  public static String variant(Cell cell) {
    return cell.target().equals("langchain4j") && cell.httpClient().equals("okhttp")
        ? "okhttp"
        : "";
  }

  public static void validateIdentity(String target, String version) {
    if (!target.matches("[a-z0-9-]+")
        || !version.matches("[a-zA-Z0-9_.-]+")
        || version.equals("..")) throw new IllegalArgumentException("Invalid target or version");
  }

  public static String key(Cell cell) {
    return cell.target() + "@" + cell.version() + ":" + variant(cell);
  }

  public static String defaultVersion(Path adapterDir) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory
        .newDocumentBuilder()
        .parse(adapterDir.resolve("pom.xml").toFile())
        .getElementsByTagName("target.version")
        .item(0)
        .getTextContent()
        .strip();
  }
}
