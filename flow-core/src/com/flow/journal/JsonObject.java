package com.flow.journal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Companion reader to Json (the writer). Parses exactly the flat,
 * single-level {"key":value,...} shape Json.object() produces -- no
 * nested objects/arrays, because nothing in this journal ever writes
 * one. Hand-rolled for the same reason Json is: no JSON library is on
 * this offline build's classpath.
 */
public final class JsonObject {
  private final Map<String, String> raw; // key -> still-JSON-encoded value text

  private JsonObject(Map<String, String> raw) {
    this.raw = raw;
  }

  public static JsonObject parse(String line) {
    Map<String, String> map = new LinkedHashMap<>();
    int i = skipWs(line, 0);
    if (i >= line.length() || line.charAt(i) != '{') {
      throw new IllegalArgumentException("not a JSON object: " + line);
    }
    i = skipWs(line, i + 1);
    if (i < line.length() && line.charAt(i) == '}') return new JsonObject(map);

    while (true) {
      i = skipWs(line, i);
      if (line.charAt(i) != '"') throw new IllegalArgumentException("expected key string at " + i + " in: " + line);
      int[] end = new int[1];
      String key = readString(line, i, end);
      i = skipWs(line, end[0]);
      if (line.charAt(i) != ':') throw new IllegalArgumentException("expected ':' at " + i + " in: " + line);
      i = skipWs(line, i + 1);
      String val = readValueRaw(line, i, end);
      i = end[0];
      map.put(key, val);
      i = skipWs(line, i);
      if (i >= line.length()) throw new IllegalArgumentException("unterminated object: " + line);
      char c = line.charAt(i);
      if (c == ',') { i++; continue; }
      if (c == '}') break;
      throw new IllegalArgumentException("expected ',' or '}' at " + i + " in: " + line);
    }
    return new JsonObject(map);
  }

  private static int skipWs(String s, int i) {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    return i;
  }

  /** start points at the opening quote; returns unescaped content, sets end[0] just past the closing quote. */
  private static String readString(String s, int start, int[] end) {
    StringBuilder out = new StringBuilder();
    int i = start + 1;
    while (true) {
      char c = s.charAt(i);
      if (c == '"') { i++; break; }
      if (c == '\\') {
        char n = s.charAt(i + 1);
        switch (n) {
          case '"' -> out.append('"');
          case '\\' -> out.append('\\');
          case '/' -> out.append('/');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
            i += 4;
          }
          default -> out.append(n);
        }
        i += 2;
      } else {
        out.append(c);
        i++;
      }
    }
    end[0] = i;
    return out.toString();
  }

  /** Returns the still-encoded token (quotes kept for strings) for a string/number/true/false/null value. */
  private static String readValueRaw(String s, int start, int[] end) {
    if (s.charAt(start) == '"') {
      int i = start + 1;
      while (true) {
        char c = s.charAt(i);
        if (c == '\\') { i += 2; continue; }
        if (c == '"') { i++; break; }
        i++;
      }
      end[0] = i;
      return s.substring(start, i);
    }
    int i = start;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
      i++;
    }
    end[0] = i;
    return s.substring(start, i);
  }

  public boolean has(String key) {
    return raw.containsKey(key);
  }

  public boolean isNull(String key) {
    String v = raw.get(key);
    return v == null || v.equals("null");
  }

  public String getString(String key) {
    String v = requireValue(key);
    if (v.equals("null")) return null;
    return readString(v, 0, new int[1]);
  }

  public long getLong(String key) {
    return Long.parseLong(requireValue(key));
  }

  public int getInt(String key) {
    return Integer.parseInt(requireValue(key));
  }

  public Integer getIntOrNull(String key) {
    String v = requireValue(key);
    return v.equals("null") ? null : Integer.valueOf(v);
  }

  public double getDouble(String key) {
    return Double.parseDouble(requireValue(key));
  }

  public boolean getBoolean(String key) {
    return Boolean.parseBoolean(requireValue(key));
  }

  private String requireValue(String key) {
    String v = raw.get(key);
    if (v == null) throw new IllegalStateException("missing field '" + key + "' -- known fields: " + raw.keySet());
    return v;
  }
}
