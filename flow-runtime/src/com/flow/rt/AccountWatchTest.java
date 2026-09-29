package com.flow.rt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The account-position watch's "did one of our fills cause this?" judgement, with a scripted clock. Includes the race
 * seen live 2026-09-28 23:13:01: the watcher reads the new position a few ms BEFORE the fill callback runs.
 */
public final class AccountWatchTest {
  private static int failures = 0;

  private static void check(String label, boolean ok) {
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label);
    } else {
      System.out.println("OK   " + label);
    }
  }

  private static final class Rig {
    final AtomicLong now = new AtomicLong(1_000_000L);
    final List<String> journal = new ArrayList<>();
    final List<String> alerts = new ArrayList<>();
    final List<Runnable> later = new ArrayList<>();
    final List<Long> laterDelays = new ArrayList<>();
    final AccountWatch w = new AccountWatch(new AccountWatch.Sink() {
      @Override public void journal(String j) { journal.add(j); }
      @Override public void alert(String m) { alerts.add(m); }
      @Override public void later(Runnable t, long d) { later.add(t); laterDelays.add(d); }
    }, now::get);

    void runLater() { List<Runnable> r = new ArrayList<>(later); later.clear(); r.forEach(Runnable::run); }
  }

  public static void main(String[] args) {
    // 1. the normal order: callback first, then the watcher sees the change
    Rig a = new Rig();
    a.w.baseline(0, 0);
    a.w.onFillCallback();
    a.now.addAndGet(400);
    a.w.observe(1, 1, 93550.0, 113);
    check("callback first: journaled at once as explained", a.journal.size() == 1 && a.journal.get(0).contains("\"explainedByFill\":true"));
    check("...no alert, nothing deferred", a.alerts.isEmpty() && a.later.isEmpty());
    check("...with the cash and entry", a.journal.get(0).contains("\"cashBalance\":93550.0") && a.journal.get(0).contains("\"avgEntryTicks\":113"));

    // 2. the live race: the watcher sees the change BEFORE the callback runs
    Rig r = new Rig();
    r.w.baseline(0, 0);
    r.w.observe(1, 1, 93550.0, 113);              // change seen first ...
    check("change seen with no callback yet: NOT journaled or alerted immediately", r.journal.isEmpty() && r.alerts.isEmpty());
    check("...it is re-judged after the grace period", r.later.size() == 1 && r.laterDelays.get(0) == AccountWatch.EXPLAIN_GRACE_MS);
    r.now.addAndGet(5);
    r.w.onFillCallback();                          // ... the fill callback arrives 5 ms later
    r.now.addAndGet(AccountWatch.EXPLAIN_GRACE_MS);
    r.runLater();
    check("the late callback explains it: journaled as explained, NO alert (the 23:13:01 false positive)",
        r.journal.size() == 1 && r.journal.get(0).contains("\"explainedByFill\":true") && r.alerts.isEmpty());

    // 3. a genuine manual close: no callback ever
    Rig m = new Rig();
    m.w.baseline(1, 1);
    m.now.addAndGet(60_000);
    m.w.observe(0, 1, 93400.0, null);
    m.now.addAndGet(AccountWatch.EXPLAIN_GRACE_MS);
    m.runLater();
    check("no callback within the grace period: journaled as NOT explained", m.journal.size() == 1 && m.journal.get(0).contains("\"explainedByFill\":false"));
    check("...and ALERTed with the positions", m.alerts.size() == 1 && m.alerts.get(0).startsWith("ACCOUNT_POSITION_CHANGED_UNTRACKED 1 -> 0 (study position 1)"));
    check("...its record time is the moment the change was SEEN, not the re-judgement", m.journal.get(0).contains("\"t\":1060000"));

    // 4. an old callback (long before) does not explain a new change
    Rig o = new Rig();
    o.w.baseline(0, 0);
    o.w.onFillCallback();
    o.now.addAndGet(20_000);
    o.w.observe(-1, -1, null, null);
    o.now.addAndGet(AccountWatch.EXPLAIN_GRACE_MS);
    o.runLater();
    check("a callback 20 s earlier does not explain it", o.alerts.size() == 1 && o.journal.get(0).contains("\"explainedByFill\":false"));

    // 5. no change, no baseline, study-only change
    Rig q = new Rig();
    q.w.observe(0, 0, null, null);
    check("no baseline yet: the first reading is just the baseline, nothing journaled", q.journal.isEmpty());
    q.w.observe(0, 0, null, null);
    check("an unchanged reading is silent", q.journal.isEmpty() && q.later.isEmpty());
    q.now.addAndGet(60_000);
    q.w.observe(0, 1, null, null);             // only the STUDY's own view moved (lagging read catching up)
    q.now.addAndGet(AccountWatch.EXPLAIN_GRACE_MS);
    q.runLater();
    check("a study-only change is journaled but never ALERTed (the account did not move)", q.journal.size() == 1 && q.alerts.isEmpty());

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all AccountWatch checks passed.");
  }
}
