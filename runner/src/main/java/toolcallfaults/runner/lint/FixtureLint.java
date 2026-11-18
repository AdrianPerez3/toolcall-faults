package toolcallfaults.runner.lint;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.server.*;

public final class FixtureLint {
  private FixtureLint() {}

  public static List<Problem> check(Path suiteDir) {
    List<Problem> out = new ArrayList<>();
    if (!Files.exists(suiteDir)) return out;
    try (var paths = Files.walk(suiteDir)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
        // This document has its own schema and is checked by ManifestLint.
        if (file.equals(suiteDir.resolve("judgements.json"))) continue;
        try {
          JsonNode root = FixtureLoader.read(file);
          if (root.path("format").asText().equals("tcf.scenario/v1")) {
            if (root.path("evidence").isEmpty()
                && !root.path("sighting").asText().equals("protocol-legal, no sighting"))
              out.add(
                  new Problem(
                      file.toString(),
                      "$.evidence",
                      "supply evidence or protocol-legal, no sighting"));
            continue;
          }
          Fixture f = FixtureLoader.load(file);
          boolean selftest = suiteDir.relativize(file).startsWith("selftest");
          if (!selftest
              && !suiteDir
                  .resolve("fixtures")
                  .resolve(f.id() + ".json")
                  .normalize()
                  .equals(file.normalize())) add(out, f, "$.id", "id must match path");
          if (!selftest
              && !f.id()
                  .equals(
                      f.wire()
                          + "/"
                          + f.scenario()
                          + (f.variant().isEmpty() ? "" : "@" + f.variant())))
            add(out, f, "$.id", "id must match wire and scenario");
          if (f.bodySha256().size() != f.exchanges().size())
            add(out, f, "$.provenance.body_sha256", "one hash per exchange required");
          var script = FixtureLoader.toScript(f);
          for (int i = 0; i < f.exchanges().size(); i++) {
            String p = "$.exchanges[" + i + "].response";
            JsonNode body = f.exchanges().get(i).path("response").path("body");
            Exchange e = script.exchanges().get(i);
            String sha =
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(FixtureLoader.bodyBytes(f, i)));
            if (i >= f.bodySha256().size() || !sha.equals(f.bodySha256().get(i)))
              add(out, f, "$.provenance.body_sha256[" + i + "]", "body hash mismatch");
            if (e.kind() == BodyKind.WHOLE && e.end() != End.CLEAN)
              add(out, f, p + ".end", "non-clean end needs streamed body");
            String form = body.fieldNames().next();
            for (int j = 0; j < e.parts().size(); j++) {
              if (e.kind() == BodyKind.STREAMED && e.parts().get(j).length == 0)
                add(out, f, p + ".body." + form + "[" + j + "]", "empty chunk ends a chunked body");
              if (form.equals("sse")
                  && !body.get(form).get(j).asText().endsWith("\n\n")
                  // The removal names the last event of the damaged first exchange only.
                  && !(f.removes().contains("final-blank-line")
                      && i == 0
                      && j == e.parts().size() - 1))
                add(out, f, p + ".body.sse[" + j + "]", "SSE element needs final blank line");
            }
            if (e.delaysMs() != null && e.delaysMs().length != e.parts().size())
              add(out, f, "$.x." + i + ".delays_ms", "one delay per part required");
          }
          if (f.tier().equals("derived")) {
            String source = root.path("derived_from").path("fixture").asText();
            Path sourceFile = suiteDir.resolve("fixtures").resolve(source + ".json").normalize();
            if (source.isEmpty()
                || !sourceFile.startsWith(suiteDir.resolve("fixtures").normalize())
                || !Files.isRegularFile(sourceFile))
              add(out, f, "$.derived_from.fixture", "source fixture must exist");
          }
          if (!selftest && !f.fromRecording()) out.addAll(Completeness.check(f));
        } catch (Exception e) {
          String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          int colon = message.indexOf(": ");
          out.add(
              new Problem(
                  file.toString(),
                  message.startsWith("$") && colon > 0 ? message.substring(0, colon) : "$",
                  message.startsWith("$") && colon > 0 ? message.substring(colon + 2) : message));
        }
      }
    } catch (Exception e) {
      out.add(new Problem(suiteDir.toString(), "$", e.getMessage()));
    }
    return out;
  }

  private static void add(List<Problem> out, Fixture f, String p, String m) {
    out.add(new Problem(f.file().toString(), p, m));
  }
}
