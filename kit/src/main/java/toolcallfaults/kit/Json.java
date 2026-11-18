package toolcallfaults.kit;

import java.util.*;
import java.util.regex.Pattern;

/** Small writer and syntax validator; no binding, schema, or provider-specific behaviour. */
public final class Json {
  private Json() {}

  record Raw(String text) {}

  public static String write(Object value) {
    if (value == null) return "null";
    if (value instanceof Raw raw) return compact(raw.text());
    if (value instanceof String s) {
      StringBuilder b = new StringBuilder("\"");
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"' -> b.append("\\\"");
          case '\\' -> b.append("\\\\");
          case '\n' -> b.append("\\n");
          case '\r' -> b.append("\\r");
          case '\t' -> b.append("\\t");
          case '\b' -> b.append("\\b");
          case '\f' -> b.append("\\f");
          default -> {
            if (c < 32
                || (Character.isSurrogate(c)
                    && !(Character.isHighSurrogate(c)
                        && i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1)))
                    && !(Character.isLowSurrogate(c)
                        && i > 0
                        && Character.isHighSurrogate(s.charAt(i - 1)))))
              b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
          }
        }
      }
      return b.append('"').toString();
    }
    if (value instanceof Boolean) return value.toString();
    if (value instanceof Number n) {
      if (!NUMBER.matcher(n.toString()).matches())
        throw new IllegalArgumentException("Not a JSON number");
      return n.toString();
    }
    if (value instanceof Map<?, ?> m) {
      StringJoiner j = new StringJoiner(",", "{", "}");
      for (var e : m.entrySet()) {
        if (!(e.getKey() instanceof String))
          throw new IllegalArgumentException("JSON object key must be a string");
        j.add(write(e.getKey()) + ":" + write(e.getValue()));
      }
      return j.toString();
    }
    if (value instanceof List<?> list) {
      StringJoiner j = new StringJoiner(",", "[", "]");
      for (Object item : list) j.add(write(item));
      return j.toString();
    }
    throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
  }

  /**
   * Remove only JSON whitespace outside strings; preserve the original string and number tokens.
   */
  private static String compact(String text) {
    StringBuilder out = new StringBuilder();
    boolean inString = false, escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (inString) {
        out.append(c);
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') inString = false;
      } else if (c == '"') {
        inString = true;
        out.append(c);
      } else if (" \t\r\n".indexOf(c) < 0) out.append(c);
    }
    return out.toString();
  }

  private static final Pattern NUMBER =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

  public static boolean isValid(String text) {
    if (text == null) return false;
    try {
      Parser p = new Parser(text);
      p.value(0);
      p.ws();
      return p.i == text.length();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static final class Parser {
    final String s;
    int i;

    Parser(String s) {
      this.s = s;
    }

    void ws() {
      while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    boolean take(char c) {
      ws();
      if (i < s.length() && s.charAt(i) == c) {
        i++;
        return true;
      }
      return false;
    }

    void need(char c) {
      if (!take(c)) throw new IllegalArgumentException("Invalid JSON");
    }

    void string() {
      need('"');
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') return;
        if (c < 32) throw new IllegalArgumentException("Control in string");
        if (c == '\\') {
          if (i == s.length()) break;
          char escape = s.charAt(i++);
          if (escape == 'u') {
            for (int j = 0; j < 4; j++)
              if (i == s.length() || "0123456789abcdefABCDEF".indexOf(s.charAt(i++)) < 0)
                throw new IllegalArgumentException("Invalid escape");
          } else if ("\"\\/bfnrt".indexOf(escape) < 0)
            throw new IllegalArgumentException("Invalid escape");
        }
      }
      throw new IllegalArgumentException("Unclosed string");
    }

    void value(int depth) {
      if (depth > 256) throw new IllegalArgumentException("JSON too deep");
      ws();
      if (i == s.length()) throw new IllegalArgumentException("Missing value");
      char c = s.charAt(i);
      if (c == '"') {
        string();
        return;
      }
      if (take('{')) {
        if (take('}')) return;
        do {
          string();
          need(':');
          value(depth + 1);
        } while (take(','));
        need('}');
        return;
      }
      if (take('[')) {
        if (take(']')) return;
        do {
          value(depth + 1);
        } while (take(','));
        need(']');
        return;
      }
      for (String literal : List.of("true", "false", "null"))
        if (s.startsWith(literal, i)) {
          i += literal.length();
          return;
        }
      var m = NUMBER.matcher(s);
      m.region(i, s.length());
      if (!m.lookingAt()) throw new IllegalArgumentException("Invalid value");
      i = m.end();
    }
  }
}
