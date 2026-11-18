package toolcallfaults.runner.report;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import toolcallfaults.runner.results.*;

class ReportWithholdingTest {
  @TempDir Path dir;
  ReportTest t;

  @BeforeEach
  void setup() throws Exception {
    t = new ReportTest();
    t.root = dir;
    t.setup();
  }

  @Test
  void unsupported_public_row_without_any_baseline_entry_is_absent_from_every_public_page() throws Exception {
    Files.delete(dir.resolve("expectations/baseline/spring-ai@2.0.2.json"));
    t.generate(false);
    assertFalse(t.pages().contains(ReportTest.CELL));
  }

  @Test
  void unsupported_public_row_with_unfiled_entry_is_absent_even_with_issue_and_old_date() throws Exception {
    Files.delete(dir.resolve("expectations/baseline/spring-ai@2.0.2.json"));
    t.baseline(ReportTest.CELL, ReportTest.TODAY.minusDays(60), true, "unfiled", ReportTest.ISSUE);
    t.generate(false);
    assertFalse(t.pages().contains(ReportTest.CELL));
  }

  @Test
  void withheld_local_row_without_baseline_entry_is_absent_from_every_public_page() throws Exception {
    Files.delete(dir.resolve("expectations/baseline/spring-ai@2.0.2.json"));
    t.writeRow(ReportTest.CELL, "EXECUTED_ALTERED", true);
    t.generate(false);
    assertFalse(t.pages().contains(ReportTest.CELL));
  }

  @Test
  void public_report_never_reads_local_results() throws Exception {
    String other = ReportTest.CELL.replace("loop-stream", "model-stream");
    t.writeRow(other, "SURFACED", true);
    t.generate(false);
    assertFalse(t.pages().contains(other));
  }

  @Test
  void reply_window_closes_exactly_fourteen_days_after_filing() throws Exception {
    t.baseline(ReportTest.CELL, ReportTest.TODAY.minusDays(13), false, "filed", ReportTest.ISSUE);
    t.generate(false);
    assertFalse(t.pages().contains(ReportTest.CELL), "day 13 is inside the window");
    t.baseline(ReportTest.CELL, ReportTest.TODAY.minusDays(14), false, "filed", ReportTest.ISSUE);
    t.generate(false);
    assertTrue(t.pages().contains(ReportTest.CELL), "day 14 is outside the window");
  }

  @Test
  void harness_rows_in_tracked_results_are_absent_from_public_pages() throws Exception {
    String other = ReportTest.CELL.replace("loop-stream", "model-stream");
    t.writeRow(other, "HARNESS", false);
    t.generate(false);
    assertFalse(t.pages().contains(other));
  }

  @Test
  void record_only_sibling_of_an_unfiled_entry_is_absent_from_every_public_page() throws Exception {
    // The loop-stream cell of setup() becomes unfiled; its model-stream sibling is record-only.
    Files.delete(dir.resolve("expectations/baseline/spring-ai@2.0.2.json"));
    t.baseline(ReportTest.CELL, ReportTest.TODAY.minusDays(60), false, "unfiled", ReportTest.ISSUE);
    String sibling = ReportTest.CELL.replace("loop-stream", "model-stream");
    t.writeRow(sibling, "SURFACED", false);
    t.generate(false);
    assertFalse(t.pages().contains(sibling));
  }

  @Test
  void cell_with_its_own_unfiled_entry_is_absent_even_when_its_row_is_no_longer_unsupported()
      throws Exception {
    // The entry is unfiled and the tracked row now shows a supported outcome (a stale entry).
    Files.delete(dir.resolve("expectations/baseline/spring-ai@2.0.2.json"));
    t.baseline(ReportTest.CELL, ReportTest.TODAY.minusDays(60), false, "unfiled", ReportTest.ISSUE);
    t.writeRow(ReportTest.CELL, "SURFACED", false);
    t.generate(false);
    assertFalse(t.pages().contains(ReportTest.CELL));
  }
}
