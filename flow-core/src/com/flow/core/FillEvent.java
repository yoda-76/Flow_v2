package com.flow.core;

/**
 * C1 (2026-09-29, D-120): a REAL fill, published through the normal event stream so it reaches the strategy in
 * order with everything else it sees -- the account side of "one position truth" was already fixed (D-113: the risk
 * chain's daily-loss check and every flatten decision read the account, not the strategy's belief); this is the
 * other half, closing the gap F-7 opened (the real bracket is anchored to the fill, but the strategy's own virtual
 * stop/target were still anchored to the signal price it never learns was wrong).
 *
 * Published by flow-runtime's LiveOrderTracker (the only thing that ever sees a real fill) via
 * Sequencer.publish(), same as every other event -- so it is ordered against ticks/bars exactly as it happened, and
 * FlowStrategy.onFill() (previously dead code -- no caller anywhere) finally has one. Pipeline routes it straight
 * to the strategy without treating it as a wake (README: deciding happens in onEvent(), this is "the world changed
 * under you," not a new decision point) and without recording it as a market price (Event.priceOf() deliberately
 * does not recognize FillEvent).
 *
 * Only entry/stop/target fills of the CURRENT bracket are ever published (see LiveOrderTracker.recordFill()) --
 * "untracked" fills (a manual trade, the platform's own close) are not the strategy's own action and are surfaced
 * separately (F-3's account watch), not through this path.
 */
public record FillEvent(
    long seq,
    long eventTimeMs,
    long receiptTimeMs,
    String orderId,
    Role role,
    boolean isBuy,
    int fillPriceTicks,
    int quantity,
    int positionAfter
) implements Event {
  public enum Role { ENTRY, STOP, TARGET, OTHER }
}
