# FLOW

**An automated trading assistant that watches the gold futures market the way an order-flow trader does, and takes
trades on a practice account when the conditions it was taught show up.**

This page explains what FLOW is, what it looks at, how it decides to trade, how it protects the account, and what you
get to review afterwards. It is written for traders. The engineering detail is in the
`docs/` folder (links at the bottom).

---

## The idea in one paragraph

A good discretionary trader reads the market in layers: *where is the trend, where are the important levels, and when
price gets to one of those levels, what are the buyers and sellers actually doing right now?* FLOW tries to turn that
read into written-down rules a computer can follow. It watches the market every moment of the trading day, applies the
rules the same way every time, takes the trade when they line up, and writes down everything it saw and did, so you can
check afterwards whether it traded the way you would have.

The point is not one magic strategy. The point is to **test many trading ideas quickly and fairly**: each idea gets the
same market data, the same order handling, the same risk limits and the same record keeping. When two ideas get
different results, the difference comes from the idea itself, not from one of them being run differently.

---

## Where it runs

| | |
|---|---|
| **Market** | Gold futures (COMEX `GC`) only, for now. It refuses to trade any other chart. |
| **Charting platform** | MotiveWave. FLOW runs inside it, attached to a gold chart. |
| **Market data** | Rithmic: every trade, who was the aggressor (buyer or seller lifting/hitting), and the full order book with individual resting orders. |
| **Account** | **The Simulated (practice) account only.** Real-money trading is switched off, both in MotiveWave's settings and inside FLOW itself. |
| **Machine** | A rented cloud computer, so it can run around the clock without your own PC being on. |

---

## What FLOW watches

FLOW keeps a live picture of the market made of the same things you would have on your own screens. Each one is
updated with every trade, not just when a candle closes.

**Market structure (the trend and the zones).**
FLOW follows the trend candle by candle: is the market making an uptrend or a downtrend, has a pullback formed, and
where are the key zones (the most recent swing zones, the level that would flip the trend if broken, the retest zone
after a flip). This tells it *where* it should be interested in trading and in *which direction*.

**Volume profile.**
How much volume traded at each price: the point of control (the busiest price), the value area (where most of the
volume traded), and the high-volume and low-volume nodes. Low-volume nodes (thin areas price moved through quickly)
are especially important to the current strategies, because price tends to react sharply when it returns to them.

**Footprint and delta.**
Inside each candle, how much was bought aggressively versus sold aggressively at every price, and the running
difference between the two (delta). This shows **absorption**: heavy aggressive selling into a level that refuses to
go lower, or the opposite.

**Big trades.**
Unusually large single trades, the "bubbles" on an order-flow chart. A burst of big aggressive orders in one direction
is a sign of **aggression** from serious participants.

**Liquidity map (the order book).**
Where resting buy and sell orders are sitting above and below price, and how big they are: walls, thin spots, and
orders being pulled. This lets FLOW spot a **sweep**, where price runs through a pocket of resting orders and snaps
back.

**VWAP and repeated orders.**
The volume-weighted average price for the session, and the same order size being placed over and over at a price
(often one larger player working an order). These are noted as background context at the moment.

**Hand-entered levels.**
Anything you want the system to know that does not come from the live data, such as key levels you marked, gamma
levels from somewhere else, or your bias for the day, can be typed into a file, and the strategies can use them the
same way they use the live readings.

---

## How a trade happens

Most trading ideas here have the same two-step shape:

1. **An area of interest.** A place on the chart worth trading from, taken from market structure, the volume profile
   or a level you supplied. *Where* and *which way*.
2. **An order-flow trigger inside it.** When price actually arrives there, FLOW waits for the tape and the book to
   confirm, through absorption, aggression or a sweep. *When*.

A worked example, using the main strategy built so far:

> The market is in an uptrend. FLOW marks the current pullback zone, the zone that, if broken, would flip the trend
> to down. Inside that zone it finds the largest low-volume node. It does nothing until price pulls back and touches
> that node. Then it looks for confirmation: sellers being absorbed in the footprint, a burst of big buy orders, or a
> sweep of the resting orders below that snaps back. If any one of those shows up, it buys **1 contract**. The stop
> goes just beyond the far edge of the zone, the same place where the uptrend would be proven wrong. The target is
> twice the distance of the stop (2-to-1 reward to risk). Stop and target are placed with the entry, and FLOW manages
> them until one of them fills.

FLOW never "chases". If price never reaches the zone, or the zone is replaced by a newer one, the idea is dropped
without a trade. If no low-volume node sits inside the zone, there is no trade at all.

### The strategies that exist today

You choose which strategy runs from a drop-down in the study settings. Only one runs at a time.

| Strategy | What it does | Purpose |
|---|---|---|
| **Market structure + LVN reversal** | The worked example above. | The first real strategy, built to prove every piece of the system works together. The rules are placeholders and are not expected to be profitable yet. |
| **LVN fade (test)** | Fades every touch of a low-volume node with a tight, fixed stop and target. Trades often. | A stress test of the order handling (entries, stops, targets, exits), not a trading idea. |
| **Level observer** | Watches levels and records what happens around them. Never trades. | Collects evidence. |
| **Do nothing** | Never trades. | Checks that the system starts up and runs cleanly. |

**Honest status:** none of these is a proven edge. The real order-flow rules (exactly what counts as absorption, which
low-volume node to pick, when a setup expires, where stops and targets belong, and so on) are being written down and
reviewed question by question, based on an order-flow course and your own trading. New strategies will be built from
that review once it is finished.

Adding a new strategy only touches that strategy. The market reading, order handling, risk limits and record keeping
are shared and stay the same, which is what keeps comparisons fair.

---

## Three stages, each one earned

| Stage | What happens | What you learn |
|---|---|---|
| **1. Watch only** (the default) | FLOW reads the market and decides what it *would* do, but sends no orders. | Does it see the setups you would see? Is it firing in the right places? Most bad ideas and most mistakes show up here, at no cost. |
| **2. Practice account** | The same decisions, but real orders go to the Simulated account. | Fills, slippage, rejected orders and partial fills become real, and you get a profit-and-loss record. |
| **3. Real money** | **Not allowed.** | Would only happen after a strategy has earned it in stage 2 *and* you make a separate, deliberate, written decision. Nothing in the system moves to this stage by itself. |

To trade on the practice account, two switches in the study settings must both be on: **Mode** set to the practice
setting, and **Armed** ticked. If Armed is unticked, nothing can be ordered, whatever else is set.

---

## How it protects the account

Every trade idea passes through a row of checks before an order can go out. Each check writes down its verdict, so a
trade that was blocked shows up in the record as "blocked, and why", instead of silently not happening.

**Before any trade**
- **Armed?** If not, nothing is sent.
- **Is the market open, and is there time left?** No new trades in the last 15 minutes before the daily 4:00 pm
  Central halt, and none over the weekend.
- **Is the system ready?** Every reading a strategy depends on must have enough data first. A volume profile with five
  minutes of data in it is not trusted. FLOW will not start trading until each reading it uses says it is ready.
- **Position size.** 1 contract maximum.
- **Daily loss limit.** 200 ticks a day (about $2,000 on one gold contract), counting both closed trades and the open
  one. If it is hit, FLOW closes the position, cancels everything and **stops trading for the rest of the run**.
  Restarting the platform does not reset this. The strategy has to be removed and added again on purpose.
- **Not too fast.** No more than 6 changes of position in any minute.
- **No flip-flopping.** At least 5 seconds in a position before it can turn around, and at most 20 reversals in a
  session.
- **Getting out is never blocked.** None of these checks can stop FLOW from closing a position.

**While running**
- **Flat before the halt.** Any open position is closed from 3:55 pm Central, before the daily halt.
- **Falling behind the market.** If FLOW starts lagging the live data, for example because the market is moving too
  fast for the machine, it stops trading immediately rather than trade on a stale picture. It keeps watching and
  recording, so you can see what happened.
- **Something unexpected goes wrong.** If any part of a strategy hits an error, trading stops and the error is
  recorded with the exact moment it happened.

**On start-up**
- **An open position or working orders already on the account?** FLOW will not trade. It does not adopt a position
  whose reasons it no longer knows, and it does not close it on its own either. You clear it by hand, then start it.

**Account and instrument**
- **Practice account only.** MotiveWave's "Sim Trade Only" setting stays on, which blocks orders to any other account
  for the whole platform. On top of that, if FLOW ever sees an order or a fill on any account other than the practice
  one, it disarms and locks out all ordering.
- **Gold only.** On any other chart it refuses to arm and places nothing.
- **Strategies cannot place orders themselves.** A strategy only says what position it wants. Only one central part of
  FLOW is able to send orders, and every request goes through all of the checks above first. A badly written strategy
  can make a bad decision, but it cannot get around the limits.

All of these limits are kept in one small settings file (`config/risk.json`) that you edit by hand. Every setting, what
it does and when a change takes effect is explained in [docs/configuration.md](docs/configuration.md).

---

## What gets recorded

**The diary.** Every decision, every blocked trade with its reason, every order, every fill, which settings and levels
were in force, and a regular "still alive" heartbeat. This is kept permanently.

**The market tape.** Every market event FLOW received, in order. This means a session can be **replayed** later
through exactly the same rules, to answer "why did it do that?". Replaying the same day twice always gives the same
decisions. This is checked automatically.

**Market data snapshots, the last 7 trading days.** Footprint, VWAP, big trades, the liquidity map (every second),
candles and market-structure changes, kept on a rolling 7 trading days so recent sessions can be studied in detail.

---

## What you look at

| Tool | What it tells you | When |
|---|---|---|
| **Status check** | One line: is it alive, is it trading, what position it holds, its last trade, any alerts, whether the data is healthy. | Whenever you check in. |
| **Nightly report** | The day's trades, what was blocked and why, anything that needs your attention, the settings in force, data health. | Each evening. |
| **Trade close-up** | For any single trade: how far it went for and against you, the order flow before, during and after the entry, the price ladder, and the liquidity that stood between the entry and the target. | When a trade makes you ask "why?". |
| **Phone alerts (Telegram)** | A separate watchdog, outside the trading platform, messages you if FLOW stops, freezes, loses its market data, hits a safety stop, or the machine runs low on disk space, and again when it recovers. | Comes to you. |

Reports and recorded data can be copied from the cloud machine to your Google Drive every day, so you can review them
from any device.

---

## Testing ideas on history

There is also a separate **backtester** for quick screening. It runs a trading idea over about six years of 1-minute
gold candles (mid-2020 to October 2026), with the same risk limits, and reports the results.

It has a hard limit: it only sees candles. **It cannot test order-flow ideas**, because historical order book data is
not available. Absorption, sweeps and the liquidity map can only be tested live, on the practice account. Whether
historical tick data (trades with the aggressor side) can be bought from Rithmic is being looked into. If it can,
everything except the order book could be backtested too.

So the backtester answers one question: *is the chart-pattern part of this idea worth spending live practice time on?*
Order-flow ideas go straight to stages 1 and 2.

---

## Where things stand (October 2026)

- **Built and running:** the full system, running around the clock on the cloud machine, with the safety checks,
  record keeping, reports, phone alerts and the backtester.
- **In progress:** the order-flow rules review, which defines what the real strategies will be; a check on whether
  historical tick data is available; setting up the daily copy to Google Drive.
- **Still open:** how closely the practice account's fills match real fills, and so how much practice profit and loss
  to trust.

---

## What FLOW is not

- **Not a black box.** No machine learning. Every rule is written down, so when a trade loses you can see which rule
  was wrong.
- **Not a portfolio manager.** One instrument, one strategy at a time.
- **Not an options tool.** It does not calculate options or gamma figures. Gamma levels from elsewhere can be typed in
  as hand-entered levels.
- **Not trading real money.** See the three stages above.

---

## For developers and for setting up a machine

- [docs/configuration.md](docs/configuration.md): every setting and how to change it.
- [docs/runbook-ec2.md](docs/runbook-ec2.md) and [docs/runbook.md](docs/runbook.md): setting up and running a machine.
- [docs/backtest-strategy-guide.md](docs/backtest-strategy-guide.md): adding an idea to the backtester.
- `docs/dynamic/`: the working record of decisions, findings and the to-do list.
- [CLAUDE.md](CLAUDE.md): the working rules for the AI assistant that helps build this.

---

*Personal research project, built alongside full-time work. Not affiliated with any employer, uses no proprietary data
or code. Not investment advice.*
