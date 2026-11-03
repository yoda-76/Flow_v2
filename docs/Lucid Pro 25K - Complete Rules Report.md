# Lucid Pro 25K - Complete Rules Report

Nov 2, 2026 · @yoda

## At a glance

Lucid Pro 25K is a one-time-fee futures evaluation: make $1,250 without losing $1,000 on an end-of-day trailing drawdown, then trade a simulated funded account with 90/10 payouts. Figures are from Lucid's live pricing card and help center on 2 Nov 2026; items marked (derived) are my own arithmetic, not Lucid's wording.

| Item | 25K Pro |
| --- | --- |
| Evaluation fee | $108 list; $75.60 with the checkout coupon (daily loss limit ON). With the limit OFF the card shows $90.30 with coupon |
| Reset fee | $75 (limit ON) or $90 (limit OFF) |
| Profit target | $1,250 |
| Max loss limit (MLL) | $1,000, end-of-day trailing; locks at $25,100 |
| Daily loss limit (DLL) | Optional, $600, soft breach; fixed at purchase |
| Max size | 2 minis or 20 micros |
| Time limit / minimum days | None stated; can pass in one day |
| Funded activation fee | Free |
| Payout goal per cycle | $250 profit and balance above the buffer |
| Consistency | 40%: largest day divided by cycle profit |
| Payout size | $500 minimum; $1,000 max for payout 1; $1,500 max for payouts 2+ |
| Profit split | 90% trader, 10% Lucid |
| Payouts before live review | 5 |

## Evaluation phase rules

The evaluation has one target ($1,250) and one hard stop ($1,000 MLL). The help center states no minimum days, time limit or consistency rule for the evaluation.

| Rule | Detail |
| --- | --- |
| Profit target | $1,250 |
| Drawdown type | End-of-day (EOD). At each session close the system takes the highest closing balance and sets the MLL $1,000 below it |
| Starting MLL | $24,000 balance level (derived: $25,000 minus $1,000) |
| Lock | MLL stops trailing once the account closes at or above $26,100 and then sits at $25,100 for good |
| Breach | Balance reaching the MLL breaches the account; a reset can be bought for $75 (or $90) |
| Daily loss limit | Optional $600 soft breach: you cannot place new trades until the next session, but the account survives unless MLL is also hit |
| Max size | 2 minis or 20 micros at all times |
| Time limit | None: you can take as long as you need |
| Minimum days | None stated; Lucid advertises passing in one day |
| Inactivity | A period of 30 calendar days with no trading that moves P&L by at least $1 gets the account deleted |
| Breached accounts | Deleted automatically after 30 days if not reset |
| Upgrade | Funded account opens 5 to 30 minutes after the target is hit; no activation fee |
| Dashboard lag | Objectives update 5 to 30 minutes after your last closed trade |
| Resets | Never free or automatic; they restore the original balance and limits |

The DLL is chosen when you buy and cannot be changed on an active account. Turning it OFF costs more and removes On Demand Payouts (see the funded section).

## Funded account and payout rules

A payout needs three things at once: at least $250 profit this cycle, no single day above 40% of that profit, and a balance above the $26,100 buffer. All three reset after every approved payout.

| Payout | Buffer balance | Min balance for $500 payout | Max payout | Min balance for max payout |
| --- | --- | --- | --- | --- |
| 1 | $26,100 | $26,600 | $1,000 | $27,100 |
| 2 and later | $26,100 | $26,600 | $1,500 | $27,600 |

| Rule | Detail |
| --- | --- |
| Drawdown | Same EOD $1,000 MLL; locked at $25,100 once the account has closed at $26,100 |
| Consistency | Largest single-day profit divided by cycle profit must be 40% or less. Accounts bought or reset before 28 Nov 2025, 3:00 PM EST keep 35% (Lucid calls its own wording ambiguous, so confirm) |
| Minimum payout | $500, for every account size |
| Split | 90% to you, 10% to Lucid. Accounts bought or reset before 28 Nov 2025, 3:00 PM EST keep 100% of the first $10,000 |
| Request timing | No fixed window; request on any day you qualify |
| Processing | Amount leaves the account within minutes of approval; money reaches you within 2 business days |
| Requests are final | Cannot be edited or cancelled once submitted |
| Trading after a request | If you trade before approval and the balance falls into the buffer, the request may be denied; Lucid says to trade as if the payout were already removed |
| DLL below $26,100 | $600 soft limit if you chose DLL ON (the Funded Rules panel shows NONE; see hidden rules) |
| DLL above $26,100 | LucidScale DLL replaces it: 60% of your highest end-of-day profit; it only ever rises |
| On Demand Payouts | DLL ON only. Request mid-session from that session's profit; funded trading then pauses until the end of the trading day |
| Days to payout | Funded Rules panel shows 3 |
| Scaling plan | None on Pro funded: full 2 minis or 20 micros from day one |
| Payouts to live | 5; after payout 5 you enter Lucid's live review pool |
| Payout methods | WorkMarket (bank or PayPal, international), crypto (BTC, ETH, LTC, USDT, USDC, international), Plaid (US only) |

Derived: a $500 payout needs $1,600 total profit ($26,600 balance). With the 40% cap, the best day can be $640, so you need at least three profitable days. That matches the "3 days" on the panel.

## Trading conduct rules

Pro allows news trading, automation and scaling in, but bans microscalping, HFT and any cross-account hedging; the Terms add broader catch-all clauses that Lucid applies at its sole discretion.

| Topic | Rule |
| --- | --- |
| Hours | Reopens 6:00 PM ET Sun to Thu. Flatten by 4:45 PM ET Mon to Fri; Lucid auto-closes anything still open. Holding past 4:45 does not fail the account. On early-close holidays you must be flat before the market closes |
| Time zone | Lucid writes "EST" and does not say if it shifts for daylight time. 4:45 PM EST is 3:15 AM IST (2:15 AM IST in US summer), derived |
| Instruments | CME Group futures only: equity index (ES, NQ, RTY, YM, NKD plus micros), currencies, energy, metals (GC, MGC, SI, SIL, HG, PL), grains and livestock |
| Commission per side | Micros $0.50 (MGC $0.80, SIL $1.60); index minis $1.75; currency futures $2.40; CL $2.00; GC, SI, HG, PL $2.30; grains and livestock $2.80. Currency not stated |
| News | Allowed on Pro. Slippage and platform "velocity logic" during news are your risk |
| Scaling in / DCA | Allowed; martingale is strongly discouraged |
| Flipping | Allowed |
| Microscalping | Flagged when more than 50% of profits come from trades held 5 seconds or less. Review, then written warning, then forfeiture of those profits and possible permanent ban |
| High-frequency trading | Banned. No numeric threshold; automated detection. First offense warning; repeat offense removes HFT profits, closes accounts and bans you |
| Hedging | Long and short in the same contract is banned, in one account or across accounts. Mini plus micro of the same contract is allowed within one account only. Different contracts in one account are fine. Opposing correlated positions across accounts (long ES in one, short NQ in another) are banned, including across users and firms |
| Hedging penalty | Email notice and account reset to the prior day's balance, with no drawdown restored. Repeat: all involved accounts breached and a ban |
| Automation and copiers | Allowed. You are fully responsible for software errors and malfunctions |
| Platforms | CQG feed: NinjaTrader, Tradovate, TradingView. Rithmic feed: Quantower, Tradesea, MotiveWave, Sierra Chart, Jigsaw, Bookmap, ATAS, R\|Trader Pro, MultiCharts |
| Account caps | 10 evaluation plus funded combined, 5 funded, 5 live, per household or family |
| Profiles | One profile per trader, personal or business, permanent once created |
| Restricted countries | 82 countries and regions are blocked; India was not on the list when I read it |

## Hidden and easy-to-miss rules

The biggest risks are conflicting pages on the daily loss limit, broad "sole discretion" clauses in the Terms, and what happens when you go live. Nothing here is a reason to avoid Lucid; it is what to plan around.

| Rule | What Lucid says | Why it matters |
| --- | --- | --- |
| DLL pages disagree | Evaluation and DLL articles list the 25K DLL as "None"; the pricing card and Customization article offer $600 ON/OFF. The Funded Rules panel (with DLL OFF selected) still shows LucidScale DLL above $26,100, while the Customization article says OFF means no DLL in either phase | Confirm with support exactly what applies to the 25K, ON and OFF, before buying |
| LucidScale DLL formula | DLL article: 60% of highest end-of-day profit. Funded article: "60% of Peak EOD Balance" | Profit-based is the only sensible reading; at $1,100 peak profit the cap is $660 (derived) |
| DLL is not a separate loss budget | Hitting it only locks you out until the next session | It protects MLL but costs you the rest of the day |
| Drawdown trigger basis | "Balance reaches the MLL" is stated; whether open P&L counts intraday is not | Assume open losses count and size accordingly |
| Dashboard lag | Objectives update 5 to 30 minutes after your last closed trade | Your own P&L tracker is the source of truth for MLL and the consistency ratio |
| Cycle-based consistency | Largest day versus profit since the last payout, reset each time | One big day stalls the payout until later days dilute it |
| Payout buffer | Payout cannot touch the $26,100 buffer; balance after payout must stay above it | After a payout you sit about $1,000 above the locked MLL at $25,100 |
| Request is final | No edit or cancel; trading after a request can get it denied | Stop trading once a request is submitted |
| On Demand Payout pause | Intraday payout pauses funded trading until the end of the trading day. Customization article says the session runs 6:00 PM to 3:45 PM ET, but flatten is 4:45 PM ET | Pull the cord only when you are done; ask support which cutoff is right |
| Payout count | Pricing card and New Live article: 5. Legacy Pro Live article (accounts bought or reset by 27 Feb 2026): 6 | New accounts fall under 5; confirm |
| "No simulated payout caps" | Funded article says this, yet payouts are capped at $1,000 then $1,500 | Treat the per-payout cap as binding |
| Going live closes sim accounts | All eligible funded accounts (at least one payout each) move live together; simulated accounts close; live starts at $0 | Do not keep evaluations in reserve if you need them after going live |
| Live review is discretionary | Risk team decides; cashout option is 50% of the live starting balance with a 24-hour window or you are moved automatically | Plan for a possible forced move after payout 5 |
| Household rule | If one household member trades live, others may not trade sim accounts | Matters if more than one of us trades from the same home |
| Live rules | $0 start, $1,000 EOD drawdown, no DLL, no consistency; $2,000 live profit over 20 trading days earns a $1,000 live bonus; a blown live account means a 2-week cooldown, longer for reckless trading | Different game from the sim account |
| Data and fills | Terms ban trades outside the best bid/offer, trades on an external or slow data feed, and strategies exploiting simulated prices or delays | Use the supported Rithmic or CQG feed; avoid anything that depends on sim-only fills |
| Edge from the simulation | Terms ban activity that circumvents targets or earns profits not achievable in live markets | Lucid may remove trades, not count the profit, or cancel access without refund |
| Pooling and sharing | Terms ban trading for others, combining performance between users, and unconnected accounts running the same or opposite strategy to pool or hedge risk | Do not share one signal across different people's accounts without asking support |
| Location | Terms say the site is for persons in the US, ban circumventing geographic restrictions, and W-8BEN holders warrant all activity happens outside the US | VPNs or a US-hosted VPS could conflict; confirm before running bots from one |
| Technical failures | No compensation for feed outages, copier or bot errors, disconnects or news slippage | Build your own kill switch; losses from errors stay with you |
| Refunds | None once an account has traded; chargebacks lead to a permanent ban and legal recovery | The fee is sunk after your first trade |
| Rules can change | Lucid can revise terms effective immediately; the Terms page I read was last modified 20 March 2025, older than the help center | Re-check rules before each purchase |
| Promo codes | Terms make codes single-use and non-shareable; the site banner advertises a code | Use only the coupon applied at checkout |
| Inactivity | 30 calendar days without a trade that moves P&L by at least $1 gets an evaluation deleted | Place a small trade if you pause |

## What this means for the strategy

The rules reward small, even, repeatable days rather than one big winner. All maths below is derived from Lucid's figures, not quoted from Lucid.

1. **Size to the $1,000 MLL.** At the 2-mini cap, standard contract values give: 2 ES lose $1,000 in 10 points, 2 NQ in 25 points, 2 GC in 5 points. Commissions per side add to that. Micros let you size well below the cap.
2. **Cap your best day.** For the first $500 payout you need $1,600 total profit with no day above $640. For the first $1,000 payout you need $2,100 profit with no day above $840. For a $1,500 payout from a $26,100 balance you need $1,500 cycle profit with no day above $600. A daily stop-for-the-day near $400 to $500 keeps you inside all three.
3. **Expect at least three winning days per payout.** Two days can cover at most 80% of cycle profit under the 40% rule, so two-day payouts are impossible.
4. **Treat the post-payout balance as fragile.** The MLL stays locked at $25,100, so right after a payout you have about $1,000 of room. Cut size until you rebuild a cushion.
5. **Ceiling on payouts.** Five payouts at the maximum would be $7,000 gross ($1,000 plus four of $1,500) and $6,300 after the 90/10 split, before live review.
6. **Pick the DLL on purpose.** DLL ON is cheaper, enables On Demand Payouts and fits a strategy that already stops at about $600 a day. DLL OFF suits a strategy that needs more room on a single day, at roughly $14.70 more per account with coupon. It cannot be changed later.
7. **Automation guardrails.** Hold winners longer than 5 seconds, keep order rates modest, add a kill switch at about 80% of MLL, and flatten before 4:45 PM ET yourself. Never run opposing or correlated positions across your own accounts; copying the same direction is allowed.
8. **Track your own numbers.** The dashboard can lag by up to 30 minutes, so compute MLL, consistency ratio and buffer yourself.
9. **Mind the clock.** 4:45 PM ET is 3:15 AM IST in winter. Plan sessions and bot shutdowns around it.

## Open questions and sources

Ask Lucid support (chat icon on the site) to confirm these in writing before buying:

- What DLL applies to the 25K, ON and OFF, in evaluation and in funded, including LucidScale when OFF?
- Does the funded account start fresh at $25,000 with a $1,000 MLL, or carry over evaluation profit?
- Is the MLL breach measured on live equity including open P&L?
- Does "Days to payout: 3" mean three profitable days, and what counts as a day?
- Is the daily session cutoff 3:45 PM or 4:45 PM ET, and does "EST" follow daylight time?
- Are payouts capped at 5 for accounts bought now, and what happens after payout 5?
- Is a US-hosted VPS or VPN acceptable for a non-US trader running automation?
- Can several people trade the same strategy on separate Lucid accounts?

I could not read the last \~8,000 characters of the Terms of Use (governing law, arbitration, indemnification). The Risk Disclosure and Privacy pages were not read. Each help article was read through a summarizer, so quote critical numbers from the live pages before relying on them.

Sources, all opened 2 Nov 2026:

- [Lucid Trading pricing page and Funded Rules panel](https://lucidtrading.com/)
- [LucidPro Evaluation Account](https://support.lucidtrading.com/en/articles/12890029-lucidpro-evaluation-account)
- [LucidPro Funded Account](https://support.lucidtrading.com/en/articles/12890069-lucidpro-funded-account)
- [LucidPro Payouts](https://support.lucidtrading.com/en/articles/12890092-lucidpro-payouts)
- [LucidPro Consistency Percentage](https://support.lucidtrading.com/en/articles/12890109-lucidpro-consistency-percentage)
- [LucidPro Daily Loss Limit](https://support.lucidtrading.com/en/articles/12890122-lucidpro-daily-loss-limit)
- [LucidPro Drawdown](https://support.lucidtrading.com/en/articles/12890136-lucidpro-drawdown)
- [LucidPro Customization](https://support.lucidtrading.com/en/articles/16226068-lucidpro-customization)
- [LucidPro Live (Legacy)](https://support.lucidtrading.com/en/articles/13432107-lucidpro-live-legacy)
- [New Live Structure](https://support.lucidtrading.com/en/articles/16558826-new-live-structure) and [Live Scaling Plan](https://support.lucidtrading.com/en/articles/15245873-live-scaling-plan)
- [Allowed Trading Times](https://support.lucidtrading.com/en/articles/11404729-allowed-trading-times)
- [Other Trading Activities](https://support.lucidtrading.com/en/articles/11404728-other-trading-activities)
- [Prohibited: Microscalping](https://support.lucidtrading.com/en/articles/11404742-prohibited-microscalping), [Hedging](https://support.lucidtrading.com/en/articles/11404734-prohibited-hedging), [High Frequency Trading](https://support.lucidtrading.com/en/articles/11404736-prohibited-high-frequency-trading)
- [Trade with Integrity](https://support.lucidtrading.com/en/articles/11404732-trade-with-integrity) and [Inactivity Policy](https://support.lucidtrading.com/en/articles/11404632-inactivity-policy)
- [Restricted Countries](https://support.lucidtrading.com/en/articles/11404636-restricted-countries)
- [Maximum Number of Accounts](https://support.lucidtrading.com/en/articles/11404617-maximum-number-of-accounts), [Approved Products and Commissions](https://support.lucidtrading.com/en/articles/11508978-approved-products-and-commissions), [Simulated Account Fees](https://support.lucidtrading.com/en/articles/11404620-simulated-account-fees)
- [Payout Methods](https://support.lucidtrading.com/en/articles/12890325-payout-methods), [Supported Platforms](https://support.lucidtrading.com/en/articles/11404614-lucid-trading-supported-platforms), [Registering as a Business](https://support.lucidtrading.com/en/articles/11404634-registering-as-a-business)
- [Terms of Use](https://lucidtrading.com/terms-of-use/) (last modified 20 March 2025) and [Refund and Liability Policy](https://lucidtrading.com/disclaimer-of-liability-refunds-and-chargeback-policy/)
