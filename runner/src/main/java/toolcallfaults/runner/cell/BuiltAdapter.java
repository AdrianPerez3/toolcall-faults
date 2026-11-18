package toolcallfaults.runner.cell;
import java.util.List;
public record BuiltAdapter(String target,String version,String mainClass,List<String> classpath,List<String> typedErrorPrefixes) {}
