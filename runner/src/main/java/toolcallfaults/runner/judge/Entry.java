package toolcallfaults.runner.judge;

public record Entry(
    String cell,
    String category,
    String issue,
    String status,
    String disclosure,
    String filedOn,
    boolean maintainerAnswered,
    String maintainerPosition) {}
