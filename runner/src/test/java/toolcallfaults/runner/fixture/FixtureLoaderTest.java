package toolcallfaults.runner.fixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import toolcallfaults.server.*;
import static org.junit.jupiter.api.Assertions.*;
import static toolcallfaults.runner.fixture.FixtureTestSupport.*;
class FixtureLoaderTest {
    @TempDir Path dir;
    @Test void loads_spec_example() throws Exception {
        var f=FixtureLoader.load(save(dir,example()));assertEquals("openai-chat/truncation.name-only",f.id());
        assertEquals(2,f.exchanges().size());assertFalse(f.truth().calls().get(0).complete());assertEquals("length",f.truth().rawFinish());
        var e=FixtureLoader.toScript(f).exchanges().get(0);assertEquals(3,e.parts().size());assertEquals(End.CLEAN,e.end());assertEquals(BodyKind.STREAMED,e.kind());
    }
    @Test void x_overrides_end() throws Exception {
        var n=example();n.withObject("x").putObject("0").put("end","stall").put("stall_ms",15000);
        var e=FixtureLoader.toScript(FixtureLoader.load(save(dir,n))).exchanges().get(0);assertEquals(End.STALL,e.end());assertEquals(15000,e.stallMs());
    }
    @Test void ndjson_parts_get_newline() throws Exception {
        var n=example();response(n).set("body",JSON.readTree("{\"ndjson\":[\"a\",\"b\"]}"));
        assertArrayEquals("a\nb\n".getBytes(StandardCharsets.UTF_8),FixtureLoader.bodyBytes(FixtureLoader.load(save(dir,n)),0));
    }
    @Test void json_body_is_compact_utf8() throws Exception {
        var n=example();response(n).set("body",JSON.readTree("{\"json\":{\"a\":1,\"b\":\"é\"}}"));var f=FixtureLoader.load(save(dir,n));
        assertEquals("{\"a\":1,\"b\":\"é\"}",new String(FixtureLoader.bodyBytes(f,0),StandardCharsets.UTF_8));assertEquals(BodyKind.WHOLE,FixtureLoader.toScript(f).exchanges().get(0).kind());
    }
    @Test void body_needs_exactly_one_form() throws Exception {
        for(String body:new String[]{"{}","{\"sse\":[],\"text\":\"a\"}"}) {
            var n=example();response(n).set("body",JSON.readTree(body));
            assertTrue(assertThrows(IOException.class,()->FixtureLoader.load(save(dir,n))).getMessage().contains("$.exchanges[0].response.body"));
        }
    }
    @Test void duplicate_key_rejected() throws Exception {
        Path p=save(dir,example());Files.writeString(p,Files.readString(p).replace("\"format\":", "\"format\":\"x\",\"format\":"));assertThrows(IOException.class,()->FixtureLoader.load(p));
    }
    @Test void unknown_field_rejected() throws Exception {
        var n=example();n.put("typo",true);assertTrue(assertThrows(IOException.class,()->FixtureLoader.load(save(dir,n))).getMessage().contains("typo"));
    }
    @Test void invalid_utf8_rejected() throws Exception {
        Path p=dir.resolve("invalid.json");Files.write(p,new byte[]{(byte)0xc3,(byte)0x28});
        assertThrows(IOException.class,()->FixtureLoader.load(p));
    }
    @Test void nested_unknown_field_rejected() throws Exception {
        var n=example();n.withObject("derived_from").put("typo",true);
        assertTrue(assertThrows(IOException.class,()->FixtureLoader.load(save(dir,n))).getMessage().contains("typo"));
    }
}
