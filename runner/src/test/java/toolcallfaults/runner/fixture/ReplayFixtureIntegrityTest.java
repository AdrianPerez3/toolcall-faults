package toolcallfaults.runner.fixture;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.matrix.Matrix;

class ReplayFixtureIntegrityTest {
  @TempDir Path root;

  private Path copyFixture(String id) throws IOException {
    Path path = Path.of("suite", "fixtures", id + ".json");
    Files.createDirectories(root.resolve(path).getParent());
    return Files.copy(Path.of("..").resolve(path), root.resolve(path));
  }

  private void staleLastBody(Path file) throws IOException {
    var fixture = (ObjectNode) FixtureLoader.read(file);
    var body = (ObjectNode) fixture.path("exchanges").get(fixture.path("exchanges").size() - 1)
        .path("response").path("body");
    body.removeAll();
    body.put("text", "different response bytes");
    Files.writeString(file, fixture.toString());
  }

  @Test
  void single_cell_and_matrix_reject_a_changed_followup_body() throws Exception {
    String id = "openai-chat/tools.single-call";
    Path file = copyFixture(id);
    Files.createDirectories(root.resolve("suite/scenarios"));
    Files.writeString(root.resolve("matrix.json"), "{\"format\":\"tcf.matrix/v1\",\"targets\":{}}");
    assertEquals(id, ControlFixtures.load(root, id).id());
    assertDoesNotThrow(() -> Matrix.load(root));
    staleLastBody(file);
    assertAll(
        () -> assertTrue(assertThrows(IOException.class, () -> ControlFixtures.load(root, id))
            .getMessage().contains("body hash mismatch")),
        () -> assertTrue(assertThrows(IOException.class, () -> Matrix.load(root))
            .getMessage().contains("body hash mismatch")));
  }

  @Test
  void derived_control_verifies_its_source_before_generating_new_hashes() throws Exception {
    staleLastBody(copyFixture("openai-chat/truncation.complete-args-under-limit@set_level"));
    var error = assertThrows(IOException.class,
        () -> ControlFixtures.load(root, "openai-chat/tools.single-call@set_level"));
    assertTrue(error.getMessage().contains("body hash mismatch"));
  }

  @Test
  void extra_declared_hash_is_rejected_even_when_every_body_matches() throws Exception {
    String id = "openai-chat/tools.single-call";
    Path file = copyFixture(id);
    var fixture = (ObjectNode) FixtureLoader.read(file);
    ((ObjectNode) fixture.path("provenance")).withArray("body_sha256").add("0".repeat(64));
    Files.writeString(file, fixture.toString());
    assertTrue(assertThrows(IOException.class, () -> ControlFixtures.load(root, id))
        .getMessage().contains("one hash per exchange required"));
  }
}
