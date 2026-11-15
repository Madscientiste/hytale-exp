package com.madscientiste.rcon.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Strict reader and writer for a flat JSON object: one level of string keys mapped to strings,
 * whole numbers, booleans, null, or a list of strings. That is all the config file needs, and being
 * strict means a damaged file is reported instead of half-read.
 */
final class FlatJson {

  private final String text;
  private int pos = 0;

  private FlatJson(String text) {
    this.text = text;
  }

  /**
   * Parse a flat JSON object. Values are String, Long, Boolean, List of String, or null.
   *
   * @throws IllegalArgumentException with the reason and position when the text is not a flat JSON
   *     object
   */
  @Nonnull
  static Map<String, Object> parse(@Nonnull String text) {
    return new FlatJson(text).parseObject();
  }

  /** Format a map of String, Number, Boolean and List-of-String values as a JSON object. */
  @Nonnull
  static String format(@Nonnull Map<String, Object> map) {
    StringBuilder json = new StringBuilder("{\n");
    boolean first = true;

    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) {
        json.append(",\n");
      }
      first = false;

      json.append("  ").append(quote(entry.getKey())).append(": ");
      Object value = entry.getValue();
      if (value instanceof String s) {
        json.append(quote(s));
      } else if (value instanceof List<?> list) {
        json.append('[');
        for (int i = 0; i < list.size(); i++) {
          json.append(i > 0 ? ", " : "").append(quote(String.valueOf(list.get(i))));
        }
        json.append(']');
      } else {
        json.append(value);
      }
    }

    json.append("\n}\n");
    return json.toString();
  }

  private Map<String, Object> parseObject() {
    Map<String, Object> result = new LinkedHashMap<>();

    skipWhitespace();
    expect('{');
    skipWhitespace();

    if (peek() == '}') {
      pos++;
    } else {
      while (true) {
        skipWhitespace();
        String key = parseString();
        if (result.containsKey(key)) {
          throw error("duplicate key \"" + key + "\"");
        }
        skipWhitespace();
        expect(':');
        skipWhitespace();
        result.put(key, parseValue());
        skipWhitespace();

        char c = next();
        if (c == '}') {
          break;
        }
        if (c != ',') {
          pos--;
          throw error("expected ',' or '}'");
        }
      }
    }

    skipWhitespace();
    if (pos != text.length()) {
      throw error("unexpected text after the closing '}'");
    }
    return result;
  }

  private Object parseValue() {
    char c = peek();
    if (c == '"') {
      return parseString();
    }
    if (c == '-' || (c >= '0' && c <= '9')) {
      return parseWholeNumber();
    }
    if (text.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (text.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    if (text.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    if (c == '[') {
      return parseStringList();
    }
    if (c == '{') {
      throw error("nested objects are not supported");
    }
    throw error("expected a value");
  }

  private List<String> parseStringList() {
    List<String> result = new ArrayList<>();
    expect('[');
    skipWhitespace();

    if (peek() == ']') {
      pos++;
      return result;
    }

    while (true) {
      skipWhitespace();
      if (peek() != '"') {
        throw error("a list may only hold strings in double quotes");
      }
      result.add(parseString());
      skipWhitespace();

      char c = next();
      if (c == ']') {
        return result;
      }
      if (c != ',') {
        pos--;
        throw error("expected ',' or ']'");
      }
    }
  }

  private Long parseWholeNumber() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    int firstDigit = pos;
    while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
      pos++;
    }
    // JSON has no leading zeros: 025575 is not a number (and some parsers read it as octal)
    if (pos - firstDigit > 1 && text.charAt(firstDigit) == '0') {
      pos = start;
      throw error("a number must not start with 0");
    }
    if (pos < text.length()) {
      char c = text.charAt(pos);
      if (c == '.' || c == 'e' || c == 'E') {
        throw error("only whole numbers are supported");
      }
    }
    try {
      return Long.parseLong(text.substring(start, pos));
    } catch (NumberFormatException e) {
      pos = start;
      throw error("not a valid whole number");
    }
  }

  private String parseString() {
    expect('"');
    StringBuilder sb = new StringBuilder();

    while (true) {
      char c = next();
      if (c == '"') {
        return sb.toString();
      }
      if (c < 0x20) {
        pos--;
        throw error("control character inside a string");
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }

      char escaped = next();
      switch (escaped) {
        case '"', '\\', '/' -> sb.append(escaped);
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          if (pos + 4 > text.length()) {
            throw error("unfinished \\u escape");
          }
          // Exactly four hex digits; Integer.parseInt alone would also take a sign
          int codeUnit = 0;
          for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(pos + i), 16);
            if (digit < 0 || text.charAt(pos + i) > 'f') {
              throw error("bad \\u escape");
            }
            codeUnit = codeUnit * 16 + digit;
          }
          sb.append((char) codeUnit);
          pos += 4;
        }
        default -> {
          pos--;
          throw error("bad escape \\" + escaped);
        }
      }
    }
  }

  private void skipWhitespace() {
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        return;
      }
      pos++;
    }
  }

  private char peek() {
    if (pos >= text.length()) {
      throw error("unexpected end of file");
    }
    return text.charAt(pos);
  }

  private char next() {
    char c = peek();
    pos++;
    return c;
  }

  private void expect(char expected) {
    if (peek() != expected) {
      throw error("expected '" + expected + "'");
    }
    pos++;
  }

  private IllegalArgumentException error(String reason) {
    int line = 1;
    for (int i = 0; i < pos && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return new IllegalArgumentException("invalid JSON at line " + line + ": " + reason);
  }

  private static String quote(String str) {
    StringBuilder sb = new StringBuilder(str.length() + 2).append('"');
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
