package com.flow.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** The alert path (placeholder log-file channel now, Telegram later): classification, throttling, failure isolation. */
public final class AlertDispatcherTest {
  private static int failures = 0;

  private static void check(String label, boolean ok) {
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static final class Cap implements AlertSink {
    final List<String> sent = new ArrayList<>();
    boolean explode = false;
    @Override public void send(String sev, String key, String msg, long t) {
      if (explode) throw new IllegalStateException("telegram is down");
      sent.add(sev + "|" + key + "|" + msg);
    }
  }

  public static void main(String[] args) throws Exception {
    check("kill switch line is an ALERT", AlertPolicy.forLogLine("KILL_SWITCH_TRIGGERED {...}").severity().equals("ALERT"));
    check("the LONGER prefix wins: FEED_RESUME_FLATTEN_HELD, not FEED_RESUME_FLATTEN",
        AlertPolicy.forLogLine("FEED_RESUME_FLATTEN_HELD stop ...").key().equals("FEED_RESUME_FLATTEN_HELD"));
    check("FEED_RESUMED is INFO (a recovery note)", AlertPolicy.forLogLine("FEED_RESUMED after 290s").severity().equals("INFO"));
    check("study start/stop are INFO", AlertPolicy.forLogLine("DESTROY instance=1").severity().equals("INFO")
        && AlertPolicy.forLogLine("SESSION_START strategyId=x").severity().equals("INFO"));
    check("ordinary chatter is not an alert", AlertPolicy.forLogLine("ORDER_MODIFIED bp.aa@1") == null
        && AlertPolicy.forLogLine("ORDER_FILLED bp.aa@1") == null && AlertPolicy.forLogLine("LEG_ENDED_BENIGN leg=stop") == null
        && AlertPolicy.forLogLine("FLATTEN_VERIFIED KILL_SWITCH ok") == null && AlertPolicy.forLogLine(null) == null);
    check("NON_SIM_ACCOUNT_EVENT and FLATTEN_NOT_CONFIRMED are ALERTs",
        AlertPolicy.forLogLine("NON_SIM_ACCOUNT_EVENT fill on account 'x'").severity().equals("ALERT")
            && AlertPolicy.forLogLine("FLATTEN_NOT_CONFIRMED KILL_SWITCH").severity().equals("ALERT"));

    AtomicLong now = new AtomicLong(1_000_000L);
    Cap cap = new Cap();
    AlertDispatcher d = new AlertDispatcher(cap, now::get, 60_000L);
    d.onLogLine("ORDER_FILLED x");
    check("a non-alert line sends nothing", cap.sent.isEmpty());
    d.onLogLine("ARM_STILL_DENIED -- a safety rule ...");
    check("an alert line is sent with severity, key and the text",
        cap.sent.size() == 1 && cap.sent.get(0).startsWith("ALERT|ARM_STILL_DENIED|ARM_STILL_DENIED --"));
    now.addAndGet(1_000);
    d.onLogLine("ARM_STILL_DENIED -- again");
    d.onLogLine("ARM_STILL_DENIED -- and again");
    check("the same key within the throttle window is suppressed", cap.sent.size() == 1);
    d.onLogLine("REFUSE_TO_ARM something else");
    check("a different key is not throttled by it", cap.sent.size() == 2);
    now.addAndGet(60_000);
    d.onLogLine("ARM_STILL_DENIED -- later");
    check("after the window it sends again and says how many were held back",
        cap.sent.size() == 3 && cap.sent.get(2).endsWith("(+2 similar suppressed)"));
    d.record("ALERT", "FEED_STALE", "no market data for 21s");
    check("pipeline events go through the same path", cap.sent.get(3).equals("ALERT|FEED_STALE|no market data for 21s"));

    cap.explode = true;
    now.addAndGet(120_000);
    boolean threw = false;
    try {
      d.onLogLine("KILL_SWITCH_TRIGGERED x");
    } catch (RuntimeException e) {
      threw = true;
    }
    check("a failing channel never throws into the caller", !threw);

    Path f = Files.createTempFile("flow_v2_alerts", ".log");
    f.toFile().deleteOnExit();
    Files.deleteIfExists(f);
    LogFileAlertSink fs = new LogFileAlertSink(f, ZoneId.of("UTC"));
    fs.send("ALERT", "FEED_STALE", "no market data\nfor 21s", 1_790_617_398_000L);
    fs.send("INFO", "DESTROY", "instance=1", 1_790_617_399_000L);
    List<String> lines = Files.readAllLines(f);
    check("the placeholder channel appends one line per alert", lines.size() == 2);
    check("...timestamped, with severity and key, newline flattened",
        lines.get(0).matches("2026-09-28T17:43:18\\+00:00 \\[ALERT\\] FEED_STALE: no market data for 21s")
            && lines.get(1).contains("[INFO] DESTROY: instance=1"));

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all alert-path checks passed.");
  }
}
