package com.flow.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which runtime log lines are worth telling the operator about, and how urgently. Mirrors the daily report's "Needs
 * attention" list (analysis/daily_report.py ATTENTION) on purpose: the report is the after-the-fact view, this is the
 * live one. Matching is by startsWith(), first hit wins, so a longer prefix must precede any shorter prefix it starts
 * with.
 */
public final class AlertPolicy {
  private AlertPolicy() {}

  public record Match(String severity, String key) {}

  private static final Map<String, String> PREFIXES = new LinkedHashMap<>();

  private static void alert(String prefix) { PREFIXES.put(prefix, "ALERT"); }
  private static void warn(String prefix) { PREFIXES.put(prefix, "WARN"); }
  private static void info(String prefix) { PREFIXES.put(prefix, "INFO"); }

  static {
    // ---- needs a human ----
    alert("KILL_SWITCH_TRIGGERED");
    alert("NON_SIM_ACCOUNT_EVENT");
    alert("POSITION_MISMATCH_DETECTED");
    alert("POSITION_MISMATCH_CORRECTED");
    alert("LIVE_ENTRY_CANCELLED_DISARMING");
    alert("LIVE_ENTRY_REJECTED_DISARMING");
    alert("LIVE_ENTRY_PARTIAL_FLATTENED");
    alert("LIVE_LEG_LOST");
    alert("LEG_LOST_FLATTEN");
    alert("LEG_LOST_CHECK_FAILED");
    alert("POSITION_SOURCES_DISAGREE");
    alert("FLATTEN_NOT_CONFIRMED");
    alert("FLATTEN_CORRECTING");
    alert("FLATTEN_VERIFY_FAILED");
    alert("FEED_RESUME_FLATTEN_HELD");
    alert("FEED_RESUME_FLATTEN");
    alert("FEED_RESUME_UNPROTECTED_POSITION");
    alert("FEED_RESUME_CHECK_FAILED");
    alert("ACCOUNT_POSITION_CHANGED_UNTRACKED");
    alert("REFUSE_TO_ARM");
    alert("ARM_STILL_DENIED");
    alert("ORDER_REJECTED");
    alert("LIVE_BRACKET_SKIPPED");
    alert("ORDER_FILL_RECORD_FAILED");
    alert("RISK_CONFIG_MISSING");
    // ---- worth a look ----
    warn("LIVE_BRACKET_ADJUSTED");
    warn("ACCOUNT_ID_UNKNOWN");
    warn("DOM_BACKLOG_SKIPPED");
    warn("FEED_RESUME_UNCHECKABLE");
    warn("AFTER_FILL_HOOK_FAILED");
    warn("RISK_LOCAL_CONFIG_IGNORED");
    // ---- state changes worth knowing (a stopped study is otherwise silent) ----
    info("SESSION_START");
    info("STUDY_SETTINGS"); // 2026-09-29: VP/FP range, big-trade Min Size/agg period -- so a run's actual settings are on record
    info("DEACTIVATE");
    info("DESTROY");
    info("FEED_RESUMED");
    info("REACTIVATED");
  }

  /** The alert for a runtime log line, or null if it is not one. */
  public static Match forLogLine(String msg) {
    if (msg == null) return null;
    for (Map.Entry<String, String> e : PREFIXES.entrySet()) {
      if (msg.startsWith(e.getKey())) return new Match(e.getValue(), e.getKey());
    }
    return null;
  }
}
