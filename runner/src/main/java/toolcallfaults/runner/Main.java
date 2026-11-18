package toolcallfaults.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import toolcallfaults.runner.lint.FixtureLint;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  private static java.util.Map<String, String> options(String[] args, int from) {
    java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
    for (int i = from; i < args.length; i += 2)
      if (i + 1 == args.length
          || !args[i].startsWith("--")
          || out.putIfAbsent(args[i], args[i + 1]) != null)
        throw new IllegalArgumentException("Invalid or duplicate option");
    return out;
  }

  private static void allowed(java.util.Map<String, String> options, String... names) {
    for (String name : options.keySet())
      if (!List.of(names).contains(name))
        throw new IllegalArgumentException("Unknown option " + name);
  }

  static String fixtureId(
      String wire, String scenario, String surface, String tool, String variant) {
    List<String> variants = new ArrayList<>();
    if (variant != null && !variant.isEmpty()) variants.add(variant);
    if (surface.endsWith("-call")) variants.add("nonstream");
    String defaultTool = scenario.equals("tools.zero-arg-call") ? "flush_notes" : "record_note";
    if (!tool.equals(defaultTool) && (variant == null || !variant.startsWith("recorded")))
      variants.add(tool);
    return wire + "/" + scenario + (variants.isEmpty() ? "" : "@" + String.join("+", variants));
  }

  private static void run(String[] args) throws Exception {
    if (List.of(args).equals(List.of("report"))
        || List.of(args).equals(List.of("report", "--include-withheld"))) {
      boolean local = args.length == 2;
      toolcallfaults.runner.report.Report.generate(
          Path.of("."), Path.of(local ? ".local/site" : "site"), local, java.time.LocalDate.now());
      return;
    }
    if (args.length > 0 && args[0].equals("run")) {
      var words = new ArrayList<>(List.of(args));
      boolean list = words.remove("--list");
      boolean rerunFlaky = words.remove("--rerun-flaky");
      boolean published = words.remove("--published");
      if (list && rerunFlaky) throw new IllegalArgumentException("Choose --list or --rerun-flaky");
      var options = options(words.toArray(String[]::new), 1);
      allowed(
          options,
          "--target",
          "--scenario",
          "--surface",
          "--wire",
          "--role",
          "--http-client",
          "--jdk",
          "--parallel",
          "--out");
      RunCommands.run(Path.of("."), options, list, rerunFlaky, published);
      return;
    }
    if (args.length > 0 && args[0].equals("check")) {
      if (args.length == 5 && args[1].equals("--compare-jdks")) {
        RunCommands.compareJdks(
            Path.of("."), List.of(Path.of(args[2]), Path.of(args[3]), Path.of(args[4])));
        return;
      }
      var options = options(args, 1);
      allowed(options, "--run");
      RunCommands.check(
          Path.of("."), options.containsKey("--run") ? Path.of(options.get("--run")) : null);
      return;
    }
    if (List.of(args).equals(List.of("compat"))) {
      if (!Files.exists(Path.of("/.dockerenv")))
        throw new IllegalStateException("Use ./tcf --container 21 compat");
      int code =
          new ProcessBuilder("sh", "compat/agent-faultbench/run.sh", "--inside")
              .inheritIO()
              .start()
              .waitFor();
      if (code != 0) throw new IllegalStateException("Compatibility run failed: " + code);
      return;
    }

    if (List.of(args).equals(List.of("selftest", "--pins"))) {
      toolcallfaults.runner.selftest.Pins.run(Path.of("."));
      return;
    }
    if (List.of(args).equals(List.of("selftest"))) {
      toolcallfaults.runner.selftest.SelfTest.server(Path.of("suite"));
      toolcallfaults.runner.selftest.SelfTest.controls(Path.of("."), null);
      toolcallfaults.runner.selftest.SelfTest.anchors(Path.of("."), null, null);
      toolcallfaults.runner.selftest.Pins.run(Path.of("."));
      return;
    }
    if (args.length >= 2 && args[0].equals("selftest") && args[1].equals("--controls")) {
      var options = options(args, 2);
      allowed(options, "--target");
      toolcallfaults.runner.selftest.SelfTest.controls(Path.of("."), options.get("--target"));
      return;
    }
    if (args.length >= 2 && args[0].equals("selftest") && args[1].equals("--anchors")) {
      var options = options(args, 2);
      allowed(options, "--target", "--wire");
      toolcallfaults.runner.selftest.SelfTest.anchors(
          Path.of("."), options.get("--target"), options.get("--wire"));
      return;
    }
    if (args.length >= 3 && args[0].equals("cell")) {
      var options = options(args, 3);
      allowed(
          options,
          "--wire",
          "--surface",
          "--tools",
          "--variant",
          "--module",
          "--http-client",
          "--reps");
      String[] target = args[1].split("@", -1);
      if (target.length != 2) throw new IllegalArgumentException("Expected target@version");
      toolcallfaults.runner.cell.AdapterBuild.validateIdentity(target[0], target[1]);
      String wire = options.getOrDefault("--wire", "openai-chat"),
          surface = options.getOrDefault("--surface", "loop-stream"),
          tool =
              options.getOrDefault(
                  "--tools", args[2].equals("tools.zero-arg-call") ? "flush_notes" : "record_note");
      var manifest =
          toolcallfaults.runner.cell.AdapterManifest.load(Path.of("adapters", target[0]));
      String module =
          options.getOrDefault(
              "--module",
              manifest.modules().entrySet().stream()
                  .filter(e -> e.getValue().wire().equals(wire))
                  .map(java.util.Map.Entry::getKey)
                  .findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("Unsupported wire " + wire)));
      if (!manifest.modules().containsKey(module))
        throw new IllegalArgumentException("module " + module + " not declared by " + target[0]);
      if (!manifest.modules().get(module).wire().equals(wire))
        throw new IllegalArgumentException("module " + module + " does not use wire " + wire);
      if (!manifest.surfaces().contains(surface))
        throw new IllegalArgumentException("surface " + surface + " not declared by " + target[0]);
      if (!manifest.modules().get(module).supportsScenario(args[2]))
        throw new IllegalArgumentException(
            "scenario " + args[2] + " not declared for module " + module);
      String fixture = fixtureId(wire, args[2], surface, tool, options.get("--variant"));
      var loaded = toolcallfaults.runner.fixture.ControlFixtures.load(Path.of("."), fixture);
      if (!options.containsKey("--tools")) tool = loaded.tools().get(0);
      if (!loaded.tools().equals(List.of(tool)))
        throw new IllegalArgumentException("Tool does not match fixture");
      if (options.containsKey("--http-client")
          && !manifest
              .modules()
              .get(module)
              .clientsFor(
                  toolcallfaults.runner.fixture.FixtureLoader.JSON
                      .readTree(
                          Path.of(
                                  "suite/scenarios",
                                  loaded.scenario().replaceFirst("\\.", "/") + ".json")
                              .toFile())
                      .path("http_client_axis")
                      .asBoolean())
              .contains(options.get("--http-client")))
        throw new IllegalArgumentException("Unsupported HTTP client for module " + module);
      int reps = Integer.parseInt(options.getOrDefault("--reps", "1"));
      if (reps < 1) throw new IllegalArgumentException("Repetitions must be positive");
      var cell =
          new toolcallfaults.runner.cell.Cell(
              target[0],
              target[1],
              module,
              surface,
              fixture,
              tool,
              Runtime.version().feature(),
              options.getOrDefault("--http-client", manifest.modules().get(module).httpClient()));
      var selection =
          toolcallfaults.runner.matrix.ControlFailures.select(
              List.of(cell), java.util.Map.of(manifest.target(), manifest));
      if (!selection.notRun().isEmpty()) {
        var notRun = selection.notRun().get(0);
        System.out.println("not run:       " + cell.key());
        System.out.println("basis:         " + String.join(" ", notRun.basis()));
        System.out.println(
            "notes:         A declared control failure prevents interpreting this cell. "
                + String.join(" ", notRun.notes()));
        return;
      }
      var adapter =
          toolcallfaults.runner.cell.AdapterBuild.ensure(
              Path.of("."),
              target[0],
              target[1],
              toolcallfaults.runner.cell.AdapterBuild.variant(cell));
      for (int rep = 1; rep <= reps; rep++)
        try (var server = toolcallfaults.server.ReplayServer.start(0)) {
          var result =
              new toolcallfaults.runner.cell.CellRunner(server, 3000, 12000)
                  .run(cell, loaded, adapter, rep);
          if (result.flagged())
            throw new IllegalStateException(
                "Private-reporting review required: request cap reached; raw cell evidence is"
                    + " local");
          var classified =
              toolcallfaults.runner.oracle.Oracle.classify(
                  loaded, result, adapter.typedErrorPrefixes());
          toolcallfaults.runner.cell.CellPrinter.render(loaded, result, classified)
              .forEach(System.out::println);
          var attributes =
              (com.fasterxml.jackson.databind.node.ObjectNode) classified.attributes().deepCopy();
          attributes.put(
              "timeout_ms",
              result.resultLine() == null
                  ? 3000
                  : result.resultLine().path("settings").path("timeout_ms").asLong(3000));
          manifest
              .controlFailure(cell)
              .ifPresent(f -> attributes.put("control_failure_basis", f.basis()));
          var verdict =
              toolcallfaults.runner.judge.Judgements.load(Path.of("suite/judgements.json"))
                  .judge(loaded.id(), surface, classified.category(), attributes);
          System.out.println(
              "judgement:     "
                  + verdict.level()
                  + "; unsupported="
                  + verdict.unsupported()
                  + "; flagged="
                  + verdict.flagged());
          System.out.println("basis:         " + verdict.basis());
          manifest
              .controlFailure(cell)
              .ifPresent(
                  f -> {
                    System.out.println("notes:         " + f.notes());
                  });
          if (classified.category() == toolcallfaults.runner.oracle.Category.HARNESS)
            throw new IllegalStateException(classified.reason());
        }
      return;
    }
    if (List.of(args).equals(List.of("selftest", "--server"))) {
      toolcallfaults.runner.selftest.SelfTest.server(Path.of("suite"));
      return;
    }
    if (args.length >= 1
        && args[0].equals("lint")
        && (args.length == 1 || List.of(args).equals(List.of("lint", "--scan")))) {
      int fixtureProblems = 0;
      if (args.length == 1) {
        var lint = new ArrayList<>(FixtureLint.check(Path.of("suite")));
        lint.addAll(toolcallfaults.runner.lint.ManifestLint.check(Path.of(".")));
        lint.forEach(System.out::println);
        fixtureProblems = lint.size();
        System.out.println("lint: " + fixtureProblems + " problems");
      }
      var problems = toolcallfaults.runner.lint.RepositoryScan.scan(Path.of("."));
      problems.forEach(System.out::println);
      System.out.println("scan: " + problems.size() + " findings");
      if (!problems.isEmpty() || fixtureProblems > 0) System.exit(1);
    } else {
      System.err.println(
          "usage: tcf lint [--scan] | run [filters] [--list] [--published] | check [--run dir] |"
              + " report [--include-withheld] | cell"
              + " target@version scenario [options] | selftest [--server | --controls | --anchors |"
              + " --pins]");
      System.exit(2);
    }
  }
}
