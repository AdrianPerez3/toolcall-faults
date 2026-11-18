package toolcallfaults.runner.selftest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.fixture.FixtureLoader;

class PinsTest {
  @TempDir Path root;

  @Test
  void pin_evidence_preserves_each_jdk_and_client() throws Exception {
    var paths = new HashMap<Path, String>();
    for (int jdk : List.of(17, 21))
      for (String client : List.of("jdk", "okhttp")) {
        var cell =
            new toolcallfaults.runner.cell.Cell(
                "langchain4j",
                "1.23.0",
                "langchain4j-open-ai",
                "loop-stream",
                "openai-chat/tools.single-call",
                "record_note",
                jdk,
                client);
        var path = Pins.evidencePath(root, cell, 1);
        Files.createDirectories(path.getParent());
        Files.writeString(path, cell.key());
        paths.put(path, cell.key());
      }
    assertEquals(4, paths.size());
    for (var entry : paths.entrySet())
      assertEquals(entry.getValue(), Files.readString(entry.getKey()));
  }

  private Pins.Observation observation(String category, String args, String signals)
      throws Exception {
    return new Pins.Observation(
        category, FixtureLoader.JSON.readTree(args), FixtureLoader.JSON.readTree(signals));
  }

  @Test
  void identical_observations_are_same() throws Exception {
    var a = observation("EXECUTED_AS_SENT", "[{\"level\":1}]", "[\"complete\"]");
    assertFalse(Pins.differs(List.of(a, a, a), List.of(a, a, a)));
  }

  @Test
  void category_arguments_or_terminal_signals_each_make_a_difference() throws Exception {
    var a = observation("EXECUTED_AS_SENT", "[{\"level\":1}]", "[\"complete\"]");
    for (var b :
        List.of(
            observation("ERROR_UNTYPED", "[{\"level\":1}]", "[\"complete\"]"),
            observation("EXECUTED_AS_SENT", "[{\"level\":2}]", "[\"complete\"]"),
            observation("EXECUTED_AS_SENT", "[{\"level\":1}]", "[\"error\"]")))
      assertTrue(Pins.differs(List.of(a, a, a), List.of(b, b, b)));
  }

  @Test
  void missing_or_flaky_repetitions_cannot_prove_a_pin() throws Exception {
    var a = observation("EXECUTED_AS_SENT", "[]", "[\"complete\"]");
    var b = observation("ERROR_UNTYPED", "[]", "[\"error\"]");
    assertThrows(IllegalStateException.class, () -> Pins.differs(List.of(a, a), List.of(b, b, b)));
    assertThrows(
        IllegalStateException.class, () -> Pins.differs(List.of(a, a, b), List.of(b, b, b)));
  }
}
