package toolcallfaults.runner.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Normal primitive controls derived without modifying catalogue fixture bytes. */
public final class ControlFixtures {
  private ControlFixtures() {}

  public static Fixture load(Path root, String id) throws Exception {
    Path base = root.resolve("suite/fixtures").toAbsolutePath().normalize();
    Path file = base.resolve(id + ".json").normalize();
    if (!file.startsWith(base)) throw new IllegalArgumentException("Invalid fixture path");
    if (Files.exists(file)) return FixtureLoader.load(file);
    String[] pieces = id.split("/", 2);
    if (pieces.length == 2
        && Set.of("tools.single-call@set_level", "tools.single-call@nonstream+set_level")
            .contains(pieces[1])) {
      String variant = pieces[1].substring(pieces[1].indexOf('@') + 1);
      return levelControl(
          FixtureLoader.load(
              base.resolve(
                  pieces[0] + "/truncation.complete-args-under-limit@" + variant + ".json")));
    }
    return FixtureLoader.load(file);
  }

  public static Fixture levelControl(Fixture source) throws Exception {
    boolean ant = source.wire().equals("anthropic");
    String limit = ant ? "max_tokens" : "length", normal = ant ? "tool_use" : "tool_calls";
    List<JsonNode> exchanges = new ArrayList<>();
    List<String> hashes = new ArrayList<>();
    for (JsonNode exchange : source.exchanges()) {
      JsonNode e = exchange.deepCopy(), body = e.path("response").path("body");
      if (body.has("sse")) {
        var frames = (com.fasterxml.jackson.databind.node.ArrayNode) body.get("sse");
        for (int i = 0; i < frames.size(); i++)
          frames.set(
              i,
              FixtureLoader.JSON
                  .getNodeFactory()
                  .textNode(
                      frames.get(i).asText().replace("\"" + limit + "\"", "\"" + normal + "\"")));
      } else if (exchanges.isEmpty()) {
        if (ant)
          ((com.fasterxml.jackson.databind.node.ObjectNode) body.get("json"))
              .put("stop_reason", normal);
        else
          ((com.fasterxml.jackson.databind.node.ObjectNode)
                  body.path("json").path("choices").get(0))
              .put("finish_reason", normal);
      }
      exchanges.add(e);
      var bytes = new ByteArrayOutputStream();
      for (byte[] part : FixtureLoader.parts(body)) bytes.writeBytes(part);
      hashes.add(
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
    }
    return new Fixture(
        source.wire() + "/tools.single-call@" + (source.stream() ? "" : "nonstream+") + "set_level",
        "tools.single-call",
        source.wire(),
        source.stream() ? "set_level" : "nonstream+set_level",
        "synthetic",
        source.stream(),
        source.tools(),
        source.prompt(),
        List.copyOf(exchanges),
        source.x(),
        new Truth(source.truth().calls(), normal, true, "None; normal primitive-tool control."),
        List.of(),
        hashes,
        source.file());
  }
}
