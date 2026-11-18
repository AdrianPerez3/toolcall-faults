package toolcallfaults.kit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class ResultLine {
    private final Flags flags;
    private final String target,api;
    private final PrintStream output;
    private final Map<String,Object> settings=new LinkedHashMap<>(),callback=new LinkedHashMap<>();
    private final List<String> finishes=new ArrayList<>(),watch=new ArrayList<>();
    private final List<Map<String,Object>> calls=new ArrayList<>();
    private final StringBuilder text=new StringBuilder();
    private String httpClient;
    private int chunks;
    private ResultLine(Flags flags,String target,String api){this.flags=flags;this.target=target;this.api=api;httpClient=flags.httpClient();output=System.out;System.setOut(System.err);}
    public static ResultLine start(Flags flags,String target,String api){return new ResultLine(flags,target,api);}
    public synchronized ResultLine watch(String... coordinates){watch.addAll(List.of(coordinates));return this;}
    public synchronized ResultLine httpClient(String name){httpClient=name;return this;}
    public synchronized ResultLine setting(String key,Object value){settings.put(key,value);return this;}
    public synchronized ResultLine callerText(String value){if(value!=null)text.append(value);return this;}
    public synchronized ResultLine callerFinish(String reason){if(reason!=null&&!reason.isEmpty())finishes.add(reason);return this;}
    public synchronized ResultLine callerToolCall(String id,String name,String argumentsRaw){
        Map<String,Object> call=new LinkedHashMap<>();boolean valid=Json.isValid(argumentsRaw);call.put("id",id);call.put("name",name);call.put("arguments_raw",argumentsRaw);call.put("arguments_parsed_ok",valid);call.put("arguments",valid?new Json.Raw(argumentsRaw):null);calls.add(call);return this;
    }
    public synchronized ResultLine chunks(int n){chunks=n;return this;}
    public synchronized ResultLine callbackOnly(String key,Object value){callback.put(key,value);return this;}
    synchronized String render(Signals signals,Recorder recorder,long elapsedMs){
        Map<String,Object> row=new LinkedHashMap<>();row.put("format","tcf.result/v1");row.put("target",target);row.put("module",flags.module());row.put("surface",flags.surface());row.put("api",api);row.put("wire",flags.wire());row.put("tools",flags.tools());
        var dependencies=Versions.bundled();var all=Versions.parse(dependencies);Map<String,Object> versions=new LinkedHashMap<>();for(String key:watch)if(all.containsKey(key))versions.put(key,all.get(key));
        row.put("versions",versions);row.put("classpath_sha256",Versions.sha256(dependencies));Map<String,Object> runtime=new LinkedHashMap<>();runtime.put("java",System.getProperty("java.version"));runtime.put("vendor",System.getProperty("java.vendor"));runtime.put("os",System.getProperty("os.name"));runtime.put("arch",System.getProperty("os.arch"));runtime.put("http_client",httpClient);row.put("runtime",runtime);
        row.put("settings",settings);row.put("terminal_signals",signals.list());row.put("error",signals.errorChain().isEmpty()?null:Map.of("chain",signals.errorChain()));
        Map<String,Object> caller=new LinkedHashMap<>();caller.put("text",text.toString());caller.put("finish_reasons",finishes);caller.put("tool_calls",calls);caller.put("chunks",chunks);caller.put("callback_only",callback);row.put("caller",caller);row.put("tool_invocations",recorder.snapshot());row.put("tools_ran_before_error",signals.toolsRanBeforeError());row.put("elapsed_ms",elapsedMs);return Json.write(row);
    }
    public void emitAndHalt(Signals signals,Recorder recorder,long startedAtMs){
        byte[] bytes=(render(signals,recorder,System.currentTimeMillis()-startedAtMs)+"\n").getBytes(StandardCharsets.UTF_8);
        output.write(bytes,0,bytes.length);output.flush();if(output.checkError())Runtime.getRuntime().halt(1);Runtime.getRuntime().halt(0);
    }
}
