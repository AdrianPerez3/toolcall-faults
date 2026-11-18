package toolcallfaults.kit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Recorder {
    public static final String FILE = "tool-invocations.jsonl";
    private static final Map<String, String> REPLIES = Map.of(
            "record_note", "note recorded",
            "set_level", "level set",
            "flush_notes", "notes flushed",
            "write_file", "file written");

    private final Path file;
    private final List<Map<String, Object>> invocations = new ArrayList<>();

    public Recorder(Path workdir) {
        file = workdir.resolve(FILE);
        try {
            Files.createDirectories(workdir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path workdir() {
        return file.toAbsolutePath().getParent();
    }

    /** A framework can send the recorder's text as-is or encode its string return value as JSON. */
    public static boolean isSuccessReply(String tool, String text) {
        String reply = REPLIES.get(tool);
        return reply != null && (reply.equals(text) || Json.write(reply).equals(text));
    }

    public synchronized String invoked(String tool, Map<String, Object> args) {
        String result = REPLIES.get(tool);
        if (result == null) throw new IllegalArgumentException("Unknown recorder tool " + tool);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", invocations.size() + 1);
        row.put("name", tool);
        row.put("args", new LinkedHashMap<>(args));
        try (var out = new FileOutputStream(file.toFile(), true)) {
            out.write((Json.write(row) + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        invocations.add(row);
        return result;
    }

    public synchronized int count() {
        return invocations.size();
    }

    synchronized List<Map<String, Object>> snapshot() {
        return List.copyOf(invocations);
    }
}
