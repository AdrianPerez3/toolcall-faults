package toolcallfaults.server;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerSizeTest {
    @Test void server_main_sources_at_most_300_lines() throws Exception {
        long count=0;
        try(var paths=Files.walk(Path.of("src/main/java"))) {
            for(Path path:paths.filter(p->p.toString().endsWith(".java")).toList())count+=Files.readAllLines(path).size();
        }
        assertTrue(count<=300,"physical source lines: "+count);
    }
}
