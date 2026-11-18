package toolcallfaults.runner.cell;
import toolcallfaults.kit.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public class StubAdapter {
    static void post(String url) throws Exception {
        var c=(HttpURLConnection)URI.create(url+"/v1/x").toURL().openConnection();c.setRequestMethod("POST");c.setDoOutput(true);
        c.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));c.getInputStream().readAllBytes();c.disconnect();
    }
    public static void main(String[] argv) throws Exception {
        var f=Flags.parse(argv);var r=ResultLine.start(f,"stub","test");var rec=new Recorder(f.workdir());var signals=new Signals(rec);long start=System.currentTimeMillis();
        Map<String,Object> args=new LinkedHashMap<>();for(String key:List.of("priority","title","body"))args.put(key,null);
        post(f.baseUrl());
        switch(f.module()) {
            case "hang" -> {Files.writeString(f.workdir().resolve("pid"),Long.toString(ProcessHandle.current().pid()));Thread.sleep(60000);}
            case "terminal-before-kill" -> {signals.complete();Thread.sleep(60000);}
            case "no-line" -> {return;}
            case "late-tool" -> {
                var out=new java.io.FileOutputStream(java.io.FileDescriptor.out);
                out.write("{\"format\":\"tcf.result/v1\",\"terminal_signals\":[\"complete\"],\"caller\":{\"finish_reasons\":[],\"tool_calls\":[]},\"tool_invocations\":[]}\n".getBytes(StandardCharsets.UTF_8));out.flush();rec.invoked("record_note",args);return;
            }
            case "torn" -> {Files.writeString(f.workdir().resolve(Recorder.FILE),"{\"seq\":1");}
            case "env" -> r.callbackOnly("environment",new ArrayList<>(System.getenv().keySet()));
            default -> {rec.invoked("record_note",args);post(f.baseUrl());}
        }
        signals.complete();r.emitAndHalt(signals,rec,start);
    }
}
