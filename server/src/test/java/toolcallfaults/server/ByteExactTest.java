package toolcallfaults.server;

import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ByteExactTest {
    static final String HEAD="HTTP/1.1 200 OK\r\ncontent-type: text/event-stream; charset=utf-8\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
    static final String PARTS="9\r\ndata: a\n\n\r\n9\r\ndata: b\n\n\r\n";
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static void register(ReplayServer s,End end,long stall,int[] delays,List<byte[]> parts) {
        s.register("a",new Script(List.of(new Exchange("POST","/x",200,
                Map.of("content-type","text/event-stream; charset=utf-8"),BodyKind.STREAMED,parts,end,stall,delays))));
    }
    static void register(ReplayServer s,End end) {register(s,end,0,null,List.of(bytes("data: a\n\n"),bytes("data: b\n\n")));}
    @Test void streamed_clean_bytes_exact() throws Exception {
        try(var s=ReplayServer.start(0)) {register(s,End.CLEAN);assertEquals(HEAD+PARTS+"0\r\n\r\n",ReplayServerTest.req(s,"a","/x"));}
    }
    @Test void abort_ends_without_terminating_chunk() throws Exception {
        try(var s=ReplayServer.start(0)) {register(s,End.ABORT);assertEquals(HEAD+PARTS,ReplayServerTest.req(s,"a","/x"));}
    }
    @Test void reset_raises_connection_reset() throws Exception {
        try(var s=ReplayServer.start(0);var c=new RawClient(s.port())) {
            register(s,End.RESET);c.send("POST","/c/a/x",Map.of(),"");
            var error=assertThrows(SocketException.class,c::all);assertTrue(error.getMessage().toLowerCase().contains("reset"));
        }
    }
    @Test void stall_sends_nothing_for_stated_time() throws Exception {
        try(var s=ReplayServer.start(0);var c=new RawClient(s.port())) {
            register(s,End.STALL,2000,new int[]{0},List.of(bytes("x")));
            c.send("POST","/c/a/x",Map.of(),"");c.through("1\r\nx\r\n");long start=System.nanoTime();
            Thread.sleep(200);assertTrue(s.log("a").get(0).servedAsScripted());
            assertEquals(0,c.all().length);long ms=(System.nanoTime()-start)/1_000_000;
            assertTrue(ms>=1900 && ms<=2600,"stall duration: "+ms);
        }
    }
    @Test void whole_body_has_content_length() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a",new Script(List.of(ReplayServerTest.whole("/x","one"))));
            assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\none",ReplayServerTest.req(s,"a","/x"));
        }
    }
    @Test void utf8_split_arrives_intact() throws Exception {
        try(var s=ReplayServer.start(0);var c=new RawClient(s.port())) {
            byte[] body=bytes("é☕");
            register(s,End.CLEAN,0,new int[]{0,0,0},List.of(Arrays.copyOfRange(body,0,1),Arrays.copyOfRange(body,1,4),Arrays.copyOfRange(body,4,5)));
            c.send("POST","/c/a/x",Map.of(),"");c.through("\r\n\r\n");
            var out=new java.io.ByteArrayOutputStream();out.write(bytes("1\r\n"));out.write(body,0,1);out.write(bytes("\r\n3\r\n"));
            out.write(body,1,3);out.write(bytes("\r\n1\r\n"));out.write(body,4,1);out.write(bytes("\r\n0\r\n\r\n"));
            assertArrayEquals(out.toByteArray(),c.all());
        }
    }
    @Test void delays_are_applied() throws Exception {
        try(var s=ReplayServer.start(0);var c=new RawClient(s.port())) {
            register(s,End.CLEAN,0,new int[]{0,100},List.of(bytes("a"),bytes("b")));
            c.send("POST","/c/a/x",Map.of(),"");c.through("1\r\na\r\n");long start=System.nanoTime();c.through("1\r\nb\r\n");
            assertTrue((System.nanoTime()-start)/1_000_000>=90);
        }
    }
    @Test void client_disconnect_marks_not_served() throws Exception {
        try(var s=ReplayServer.start(0)) {
            register(s,End.CLEAN,0,null,java.util.Collections.nCopies(50,bytes("data: a\n\n")));
            try(var c=new RawClient(s.port())) {c.send("POST","/c/a/x",Map.of(),"");c.through("9\r\ndata: a\n\n\r\n");c.socket.setSoLinger(true,0);}
            long until=System.nanoTime()+2_000_000_000L;
            while(s.log("a").get(0).finishedAtMs()==0 && System.nanoTime()<until)Thread.sleep(10);
            assertTrue(s.log("a").get(0).finishedAtMs()>0);assertFalse(s.log("a").get(0).servedAsScripted());
        }
    }
}
