package com.flow.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * D-75: raw-log retention, per the user's own stated policy
 * (`docs/dynamic/todo.md` §4b, 2026-09-20 scratch note) -- deletes
 * `raw.jsonl` from every session directory whose embedded start time is
 * older than the retention window; `decisions.jsonl` and the directory
 * itself are never touched here. D-15's own "long retention" tier
 * already carries exactly what the user called "the analysed data"
 * (price trace, liquidity snapshots, etc.), so this is the raw tier's
 * own short-rolling-retention half of D-07, finally given a concrete
 * number and a real mechanism.
 *
 * Zero SDK dependency -- pure file I/O, so this lives in flow-core (like
 * everything else that doesn't structurally need the SDK) rather than
 * buried as a private helper in `FlowRuntimeStudy`, and can be tested
 * synthetically against a real temp directory instead of only by
 * running the actual platform.
 *
 * Session directory names are `<strategyId>_<sessionStartMs>_inst
 * <instanceId>` (see `FlowRuntimeStudy.startSession()`'s own `sessionDir`
 * construction) -- `sessionStartMs` is parsed directly out of the name
 * rather than trusting filesystem mtimes, since `raw.jsonl` gets
 * appended to throughout a session (shifting its own mtime continuously)
 * and a copy/backup could shift a directory's mtime independent of when
 * the session actually started.
 */
public final class LogRetention {
  public static final long DEFAULT_RETENTION_MS = 48L * 60 * 60 * 1000;

  private LogRetention() {}

  /**
   * Best-effort throughout: a directory whose name doesn't match the
   * pattern, or a file that fails to delete, is skipped and simply not
   * counted -- never fatal to the caller, matching every other one-time
   * startup check in `FlowRuntimeStudy` (risk config, warm-start).
   * Returns how many `raw.jsonl` files were actually deleted.
   */
  public static int pruneOldRawLogs(Path logRoot, long nowMs, long retentionMs) {
    int pruned = 0;
    try (var stream = Files.list(logRoot)) {
      for (Path dir : (Iterable<Path>) stream::iterator) {
        if (!Files.isDirectory(dir)) continue;
        long startMs = parseSessionStartMs(dir.getFileName().toString());
        if (startMs < 0 || nowMs - startMs < retentionMs) continue;
        try {
          if (Files.deleteIfExists(dir.resolve("raw.jsonl"))) pruned++;
        } catch (IOException ignored) {
          // e.g. a file still open/locked -- skip, try again next call.
        }
      }
    } catch (IOException e) {
      return 0; // logRoot missing or unreadable -- nothing to prune, not fatal
    }
    return pruned;
  }

  /**
   * Parses sessionStartMs out of "&lt;strategyId&gt;_&lt;sessionStartMs&gt;_inst&lt;instanceId&gt;"
   * -- strategyId itself may contain underscores, so anchored from the
   * right via the "_inst" suffix. Returns -1 if the name doesn't match
   * (not a session directory at all, or some other unrelated entry).
   */
  public static long parseSessionStartMs(String dirName) {
    int instIdx = dirName.lastIndexOf("_inst");
    if (instIdx < 0) return -1;
    String beforeInst = dirName.substring(0, instIdx);
    int lastUnderscore = beforeInst.lastIndexOf('_');
    if (lastUnderscore < 0) return -1;
    try {
      return Long.parseLong(beforeInst.substring(lastUnderscore + 1));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
