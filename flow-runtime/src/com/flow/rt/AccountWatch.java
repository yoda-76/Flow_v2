package com.flow.rt;

import com.flow.journal.Json;

import java.util.function.LongSupplier;

/**
 * F-3 (D-113), extracted 2026-09-28 so its judgement can be unit-tested: watches the ACCOUNT's position for changes
 * and journals each as `account_position_change`, flagging whether a fill callback of this study explains it.
 *
 * Why the judgement is deferred: the watcher (about once a second, on the drain thread) can read the new account
 * position a few milliseconds BEFORE the order-callback thread has run the fill callback that would mark it explained
 * (seen live 2026-09-28 23:13:01: a genuine entry fill was ALERTed as "not from an order this study placed"). A change
 * that no recent callback explains is therefore judged again EXPLAIN_GRACE_MS later, and only a change that is still
 * unexplained after that is journaled as such and ALERTed. Read-only: it never places anything.
 */
final class AccountWatch {
  /** A fill callback up to this long BEFORE a change (or, after the grace period, any time since) explains it. */
  static final long EXPLAIN_WINDOW_MS = 3_000L;
  /** How long an unexplained change waits for its fill callback before it is called untracked. */
  static final long EXPLAIN_GRACE_MS = 2_500L;

  interface Sink {
    void journal(String jsonLine);
    void alert(String message);
    /** Run later, off the calling thread. */
    void later(Runnable task, long delayMs);
  }

  private final Sink sink;
  private final LongSupplier nowMs;

  private volatile int lastAccount = Integer.MIN_VALUE;
  private volatile long lastPairKey = Long.MIN_VALUE;
  private volatile long lastFillCallbackMs = 0;

  AccountWatch(Sink sink, LongSupplier nowMs) {
    this.sink = sink;
    this.nowMs = nowMs;
  }

  /** At (re-)activation: the position now is the baseline; only changes after it are records. */
  void baseline(int accountPosition, int studyPosition) {
    lastAccount = accountPosition;
    lastPairKey = key(accountPosition, studyPosition);
  }

  /** A fill callback of this study just ran. */
  void onFillCallback() {
    lastFillCallbackMs = nowMs.getAsLong();
  }

  private static long key(int account, int study) {
    return (long) account * 1_000_003L + study;
  }

  /** Called about once a second with the current readings. cash/entryTicks may be null. */
  void observe(int account, int study, Double cash, Integer entryTicks) {
    long pairKey = key(account, study);
    if (account == lastAccount && pairKey == lastPairKey) return;
    boolean first = lastAccount == Integer.MIN_VALUE;
    int previous = lastAccount;
    lastAccount = account;
    lastPairKey = pairKey;
    if (first) return; // no baseline was taken: nothing to compare with
    long changeMs = nowMs.getAsLong();
    if (changeMs - lastFillCallbackMs < EXPLAIN_WINDOW_MS) {
      emit(changeMs, previous, account, study, cash, entryTicks, true);
    } else {
      // Maybe the callback is only a few milliseconds behind: judge again after the grace period.
      sink.later(() -> {
        boolean explained = lastFillCallbackMs >= changeMs - EXPLAIN_WINDOW_MS;
        emit(changeMs, previous, account, study, cash, entryTicks, explained);
      }, EXPLAIN_GRACE_MS);
    }
  }

  private void emit(long changeMs, int previous, int account, int study, Double cash, Integer entryTicks, boolean explained) {
    sink.journal(Json.object()
        .field("type", "account_position_change")
        .field("t", changeMs)
        .field("previousAccountPosition", previous)
        .field("accountPosition", account)
        .field("studyPosition", study)
        .field("explainedByFill", explained)
        .fieldOrNull("cashBalance", cash)
        .fieldOrNull("avgEntryTicks", entryTicks) // F-1 evidence: the entry the daily-loss check marks against
        .build());
    if (!explained && previous != account) {
      sink.alert("ACCOUNT_POSITION_CHANGED_UNTRACKED " + previous + " -> " + account + " (study position " + study
          + ") -- not from an order this study placed (manual trade / platform close)");
    }
  }
}
