# Additions for Master Order-Flow Rules Review

**Purpose:** Add only the missing questions/sections identified during review of `orderFlowRulesRedefinition.md`.  
**Status:** All additions remain OPEN until explicitly decided.

---

## A. Aggression — add to §3

### Q3.5 — What constitutes meaningful aggression?

The course uses several forms of aggressive activity:
- large aggressive buy/sell prints (big-trade bubbles);
- strong positive/negative delta;
- buy/sell diagonal imbalances;
- stacked imbalances;
- repeated aggressive prints.

**Open:** Which of these are valid inputs to the generic concept of **aggression**, and how should they be measured/combined?

### Q3.6 — How do we determine whether aggression succeeded or failed?

The same aggressive activity can have opposite meanings:
- aggression that produces price progress = successful aggression;
- aggression that fails to produce expected price progress = failed aggression / possible absorption.

**Open:** Define objectively what counts as sufficient price progress or failure after aggression:
- ticks;
- percentage of zone/ATR;
- movement within a time window;
- movement by the end of the bar;
- or another definition.

---

## B. Relationship between failed aggression and absorption — add to §4

### Q4.6 — Is failed aggression the broader concept, with absorption as one manifestation?

The course repeatedly describes absorption as **effort without result**, while several examples describe aggressive orders hitting a location and failing to move price.

**Open:** Decide whether:
1. **Failed aggression** is the broader concept and **absorption** is one measurable manifestation of it; or
2. absorption and failed aggression are separate, independently measurable signals.

This distinction should be settled before implementing either as an entry condition so the system does not accidentally encode the same phenomenon under multiple names.

---

## C. LVN strength/ranking — add to §5

### Q5.8 — How should LVN strength be ranked?

The course describes a deeper/larger LVN as stronger, but does not provide a precise ranking formula.

**Open:** Should LVN strength consider:
- LVN width;
- depth of the volume depression;
- relative volume versus profile average;
- surrounding HVN volume;
- distance/separation between surrounding volume nodes;
- or a combined score?

The preferred direction is a **combined view of width and low-volume characteristics**, but the exact metric and weighting remain open and must be tested.

---

## D. LVN touch and proximity — add to §5

### Q5.9 — What exactly constitutes an LVN touch?

**Open:** Define whether an LVN is considered touched when:
- price enters the LVN range;
- price reaches its boundary;
- price comes within a configurable number of ticks;
- or another condition occurs.

### Q5.10 — Should touch, absorption proximity, and aggression proximity use the same tolerance?

**Open:** Decide whether they share one tolerance or have separate definitions.

**Provisional baseline for testing:** use a shared 2-tick tolerance for all three, then revisit after testing.

---

## E. Trade-idea lifecycle and ownership — add as a new section before entry models

### Trade-idea lifecycle

The underlying strategy creates and owns the trade idea. Order-flow logic monitors and evaluates that idea.

Proposed lifecycle to review:

1. Underlying strategy creates a trade idea.
2. Order-flow monitoring begins.
3. Candidate LVNs are ranked dynamically.
4. Before touch, the selected/highest-ranked LVN may change as rankings update.
5. Touch of the currently selected LVN activates the setup and locks that selected LVN.
6. The system waits for the required confirmation.
7. An order is placed only after confirmation.
8. If the underlying strategy withdraws or invalidates the idea **before order placement**, order-flow monitoring stops immediately.
9. Once an order is placed and the position becomes active, execution and position-management rules own the active trade.

### Q-L1 — What exactly activates the setup?

Is activation specifically the touch of the currently selected LVN, or does another condition need to occur?

### Q-L2 — What exactly constitutes confirmation after activation?

Define the event/evidence that changes an activated setup into an entry-ready setup.

### Q-L3 — At what exact point does ownership transfer?

Confirm that the underlying strategy owns the idea until order placement, and execution/position-management owns the active trade afterward.

---

## F. Activation vs. confirmation vs. execution — add to §8

These states should remain explicitly separate:

**Monitoring**  
→ underlying strategy has created an idea and order-flow is watching.

**Activated**  
→ the selected LVN has been touched/activation condition has occurred.

**Confirmed / entry-ready**  
→ the required order-flow confirmation has occurred.

**Executed**  
→ the order has actually been placed and the position is active.

### Q8.4 — What event changes monitoring into activation?

### Q8.5 — What evidence changes activation into confirmation/entry-ready?

### Q8.6 — What exact event constitutes execution?

This separation prevents “LVN touched”, “confirmation detected”, and “order placed” from being treated as the same event.

---

## G. Sweep — add to §12

### Q12.3 — Is liquidity sweep part of the first version?

**Open:** Include in V1, defer, or drop because it requires L2/depth history?

If included:

### Q12.4 — What objectively constitutes a sweep?

Define:
- required wall/liquidity size;
- required persistence;
- amount of liquidity consumed/removed;
- whether price must trade through the wall;
- whether a subsequent reversal is required;
- whether sweep is location, confirmation, or both.

---

## H. Review-order adjustment

Replace the suggested review order with:

1. **§1 — Bar/data unit**
2. **§2–§3 — Footprint primitives, aggression and big-trade definitions**
3. **§4 — Absorption, exhaustion and delta-flip definitions**
4. **§5 — Location and LVN qualification**
5. **§6 — Entry models**
6. **§7–§8 — Trigger timing and confirmation/lifecycle**
7. **§9–§11 — Stop, target and no-trade filters**
8. **§12 — Optional heatmap/gamma layers**
9. **§13 — Validation**

Reason: entry models depend on the definitions of the underlying reads and locations, so the models should not be finalized before those primitives are settled.
