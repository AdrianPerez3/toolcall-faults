package toolcallfaults.kit;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FlagsTest {
    static String[] args(String dir) {return new String[]{"--wire","openai-chat","--surface","loop-stream","--tools","record_note,set_level","--base-url","http://127.0.0.1:1","--timeout-ms","3000","--workdir",dir};}
    @Test void parses_all_flags() {
        var a=new ArrayList<>(List.of(args("work")));a.addAll(List.of("--module","model","--http-client","jdk","--prompt","hello"));var f=Flags.parse(a.toArray(String[]::new));
        assertEquals(List.of("record_note","set_level"),f.tools());assertEquals(3000,f.timeoutMs());assertEquals("openai-chat",f.wire());assertEquals("loop-stream",f.surface());assertEquals("model",f.module());assertEquals("jdk",f.httpClient());assertEquals("hello",f.prompt());assertEquals("work",f.workdir().toString());assertEquals("http://127.0.0.1:1",f.baseUrl());
    }
    @Test void unknown_flag_named_in_error(){assertTrue(assertThrows(IllegalArgumentException.class,()->Flags.parse(new String[]{"--bogus","x"})).getMessage().contains("--bogus"));}
    @Test void missing_required_named_in_error(){assertTrue(assertThrows(IllegalArgumentException.class,()->Flags.parse(Arrays.copyOf(args("work"),10))).getMessage().contains("--workdir"));}
}
