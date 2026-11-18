package toolcallfaults.runner.lint;

public record Problem(String file, String jsonPath, String message) {
    @Override public String toString() { return file + ": " + jsonPath + ": " + message; }
}
