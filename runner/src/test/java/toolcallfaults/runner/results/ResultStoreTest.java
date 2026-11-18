package toolcallfaults.runner.results;

import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.results.Records.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ResultStoreTest {
  @TempDir Path root;
  ResultStore store;

  @BeforeEach
  void setup() {
    store = new ResultStore(root.resolve("public"), root.resolve("local"));
  }

  @Test
  void withheld_cells_go_to_local() throws Exception {
    store.write("target", "1", List.of(row(CELL, 1, "EXECUTED_ALTERED", 1)), Set.of(CELL));
    assertTrue(store.read("target", "1", false).isEmpty());
    assertEquals(1, store.read("target", "1", true).size());
    assertTrue(Files.readString(root.resolve("local/target/1.jsonl")).contains(CELL));
  }

  @Test
  void other_cells_go_to_public() throws Exception {
    store.write("target", "1", List.of(row(CELL, 1, "EXECUTED_AS_SENT", 1)), Set.of());
    assertEquals(1, store.read("target", "1", false).size());
  }

  @Test
  void rerun_replaces_only_cells_it_ran() throws Exception {
    String other = CELL.replace("loop-stream", "model-stream");
    store.write(
        "target",
        "1",
        List.of(row(CELL, 1, "EXECUTED_ALTERED", 1), row(other, 1, "SURFACED", 1)),
        Set.of(CELL));
    store.write("target", "1", List.of(row(CELL, 1, "ERROR_TYPED", 2)), Set.of(CELL));
    var rows = store.read("target", "1", true);
    assertEquals(2, rows.size());
    assertTrue(
        rows.stream().anyMatch(r -> r.cell().equals(other) && r.category().equals("SURFACED")));
    assertTrue(
        rows.stream().anyMatch(r -> r.cell().equals(CELL) && r.category().equals("ERROR_TYPED")));
  }

  @Test
  void newly_withheld_cell_is_removed_from_public_copy() throws Exception {
    store.write("target", "1", List.of(row(CELL, 1, "EXECUTED_AS_SENT", 1)), Set.of());
    store.write("target", "1", List.of(row(CELL, 1, "EXECUTED_ALTERED", 1)), Set.of(CELL));
    assertTrue(store.read("target", "1", false).isEmpty());
    assertEquals(1, store.read("target", "1", true).size());
  }

  @Test
  void result_files_are_readable_by_a_later_step_running_as_another_user() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    store.write("target", "1", List.of(row(CELL, 1, "EXECUTED_AS_SENT", 1)), Set.of());
    var permissions = Files.getPosixFilePermissions(root.resolve("public/target/1.jsonl"));
    assertTrue(
        permissions.containsAll(
            Set.of(
                java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ)),
        "container-written results must be readable by the runner user: " + permissions);
  }

  @Test
  void withheld_result_files_stay_owner_only() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    store.write("target", "1", List.of(row(CELL, 1, "EXECUTED_ALTERED", 1)), Set.of(CELL));
    assertEquals(
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(root.resolve("local/target/1.jsonl")));
  }

  @Test
  void row_identity_must_match_its_result_file_surface_and_fixture() throws Exception {
    store.write("target", "1", List.of(row(CELL, 1, "SURFACED", 1)), Set.of());
    Path path = root.resolve("public/target/1.jsonl");
    var json = toolcallfaults.runner.fixture.FixtureLoader.JSON;
    var original = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(path.toFile());
    for (var field : Map.of("surface", "model-stream", "fixtureId", "openai-chat/tools.single-call",
            "cell", CELL.replace("target@1", "target@2")).entrySet()) {
      Files.writeString(path, original.deepCopy().put(field.getKey(), field.getValue()).toString());
      assertThrows(java.io.IOException.class, () -> store.read("target", "1", false), field.getKey());
    }
  }
}
