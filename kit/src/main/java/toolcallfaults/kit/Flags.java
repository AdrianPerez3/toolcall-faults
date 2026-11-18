package toolcallfaults.kit;
import java.nio.file.Path;
import java.util.*;
public final class Flags {
    private final Map<String,String> values;
    private Flags(Map<String,String> values){this.values=Map.copyOf(values);}
    public static Flags parse(String[] argv){
        Set<String> required=Set.of("--wire","--surface","--tools","--base-url","--timeout-ms","--workdir");
        Set<String> optional=Set.of("--module","--http-client","--prompt");Map<String,String> values=new LinkedHashMap<>();
        for(int i=0;i<argv.length;i+=2){String flag=argv[i];
            if(!required.contains(flag)&&!optional.contains(flag))throw new IllegalArgumentException("Unknown flag "+flag);
            if(i+1==argv.length||argv[i+1].startsWith("--"))throw new IllegalArgumentException("Missing value for "+flag);
            if(values.putIfAbsent(flag,argv[i+1])!=null)throw new IllegalArgumentException("Duplicate flag "+flag);
        }
        for(String flag:required)if(!values.containsKey(flag)||values.get(flag).isBlank())throw new IllegalArgumentException("Missing "+flag);
        try{if(Long.parseLong(values.get("--timeout-ms"))<=0)throw new NumberFormatException();}
        catch(NumberFormatException e){throw new IllegalArgumentException("Invalid --timeout-ms",e);}
        return new Flags(values);
    }
    public String wire(){return values.get("--wire");} public String module(){return values.getOrDefault("--module","");}
    public String surface(){return values.get("--surface");} public List<String> tools(){return List.of(values.get("--tools").split(","));}
    public String baseUrl(){return values.get("--base-url");} public long timeoutMs(){return Long.parseLong(values.get("--timeout-ms"));}
    public Path workdir(){return Path.of(values.get("--workdir"));} public String httpClient(){return values.getOrDefault("--http-client","");}
    public String prompt(){return values.getOrDefault("--prompt","Record a note about the quarterly report.");}
}
