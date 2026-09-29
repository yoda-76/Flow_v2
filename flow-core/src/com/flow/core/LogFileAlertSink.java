package com.flow.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * PLACEHOLDER alert channel (2026-09-28): appends one line per alert to a file (logs/alerts.log by default) so the
 * alert path can be exercised end to end before the Telegram bot exists. Replace with a TelegramAlertSink that
 * implements the same {@link AlertSink}. Line format: {@code 2026-09-28T23:30:11+05:30 [ALERT] FEED_STALE: message}.
 */
public final class LogFileAlertSink implements AlertSink {
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
  private final Path file;
  private final ZoneId zone;

  public LogFileAlertSink(Path file) {
    this(file, ZoneId.systemDefault());
  }

  public LogFileAlertSink(Path file, ZoneId zone) {
    this.file = file;
    this.zone = zone;
  }

  @Override
  public synchronized void send(String severity, String key, String message, long timeMs) {
    String line = TS.format(Instant.ofEpochMilli(timeMs).atZone(zone)) + " [" + severity + "] " + key + ": "
        + message.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator();
    try {
      if (file.getParent() != null) Files.createDirectories(file.getParent());
      Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
