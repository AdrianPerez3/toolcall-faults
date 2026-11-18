package toolcallfaults.runner.lint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class SecretPathScan {
  private SecretPathScan() {}

  private static final List<Pattern> PATTERNS =
      List.of(
          Pattern.compile("sk-(ant-)?[A-Za-z0-9_-]{20,}"),
          Pattern.compile("gh[opsu]_[A-Za-z0-9]{30,}"),
          Pattern.compile("github" + "_pat_[A-Za-z0-9_]{20,}"),
          Pattern.compile("A[KS]IA[0-9A-Z]{16}"),
          Pattern.compile("-----BEGIN [A-Z ]*PRIV" + "ATE KEY-----"),
          Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{16,}"),
          Pattern.compile("/Us" + "ers/[^/\\s\"'<>]+"),
          Pattern.compile("/ho" + "me/[^/\\s\"'<>]+"),
          Pattern.compile("/Vol" + "umes/"),
          Pattern.compile("/priv" + "ate/(tmp|var)/"),
          Pattern.compile("/var/fol" + "ders/"),
          Pattern.compile("[A-Za-z]:\\\\{1,2}Users\\\\{1,2}"),
          Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
  private static final Set<String> EXTENSIONS =
      Set.of("json", "jsonl", "md", "java", "xml", "yml", "yaml", "txt", "sh");

  public static List<Problem> scan(List<Path> files, Set<String> allow) {
    List<Problem> problems = new ArrayList<>();
    for (Path file : files) {
      try {
        byte[] bytes = Files.readAllBytes(file);
        problems.addAll(scan(file.toString(), bytes, allow));
      } catch (IOException e) {
        problems.add(new Problem(file.toString(), "$", "cannot read file"));
      }
    }
    return List.copyOf(problems);
  }

  public static List<Problem> scan(String file, byte[] bytes, Set<String> allow) {
    return scan(file, bytes, allow, null);
  }

  static List<Problem> scan(String file, byte[] bytes, Set<String> allow, Pattern withheld) {
    List<Problem> problems = new ArrayList<>();
    boolean binary = false;
    for (int i = 0; i < Math.min(8192, bytes.length); i++)
      if (bytes[i] == 0) {
        binary = true;
        break;
      }
    if (binary) return List.of();
    String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      if (withheld != null && lines[i].contains("|")
          && withheld.matcher(lines[i].replace("\\|", "|")).find())
        problems.add(new Problem(file, "line " + (i + 1), "contains a withheld cell identifier"));
      for (Pattern pattern : PATTERNS) {
        var matcher = pattern.matcher(lines[i]);
        while (matcher.find()) {
          String match = matcher.group();
          if (allow.contains(match)) continue;
          if (pattern == PATTERNS.get(PATTERNS.size() - 1)
              && EXTENSIONS.contains(match.substring(match.lastIndexOf('.') + 1))) continue;
          // Do not repeat a possible secret in the diagnostic.
          problems.add(
              new Problem(file, "line " + (i + 1), "forbidden secret, address or local path"));
        }
      }
    }
    return List.copyOf(problems);
  }
}
