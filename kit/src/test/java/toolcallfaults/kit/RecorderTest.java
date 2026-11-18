package toolcallfaults.kit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RecorderTest {
    @TempDir Path dir;
    @Test void appends_one_line_per_invocation() throws Exception {
        var r=new Recorder(dir);r.invoked("record_note",Map.of("title","café ☕"));r.invoked("flush_notes",Map.of());
        assertEquals(List.of("{\"seq\":1,\"name\":\"record_note\",\"args\":{\"title\":\"café ☕\"}}","{\"seq\":2,\"name\":\"flush_notes\",\"args\":{}}"),Files.readAllLines(dir.resolve(Recorder.FILE),StandardCharsets.UTF_8));assertEquals(2,r.count());
    }
    @Test void returns_fixed_result_text(){var r=new Recorder(dir);assertEquals("note recorded",r.invoked("record_note",Map.of()));assertEquals("level set",r.invoked("set_level",Map.of()));assertEquals("notes flushed",r.invoked("flush_notes",Map.of()));}
}
