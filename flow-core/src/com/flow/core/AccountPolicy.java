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

  private AccountPolicy() {}

  public static Kind classify(String accountId) {
    if (accountId == null || accountId.isBlank()) return Kind.UNKNOWN;
    return SIMULATED_ACCOUNT_ID.equalsIgnoreCase(accountId.trim()) ? Kind.SIMULATED : Kind.OTHER;
  }
}
