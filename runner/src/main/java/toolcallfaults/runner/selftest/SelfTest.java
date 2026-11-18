package toolcallfaults.runner.selftest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import toolcallfaults.runner.cell.*;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.server.*;

public final class SelfTest {
  private SelfTest() {}

  public static void server(Path suite) throws Exception {
    List<Path> files = new ArrayList<>();
    for (String dir : List.of("fixtures", "selftest"))
      try (var paths = Files.walk(suite.resolve(dir))) {
        var fixtures = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        if (fixtures.isEmpty()) throw new IOException("No server fixtures in " + dir);
        files.addAll(fixtures);
      }
    for (Path path : files) {
      Fixture f = FixtureLoader.load(path);
      for (int i = 0; i < f.exchanges().size(); i++)
        verify(f, i, path.startsWith(suite.resolve("selftest")));
    }
    System.out.println("server: " + files.size() + " of " + files.size() + " fixtures byte-exact");
  }

  static Fixture levelControl(Fixture source) throws Exception {
    return ControlFixtures.levelControl(source);
  }

  @FunctionalInterface
  interface ControlRunner {
    CellResult run(Cell cell, Fixture fixture, int rep) throws Exception;
  }

  public static void controls(Path root, String target) throws Exception {
    Map<String, BuiltAdapter> adapters = new HashMap<>();
    controls(
        root,
        target,
        (cell, fixture, rep) -> {
          BuiltAdapter adapter = adapters.get(AdapterBuild.key(cell));
          if (adapter == null) {
            adapter =
                AdapterBuild.ensure(
                    root, cell.target(), cell.version(), AdapterBuild.variant(cell));
            adapters.put(AdapterBuild.key(cell), adapter);
          }
          try (var server = ReplayServer.start(0)) {
            return new CellRunner(server, 3000, 12000).run(cell, fixture, adapter, rep);
          }
        });
  }

  static void controls(Path root, String target, ControlRunner runner) throws Exception {
    List<Fixture> fixtures = new ArrayList<>();
    try (var files = Files.walk(root.resolve("suite/fixtures"))) {
      for (Path p : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
        Fixture f = FixtureLoader.load(p);
        if (AdapterManifest.isControl(f.scenario())) fixtures.add(f);
        if (f.scenario().equals("truncation.complete-args-under-limit")
            && f.tools().equals(List.of("set_level"))) fixtures.add(levelControl(f));
      }
    }
    int total = 0, passed = 0, declared = 0;
    boolean missingDeclaration = false;
    try (var dirs = Files.list(root.resolve("adapters"))) {
      for (Path dir : dirs.sorted().toList()) {
        if (!Files.exists(dir.resolve("adapter.json"))
            || target != null && !dir.getFileName().toString().equals(target)) continue;
        var manifest = toolcallfaults.runner.cell.AdapterManifest.load(dir);
        String version = toolcallfaults.runner.cell.AdapterBuild.defaultVersion(dir);
        Set<AdapterManifest.ControlFailure> seen = new HashSet<>();
        Set<String> missingControls = new TreeSet<>();
        for (var module : manifest.modules().entrySet())
          for (String surface : manifest.surfaces()) {
            String selection = module.getKey() + "/" + surface;
            missingControls.add(selection);
            for (Fixture f : fixtures) {
              if (!module.getValue().supportsScenario(f.scenario())
                  || !f.wire().equals(module.getValue().wire())
                  || f.stream() != surface.endsWith("-stream")) continue;
              boolean axis =
                  module.getValue().httpClients().size() > 1
                      && FixtureLoader.JSON
                          .readTree(
                              root.resolve(
                                      "suite/scenarios/"
                                          + f.scenario().replaceFirst("\\.", "/")
                                          + ".json")
                                  .toFile())
                          .path("http_client_axis")
                          .asBoolean();
              for (String client : module.getValue().clientsFor(axis)) {
                var cell =
                    new toolcallfaults.runner.cell.Cell(
                        manifest.target(),
                        version,
                        module.getKey(),
                        surface,
                        f.id(),
                        f.tools().get(0),
                        Runtime.version().feature(),
                        client);
                total++;
                if (f.scenario().equals("tools.single-call") && f.tools().equals(List.of("record_note")))
                  missingControls.remove(selection);
                var declaration = manifest.controlFailure(cell).orElse(null);
                if (declaration != null) seen.add(declaration);
                boolean ok = true;
                for (int rep = 1; rep <= 3; rep++) {
                  var result = runner.run(cell, f, rep);
                  var classified =
                      toolcallfaults.runner.oracle.Oracle.classify(
                          f, result, manifest.typedErrorPrefixes());
                  var saved = FixtureLoader.JSON.createObjectNode();
                  saved
                      .put("cell", cell.key())
                      .put("category", classified.category().name())
                      .put("reason", classified.reason())
                      .put("flagged", result.flagged());
                  saved.set("result", result.resultLine());
                  saved.set("attributes", classified.attributes());
                  if (declaration != null) {
                    saved.put("basis", declaration.basis()).put("notes", declaration.notes());
                  }
                  saved.set("invocations", FixtureLoader.JSON.valueToTree(result.invocations()));
                  saved.set("exchanges", FixtureLoader.JSON.valueToTree(f.exchanges()));
                  Path evidence =
                      root.resolve(
                          ".local/controls/"
                              + manifest.target()
                              + "/"
                              + module.getKey()
                              + "/"
                              + surface
                              + "/jdk"
                              + Runtime.version().feature()
                              + "/"
                              + client
                              + "/"
                              + f.id()
                              + "-"
                              + rep
                              + ".json");
                  Files.createDirectories(evidence.getParent());
                  Files.writeString(
                      evidence,
                      FixtureLoader.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(saved)
                          + "\n");
                  if (result.flagged())
                    throw new IOException(
                        "Private-reporting review required: request cap reached; control evidence"
                            + " is local");
                  boolean matches =
                      declaration == null
                          ? classified.category()
                              == toolcallfaults.runner.oracle.Category.EXECUTED_AS_SENT
                          : declaration.matches(classified.category().name(), result.resultLine());
                  if (!matches) {
                    ok = false;
                    System.err.println(
                        cell.key()
                            + " rep "
                            + rep
                            + ": "
                            + classified.category()
                            + " "
                            + classified.reason());
                  }
                }
                if (ok) {
                  if (declaration == null) passed++;
                  else declared++;
                }
                System.out.println(
                    "control "
                        + cell.key()
                        + " "
                        + (ok
                            ? (declaration == null
                                ? "OK 3/3"
                                : "DECLARED 3/3 " + declaration.basis())
                            : "FAIL"));
              }
            }
          }
        if (!missingControls.isEmpty())
          throw new IOException(manifest.target() + ": no normal control for " + missingControls);
        if (!seen.containsAll(
            manifest.controlFailures().stream()
                .filter(f -> f.appliesToVersion(version))
                .toList())) {
          missingDeclaration = true;
          System.err.println(manifest.target() + ": control failure declaration was not exercised");
        }
      }
    }
    System.out.println(
        "controls: " + passed + " of " + total + " EXECUTED_AS_SENT; " + declared + " declared");
    if (total == 0 || passed + declared != total || missingDeclaration)
      throw new IOException("Control mismatch");
  }

  public static void anchors(Path root, String target, String wire) throws Exception {
    // Anchors for behaviours not reported upstream yet live in the private file.
    List<JsonNode> entries = new ArrayList<>();
    for (String dir : List.of("expectations", ".local/expectations")) {
      Path file = root.resolve(dir).resolve("anchors.json");
      if (!Files.exists(file) && dir.startsWith(".local/")) continue;
      JsonNode document = FixtureLoader.read(file);
      if (document == null || !document.path("format").asText().equals("tcf.anchors/v1")
          || !document.path("entries").isArray())
        throw new IOException("Invalid anchor register in " + file);
      if (dir.equals("expectations") && document.path("entries").isEmpty())
        throw new IOException("Empty public anchor register");
      for (var entry : document.path("entries")) {
        AnchorExpectation.validate(entry.path("expect"));
        entries.add(entry);
      }
    }
    int total = 0, matched = 0;
    for (JsonNode entry : entries) {
      if (target != null && !entry.path("target").asText().equals(target)
          || wire != null && !entry.path("fixture").asText().startsWith(wire + "/")) continue;
      total++;
      String name = entry.path("target").asText(), version = entry.path("version").asText();
      var adapter = toolcallfaults.runner.cell.AdapterBuild.ensure(root, name, version, "");
      var manifest =
          toolcallfaults.runner.cell.AdapterManifest.load(root.resolve("adapters").resolve(name));
      String fixture = entry.path("fixture").asText();
      var cell = anchorCell(entry, manifest, Runtime.version().feature());
      Fixture f = FixtureLoader.load(root.resolve("suite/fixtures/" + fixture + ".json"));
      int ok = 0;
      for (int rep = 1; rep <= 3; rep++)
        try (var server = ReplayServer.start(0)) {
          var result =
              new toolcallfaults.runner.cell.CellRunner(server, 3000, 12000)
                  .run(cell, f, adapter, rep);
          var classified =
              toolcallfaults.runner.oracle.Oracle.classify(f, result, adapter.typedErrorPrefixes());
          var actual = classified.attributes().deepCopy();
          var object = (com.fasterxml.jackson.databind.node.ObjectNode) actual;
          object.put("category", classified.category().name());
          var invocations = object.putArray("invocations");
          result.invocations().forEach(i -> invocations.add(i.path("args")));
          JsonNode line = result.resultLine();
          if (line != null) {
            object.put("caller_text", line.path("caller").path("text").asText());
            object.set("caller_chunks", line.path("caller").path("chunks"));
            for (int i = 0; i < line.path("error").path("chain").size(); i++)
              object.put(
                  "error_type" + i, line.path("error").path("chain").path(i).path("type").asText());
          }
          Path evidence =
              root.resolve(
                  ".local/anchors/"
                      + name
                      + "/jdk"
                      + cell.jdk()
                      + "/"
                      + fixture
                      + "-"
                      + rep
                      + ".json");
          Files.createDirectories(evidence.getParent());
          var saved = FixtureLoader.JSON.createObjectNode();
          saved.set("actual", actual);
          saved.set("result", line);
          saved.put("flagged", result.flagged());
          saved.put("exit_code", result.exitCode());
          Files.writeString(
              evidence,
              FixtureLoader.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(saved) + "\n");
          if (result.flagged())
            throw new IOException(
                "Private-reporting review required: request cap reached; evidence saved locally");
          List<String> differences = new ArrayList<>();
          var fields = entry.path("expect").fields();
          while (fields.hasNext()) {
            var expected = fields.next();
            String key = expected.getKey();
            boolean contains = key.endsWith("_contains");
            String field = contains ? key.substring(0, key.length() - 9) : key;
            boolean equal =
                contains
                    ? actual.path(field).isTextual()
                        && actual.path(field).textValue().contains(expected.getValue().textValue())
                    : actual.path(field).equals(expected.getValue());
            if (!equal)
              differences.add(
                  key + ": expected " + expected.getValue() + ", actual " + actual.path(field));
          }
          if (differences.isEmpty()) ok++;
          else
            System.err.println(cell.key() + " rep " + rep + ": " + String.join("; ", differences));
        }
      if (ok == 3) matched++;
      System.out.println(cell.key() + " " + (ok == 3 ? "OK" : "FAIL") + " " + ok + "/3");
    }
    System.out.println(
        "anchors: "
            + matched
            + " of "
            + total
            + " matched (JDK "
            + Runtime.version().feature()
            + "; probe JDK 21)");
    if (total == 0 || matched != total) throw new IOException("Anchor mismatch");
  }

  static Cell anchorCell(JsonNode entry, AdapterManifest manifest, int jdk) throws IOException {
    if (entry.path("jdk").asInt() != 21 || !Set.of(17, 21, 25).contains(jdk))
      throw new IOException("JDK 21 probe expectations require a reference JDK (17, 21 or 25)");
    String module = entry.path("module").asText();
    return new Cell(
        entry.path("target").asText(),
        entry.path("version").asText(),
        module,
        entry.path("surface").asText(),
        entry.path("fixture").asText(),
        entry.path("tool").asText(),
        jdk,
        manifest.modules().get(module).httpClient());
  }

  private static void verify(Fixture f, int index, boolean fullStall) throws Exception {
    JsonNode e = f.exchanges().get(index),
        r = e.path("response"),
        b = r.path("body"),
        x = f.x().path(Integer.toString(index));
    String form = b.fieldNames().next(), end = x.path("end").asText(r.path("end").asText());
    boolean streamed = Set.of("sse", "ndjson", "chunks_b64").contains(form);
    List<byte[]> parts = new ArrayList<>();
    if (streamed)
      for (JsonNode p : b.get(form))
        parts.add(
            form.equals("chunks_b64")
                ? Base64.getDecoder().decode(p.asText())
                : (p.asText() + (form.equals("ndjson") ? "\n" : ""))
                    .getBytes(StandardCharsets.UTF_8));
    else
      parts.add(
          (form.equals("json")
                  ? FixtureLoader.JSON.writeValueAsString(b.get(form))
                  : b.get(form).asText())
              .getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    int status = r.path("status").asInt();
    write(expected, "HTTP/1.1 " + status + (status == 200 ? " OK\r\n" : " Error\r\n"));
    var headers = r.path("headers").fields();
    while (headers.hasNext()) {
      var h = headers.next();
      write(expected, h.getKey() + ": " + h.getValue().asText() + "\r\n");
    }
    write(
        expected,
        streamed
            ? "Transfer-Encoding: chunked\r\n"
            : "Content-Length: " + parts.stream().mapToInt(p -> p.length).sum() + "\r\n");
    write(expected, "Connection: close\r\n\r\n");
    for (byte[] part : parts) {
      if (streamed) write(expected, Integer.toHexString(part.length) + "\r\n");
      expected.write(part);
      if (streamed) write(expected, "\r\n");
    }
    if (streamed && end.equals("clean")) write(expected, "0\r\n\r\n");
    try (var server = ReplayServer.start(0)) {
      server.register(
          "selftest", new Script(List.of(FixtureLoader.toScript(f).exchanges().get(index))));
      try (var socket = new Socket("127.0.0.1", server.port())) {
        socket.setSoTimeout(5000);
        String path = e.path("match").path("path").asText().replace("*", "tcf");
        socket
            .getOutputStream()
            .write(
                (e.path("match").path("method").asText()
                        + " /c/selftest"
                        + path
                        + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n{}")
                    .getBytes(StandardCharsets.US_ASCII));
        byte[] actual = socket.getInputStream().readNBytes(expected.size());
        if (!Arrays.equals(actual, expected.toByteArray()))
          throw new IOException(f.id() + ": byte mismatch at exchange " + index);
        if (end.equals("reset")) {
          try {
            socket.getInputStream().read();
            throw new IOException("Expected reset");
          } catch (SocketException reset) {
            if (!reset.getMessage().toLowerCase(Locale.ROOT).contains("reset")) throw reset;
          }
        } else if (end.equals("stall")) {
          long start = System.nanoTime();
          socket.setSoTimeout(1500);
          try {
            socket.getInputStream().read();
            throw new IOException("Stall ended early");
          } catch (SocketTimeoutException expectedSilence) {
          }
          if ((System.nanoTime() - start) / 1_000_000 < 1450)
            throw new IOException("Insufficient stall silence");
          if (fullStall) {
            socket.setSoTimeout(1500);
            if (socket.getInputStream().read() != -1) throw new IOException("Extra stall bytes");
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (elapsed < x.path("stall_ms").asLong() - 100
                || elapsed > x.path("stall_ms").asLong() + 600)
              throw new IOException("Wrong stall duration");
          }
        } else if (socket.getInputStream().read() != -1)
          throw new IOException("Trailing response bytes");
      }
    }
  }

  private static void write(ByteArrayOutputStream out, String value) {
    out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
