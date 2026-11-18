package toolcallfaults.runner.oracle;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import toolcallfaults.runner.fixture.FixtureLoader;
public final class RequestBodies {
    private RequestBodies() {}
    public static boolean parseable(String requestBody){
        try{JsonNode n=FixtureLoader.JSON.readTree(requestBody);return n!=null&&!n.isMissingNode();}catch(Exception e){return false;}
    }
    public static List<ToolResult> toolResults(String wire,String requestBody){
        List<ToolResult> results=new ArrayList<>();
        try{for(JsonNode message:FixtureLoader.JSON.readTree(requestBody).path("messages")){
            if(wire.equals("openai-chat")&&message.path("role").asText().equals("tool")&&message.has("tool_call_id"))results.add(new ToolResult(message.get("tool_call_id").asText(),text(message.path("content"))));
            if(wire.equals("anthropic"))for(JsonNode block:message.path("content"))if(block.path("type").asText().equals("tool_result"))results.add(new ToolResult(block.path("tool_use_id").asText(),text(block.path("content"))));
        }}catch(Exception ignored){ }
        return results;
    }
    private static String text(JsonNode content){if(content.isTextual())return content.asText();StringBuilder b=new StringBuilder();for(JsonNode block:content)if(block.path("type").asText().equals("text"))b.append(block.path("text").asText());return b.toString();}
}
