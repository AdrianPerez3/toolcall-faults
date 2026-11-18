package toolcallfaults.server;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayServerTest {
    static Exchange whole(String path, String body) {
        return new Exchange("POST",path,200,Map.of(),BodyKind.WHOLE,
                List.of(body.getBytes(StandardCharsets.UTF_8)),End.CLEAN,0,null);
    }
    static String req(ReplayServer s, String cell, String path) throws Exception {
        return RawClient.request(s.port(),"POST","/c/"+cell+path,Map.of(),"{}");
    }
    @Test void kth_request_gets_kth_exchange() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a1",new Script(List.of(whole("/v1/chat","one"),whole("/v1/chat","two"))));
            assertTrue(req(s,"a1","/v1/chat").endsWith("one"));
            assertTrue(req(s,"a1","/v1/chat").endsWith("two"));
            assertTrue(req(s,"a1","/v1/chat").endsWith("two"));
            assertEquals(3,s.log("a1").size());assertEquals(1,s.log("a1").get(2).exchangeIndex());
        }
    }
    @Test void non_matching_request_gets_404() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a1",new Script(List.of(whole("/v1/chat","one"))));
            String response=RawClient.request(s.port(),"GET","/c/a1/v1/other",Map.of(),"");
            assertTrue(response.startsWith("HTTP/1.1 404"));assertTrue(response.endsWith("no exchange for GET /v1/other"));
            assertEquals(-1,s.log("a1").get(0).exchangeIndex());assertTrue(req(s,"a1","/v1/chat").endsWith("one"));
        }
    }
    @Test void query_string_is_ignored_for_matching() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a1",new Script(List.of(whole("/v1/messages","one"))));
            assertTrue(req(s,"a1","/v1/messages?beta=true").startsWith("HTTP/1.1 200"));
            assertEquals("/v1/messages?beta=true",s.log("a1").get(0).path());
        }
    }
    @Test void wildcard_matches_any_run() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a1",new Script(List.of(whole("/v1/models/*:generate","one"))));
            assertTrue(req(s,"a1","/v1/models/m1:generate").endsWith("one"));
        }
    }
    @Test void ninth_request_gets_409_and_flags() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a1",new Script(List.of(whole("/x","one"))));
            for(int i=0;i<8;i++) assertTrue(req(s,"a1","/x").startsWith("HTTP/1.1 200"));
            assertTrue(req(s,"a1","/x").startsWith("HTTP/1.1 409"));assertTrue(s.flagged("a1"));
        }
    }
    @Test void unknown_cell_gets_404() throws Exception {
        try(var s=ReplayServer.start(0)) {assertTrue(req(s,"missing","/x").startsWith("HTTP/1.1 404"));}
    }
    @Test void credential_values_never_logged() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a1",new Script(List.of(whole("/x","one"))));
            RawClient.request(s.port(),"POST","/c/a1/x",Map.of("Authorization","Bearer SECRETVALUE123","x-api-key","KEYSECRET9"),"{}");
            assertTrue(s.log("a1").get(0).hasCredentialHeader());
            assertFalse(s.log("a1").toString().contains("SECRETVALUE123"));assertFalse(s.log("a1").toString().contains("KEYSECRET9"));
        }
    }
    @Test void retry_header_recorded() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a1",new Script(List.of(whole("/x","one"))));
            RawClient.request(s.port(),"POST","/c/a1/x",Map.of("x-stainless-retry-count","1"),"{}");
            assertEquals("1",s.log("a1").get(0).retryHeaders().get("x-stainless-retry-count"));
        }
    }
    @Test void body_logged_up_to_16kB() throws Exception {
        try(var s=ReplayServer.start(0)) {
            s.register("a1",new Script(List.of(whole("/x","one"))));
            RawClient.request(s.port(),"POST","/c/a1/x",Map.of(),"a".repeat(20000));
            assertEquals("a".repeat(16384),s.log("a1").get(0).body());
        }
    }
    @Test void ninety_concurrent_requests_three_cells() throws Exception {
        try(var s=ReplayServer.start(0)) {
            var pool=Executors.newFixedThreadPool(30);
            try {
                for(String id:List.of("a","b","c"))s.register(id,new Script(List.of(whole("/x",id))));
                var jobs=new ArrayList<java.util.concurrent.Callable<Void>>();
                for(int i=0;i<30;i++)for(String id:List.of("a","b","c"))jobs.add(()->{
                    String response=req(s,id,"/x");
                    if(response.startsWith("HTTP/1.1 200"))assertTrue(response.endsWith("\r\n\r\n"+id));
                    else assertTrue(response.startsWith("HTTP/1.1 409"));
                    return null;
                });
                for(var future:pool.invokeAll(jobs))future.get();
                for(String id:List.of("a","b","c")) {
                    assertEquals(30,s.log(id).size());
                    assertEquals(8,s.log(id).stream().filter(r->r.exchangeIndex()==0).count());
                    assertTrue(s.flagged(id));
                }
            } finally {pool.shutdownNow();}
        }
    }
    @Test void binds_loopback_only() throws Exception {
        try(var s=ReplayServer.start(0);var client=new RawClient(s.port())) {
            assertTrue(client.socket.getInetAddress().isLoopbackAddress());
            assertEquals(InetAddress.getByName("127.0.0.1"),client.socket.getInetAddress());
            for (var iface : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (var address : java.util.Collections.list(iface.getInetAddresses())) {
                    if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                        try (var probe = new java.net.Socket()) {
                            assertThrows(java.io.IOException.class, () -> probe.connect(new java.net.InetSocketAddress(address,s.port()),300));
                        }
                    }
                }
            }
        }
    }
}
