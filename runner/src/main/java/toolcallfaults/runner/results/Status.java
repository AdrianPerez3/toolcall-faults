package toolcallfaults.runner.results;

import java.util.Map;

public record Status(boolean stable, Map<String, Integer> distribution) {}
