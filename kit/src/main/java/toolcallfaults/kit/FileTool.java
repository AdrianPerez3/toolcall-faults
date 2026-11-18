package toolcallfaults.kit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class FileTool {
  private FileTool() {}

  public static String write(Path workdir, String path, String content) {
    Path name = Path.of(path).getFileName();
    if (name == null || name.toString().equals(".") || name.toString().equals(".."))
      throw new IllegalArgumentException("Expected a file name");
    try {
      Files.writeString(workdir.resolve(name), content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return "Wrote " + content.codePointCount(0, content.length()) + " characters to " + name;
  }
}
