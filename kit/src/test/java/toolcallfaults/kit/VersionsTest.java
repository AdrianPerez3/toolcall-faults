package toolcallfaults.kit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class VersionsTest {
    @Test void parses_dependency_list_lines(){var v=Versions.parse(List.of("The following files have been resolved:","   org.springframework.ai:spring-ai-openai:jar:2.0.2:compile","g:a:jar:linux-x86_64:1.2.3:runtime -- module x.y [auto]"));assertEquals(2,v.size());assertEquals("2.0.2",v.get("org.springframework.ai:spring-ai-openai"));assertEquals("1.2.3",v.get("g:a"));}
    @Test void sha_is_order_independent(){var a=List.of("g:a:jar:1.0:compile","g:b:jar:2.0:runtime");assertEquals(Versions.sha256(a),Versions.sha256(List.of(a.get(1),a.get(0))));assertNotEquals(Versions.sha256(a),Versions.sha256(List.of(a.get(0))));assertEquals(64,Versions.sha256(a).length());}
}
