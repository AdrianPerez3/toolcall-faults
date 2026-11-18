package toolcallfaults.kit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
public final class Versions {
    private Versions() {}
    public static Map<String,String> parse(List<String> lines){
        Map<String,String> result=new TreeMap<>();
        for(String line:lines){String[] parts=line.trim().split("\\s+",2)[0].split(":");
            if((parts.length==5||parts.length==6)&&Set.of("compile","runtime","test","provided","system").contains(parts[parts.length-1]))result.put(parts[0]+":"+parts[1],parts[parts.length-2]);}
        return result;
    }
    public static String sha256(List<String> lines){
        String text=String.join("\n",parse(lines).entrySet().stream().map(e->e.getKey()+":"+e.getValue()).toList());
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static List<String> bundled(){try(var in=Versions.class.getResourceAsStream("/tcf-deps.txt")){
        return in==null?List.of():new String(in.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
    }catch(IOException e){throw new UncheckedIOException(e);}}
}
