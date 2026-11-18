package toolcallfaults.runner.cell;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import toolcallfaults.runner.fixture.*;
import toolcallfaults.runner.oracle.*;
import toolcallfaults.server.*;
import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.fixture.FixtureTestSupport.*;
class CellRunnerTest {
    @TempDir Path dir;
    CellResult run(String mode,long watchdog) throws Exception {
        var n=example();for(var e:n.path("exchanges"))((com.fasterxml.jackson.databind.node.ObjectNode)e.path("match")).put("path","/v1/x");
        var fixture=FixtureLoader.load(save(dir,n));
        try(var server=ReplayServer.start(0)) {
            var adapter=new BuiltAdapter("stub","1",StubAdapter.class.getName(),List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)),List.of());
            return new CellRunner(server,3000,watchdog).run(new Cell("stub","1",mode,"loop-stream",fixture.id(),"record_note",21,"jdk"),fixture,adapter,1);
        }
    }
    @Test void runs_cell_and_reads_three_sources() throws Exception {var r=run("ok",4000);assertEquals(2,r.requests().size());assertEquals(1,r.invocations().size());assertNotNull(r.resultLine());assertFalse(r.watchdogKilled());}
    private static Set<Path> pidFiles() throws Exception {
        if(!Files.isDirectory(Path.of(".local/cells")))return Set.of();
        try(var paths=Files.walk(Path.of(".local/cells"))){return new HashSet<>(paths.filter(p->p.getFileName().toString().equals("pid")).toList());}
    }
    @Test void watchdog_kills_at_w() throws Exception {
        // Work directories of earlier runs stay on disk and their process ids get reused by
        // unrelated processes, so only the child started here is checked.
        var before=pidFiles();
        var r=run("hang",1500);assertTrue(r.watchdogKilled());assertTrue(r.elapsedMs()>=1500&&r.elapsedMs()<3000);
        var started=pidFiles();started.removeAll(before);assertEquals(1,started.size(),started.toString());
        for(Path p:started)assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(p))).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void child_environment_is_cleared() throws Exception {var r=run("env",4000);var names=r.resultLine().path("caller").path("callback_only").path("environment");for(String forbidden:List.of("OPENAI_API_KEY","ANTHROPIC_API_KEY","PATH","HOME")) for(var name:names) assertNotEquals(forbidden,name.asText());
        for(var name:names) assertTrue(System.getProperty("os.name").startsWith("Mac") && name.asText().equals("__CF_USER_TEXT_ENCODING"),names.toString());}
    @Test void recorder_file_outranks_result_line() throws Exception {var r=run("late-tool",4000);assertEquals(1,r.invocations().size());assertTrue(r.resultLine().get("tool_invocations").isEmpty());}
    @Test void printer_shows_invocations_and_category() throws Exception {
        var r=run("ok",4000);var fixture=FixtureLoader.load(save(dir,example()));var lines=CellPrinter.render(fixture,r,Oracle.classify(fixture,r,List.of()));
        assertTrue(lines.contains("invocations:   1  record_note {\"priority\":null,\"title\":null,\"body\":null}"),lines.toString());assertTrue(lines.contains("category:      EXECUTED_ALTERED"));
    }
    @Test void torn_recorder_detected_from_disk() throws Exception {assertTrue(run("torn",4000).recorderTorn());}
}
