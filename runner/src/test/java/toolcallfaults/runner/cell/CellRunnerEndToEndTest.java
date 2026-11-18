package toolcallfaults.runner.cell;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.runner.oracle.*;
import toolcallfaults.server.*;

class CellRunnerEndToEndTest {
  @TempDir Path dir;

  @Test
  void clean_exit_without_result_line_is_harness_end_to_end() throws Exception {
    var t = new CellRunnerTest();
    t.dir = dir;
    var r = t.run("no-line", 4000);
    assertNull(r.resultLine());
    assertEquals(0, r.exitCode());
    assertFalse(r.watchdogKilled());
    var fixture = FixtureLoader.load(FixtureTestSupport.save(dir, FixtureTestSupport.example()));
    var c = Oracle.classify(fixture, r, List.of());
    assertEquals(Category.HARNESS, c.category());
    assertEquals("missing or malformed result line", c.reason());
  }

  @Test
  void hang_is_classified_hang_end_to_end_and_exit_code_is_kill() throws Exception {
    var t = new CellRunnerTest();
    t.dir = dir;
    var r = t.run("hang", 1500);
    var fixture = FixtureLoader.load(FixtureTestSupport.save(dir, FixtureTestSupport.example()));
    assertEquals(Category.HANG, Oracle.classify(fixture, r, List.of()).category());
  }

  @Test
  void library_targets_refuse_to_run_outside_a_container() {
    Assumptions.assumeFalse(Files.exists(Path.of("/.dockerenv")));
    var adapter = new BuiltAdapter("spring-ai", "1", "x.Main", List.of(), List.of());
    assertThrows(java.io.IOException.class, () -> {
      try (var server = ReplayServer.start(0)) {
        new CellRunner(server, 3000, 4000).run(new Cell("spring-ai", "1", "m", "loop-stream", "openai-chat/truncation.name-only", "record_note", 21, "jdk"), FixtureLoader.load(FixtureTestSupport.save(dir, FixtureTestSupport.example())), adapter, 1);
      }
    });
  }
}
