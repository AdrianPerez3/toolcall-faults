package toolcallfaults.runner.oracle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RequestBodiesTest {
    @Test void tool_result_found_in_pretty_printed_body(){var r=RequestBodies.toolResults("openai-chat","{\"messages\" : [{\"role\" : \"tool\", \"tool_call_id\" : \"call_TCF1\", \"content\" : \"boom\"}]}");assertEquals(1,r.size());assertEquals("call_TCF1",r.get(0).callId());assertEquals("boom",r.get(0).text());}
    @Test void tool_result_found_as_block_list(){
        assertEquals("boom",RequestBodies.toolResults("openai-chat","{\"messages\":[{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":[{\"type\":\"text\",\"text\":\"boom\"}]}]}").get(0).text());
        var r=RequestBodies.toolResults("anthropic","{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01TCF1\",\"content\":[{\"type\":\"text\",\"text\":\"boom\"}]}]}]}");assertEquals("toolu_01TCF1",r.get(0).callId());assertEquals("boom",r.get(0).text());
    }
    @Test void no_tool_result_in_first_turn(){assertTrue(RequestBodies.toolResults("openai-chat","{\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}").isEmpty());}
}
