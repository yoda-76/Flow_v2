package com.flow.core;

/**
 * The Simulated-account-only rule as code (user, 2026-09-28: "only simulated trades will affect anything related to
 * the system"; CLAUDE.md: real account strictly forbidden). Until now this rested on one MotiveWave checkbox ("Sim
 * Trade Only") and a human reading the account selector, because the SDK offers no account object -- but every
 * {@code Order} carries {@code getAccountId()}, and every fill of the live Sim runs (2026-09-28, 400+ fills) reported
 * exactly {@code "simulated"}.
 *
 * SIMULATED: safe to act on. OTHER: an order or fill on any other account -- never acted on; the runtime disarms,
 * sends nothing further and raises an ALERT. UNKNOWN (null/blank): the platform did not say -- allowed (a freshly
 * created, unsubmitted order may not carry an account yet) but logged, because failing closed would halt the system
 * whenever a getter returns nothing; fills have always carried the id, so an UNKNOWN fill is still worth an alert.
 */
public final class AccountPolicy {
  /** The account id MotiveWave gives its built-in simulated account (seen on every Sim fill, 2026-09-28). */
  public static final String SIMULATED_ACCOUNT_ID = "simulated";

  public enum Kind { SIMULATED, OTHER, UNKNOWN }

  /**
   * 2026-11-02 (forwardTest_plan_v1.md): MotiveWave allows several Simulated accounts ("You can have multiple simulated
   * accounts in one workspace" [DOC]), one per forward-tested strategy. Their ids are not known in advance, so the
   * extra ids are listed in config (risk config key {@code simAccountIds}, comma separated) and set here at activation.
   * The built-in {@code simulated} id is always on the list. Exact match after trim + lower case, as before -- a name
   * that merely contains "sim" is still OTHER. "Sim Trade Only" in MotiveWave stays the platform-wide guard: this
   * list only decides which Sim accounts this code will act on, it cannot make a real account tradable.
   */
  private static volatile java.util.Set<String> allowed = java.util.Set.of(SIMULATED_ACCOUNT_ID);

  private AccountPolicy() {}

  /** Replace the extra Sim account ids (null/blank entries ignored); {@code simulated} always stays allowed. */
  public static void configure(java.util.Collection<String> extraSimIds) {
    java.util.Set<String> s = new java.util.TreeSet<>();
    s.add(SIMULATED_ACCOUNT_ID);
    if (extraSimIds != null) {
      for (String id : extraSimIds) if (id != null && !id.isBlank()) s.add(id.trim().toLowerCase(java.util.Locale.ROOT));
    }
    allowed = java.util.Set.copyOf(s);
  }

  /** The ids currently treated as Simulated (for the session journal). */
  public static java.util.Set<String> allowedIds() { return allowed; }

  public static Kind classify(String accountId) {
    if (accountId == null || accountId.isBlank()) return Kind.UNKNOWN;
    return allowed.contains(accountId.trim().toLowerCase(java.util.Locale.ROOT)) ? Kind.SIMULATED : Kind.OTHER;
  }
}
