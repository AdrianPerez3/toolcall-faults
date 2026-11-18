package toolcallfaults.runner.cell;
import com.fasterxml.jackson.databind.JsonNode;
import toolcallfaults.server.RequestRecord;
import java.util.List;
public record CellResult(Cell cell,int rep,JsonNode resultLine,List<JsonNode> invocations,boolean recorderTorn,
                         List<RequestRecord> requests,boolean flagged,boolean watchdogKilled,Integer exitCode,long elapsedMs, String watchdogProblem) {}
