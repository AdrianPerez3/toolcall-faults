package toolcallfaults.runner.fixture;
import java.util.List;
public record Truth(List<TruthCall> calls, String rawFinish, Boolean streamCompleted, String damage) {}
