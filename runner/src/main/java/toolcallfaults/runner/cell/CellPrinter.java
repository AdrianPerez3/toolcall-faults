package toolcallfaults.runner.cell;
import java.util.*;
import toolcallfaults.runner.fixture.Fixture;
import toolcallfaults.runner.oracle.Classified;
public final class CellPrinter {
    private CellPrinter() {}
    public static List<String> render(Fixture fixture,CellResult result,Classified classified){
        var line=result.resultLine();List<String> versions=new ArrayList<>();if(line!=null)line.path("versions").fields().forEachRemaining(e->versions.add(e.getKey()+"="+e.getValue().asText()));
        StringBuilder invocations=new StringBuilder(Integer.toString(result.invocations().size()));for(var row:result.invocations())invocations.append("  ").append(row.path("name").asText()).append(" ").append(row.path("args"));
        return List.of(row("target:",result.cell().key()),row("versions:",String.join(" ",versions)),row("fixture:",fixture.file().toString()),row("invocations:",invocations.toString()),row("caller:",line==null?"null":line.path("caller").toString()),row("requests:",Integer.toString(result.requests().size())),row("category:",classified.category().name()));
    }
    private static String row(String label,String value){return String.format("%-15s%s",label,value);}
}
