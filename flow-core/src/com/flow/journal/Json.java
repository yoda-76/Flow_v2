package com.flow.journal;

/**
 * Minimal dependency-free JSON-object-line builder. No JSON library is on
 * the classpath anywhere in this project (pure javac against the portable
 * JDK and mwave_sdk.jar only, no package manager -- see motivewave/
 * CLAUDE.md's build toolchain note), and the journal's field set is
 * always flat primitives/strings, so a fluent builder is simpler than
 * pulling in a dependency this offline build can't fetch anyway.
 */
public final class Json {
  private final StringBuilder sb = new StringBuilder();
  private boolean first = true;

  public static Json object() {
    Json j = new Json();
    j.sb.append('{');
    return j;
  }

  private Json comma() {
    if (!first) sb.append(',');
    first = false;
    return this;
  }

  public Json field(String name, String value) {
    comma();
    sb.append('"').append(escape(name)).append("\":");
    if (value == null) {
      sb.append("null");
    } else {
      sb.append('"').append(escape(value)).append('"');
    }
    return this;
  }

  public Json field(String name, long value) {
    comma();
    sb.append('"').append(escape(name)).append("\":").append(value);
    return this;
  }

  public Json field(String name, double value) {
    comma();
    sb.append('"').append(escape(name)).append("\":").append(value);
    return this;
  }

  public Json field(String name, boolean value) {
    comma();
    sb.append('"').append(escape(name)).append("\":").append(value);
    return this;
  }

  public Json fieldOrNull(String name, Integer value) {
    comma();
    sb.append('"').append(escape(name)).append("\":");
    sb.append(value == null ? "null" : value.toString());
    return this;
  }

  public Json fieldOrNull(String name, Double value) {
    comma();
    sb.append('"').append(escape(name)).append("\":");
    sb.append(value == null ? "null" : value.toString());
    return this;
  }

  public Json fieldOrNull(String name, Long value) {
    comma();
    sb.append('"').append(escape(name)).append("\":");
    sb.append(value == null ? "null" : value.toString());
    return this;
  }

  /**
   * Embeds a pre-built JSON fragment (array or object) verbatim, not as
   * an escaped string value -- added for D-58's liquidity snapshot
   * record, the first decisions-tier record needing anything beyond a
   * flat set of primitive fields (bidRows/askRows, each a JSON array of
   * {"p":...,"s":...} objects). Caller is responsible for rawJson being
   * valid JSON; this class has no parser to check it.
   */
  public Json fieldRaw(String name, String rawJson) {
    comma();
    sb.append('"').append(escape(name)).append("\":").append(rawJson);
    return this;
  }

  public String build() {
    return sb.append('}').toString();
  }

  private static String escape(String s) {
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.toString();
  }
}
