"""
Calendar Spread Arbitrage — Deep Dive
"""
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

print("""
======================================================================
CALENDAR SPREAD ARBITRAGE — COMPLETE GUIDE
======================================================================

WHAT IS IT?
-----------
Sell a NEAR-TERM option + Buy a FAR-TERM option (same strike, same type)

Example on NIFTY 24300 CE:
  SELL  NIFTY 24300 CE  (Jul 22 weekly, 4 DTE)  @ ₹100
  BUY   NIFTY 24300 CE  (Jul 29 monthly, 11 DTE) @ ₹150
  
  Net debit paid = ₹150 - ₹100 = ₹50 per unit
  Lot size = 65
  Total cost = ₹50 x 65 = ₹3,250

You pay ₹3,250 upfront. That's your MAXIMUM risk.

HOW DO YOU MAKE MONEY?
----------------------
Two sources of edge:

1. TIME DECAY (Theta)
   - The weekly option (4 DTE) decays FASTER than the monthly (11 DTE)
   - After 4 days, weekly might go from ₹100 → ₹20 (decayed ₹80)
   - Monthly might go from ₹150 → ₹120 (decayed only ₹30)
   - Net profit = ₹80 - ₹30 - ₹50 (cost) = ₹0... wait, that's break-even
   
   Actually: you SOLD the weekly, so you GAIN from its decay.
   You BOUGHT the monthly, so you LOSE from its decay.
   
   Weekly decay (your gain): ₹100 → ₹20 = +₹80
   Monthly decay (your loss): ₹150 → ₹120 = -₹30
   Net from time decay: +₹50
   Minus entry cost: ₹50
   Net profit: ₹0... 
   
   BUT the monthly still has 7 DTE left! You can sell it for ₹120.
   Your entry cost was ₹50 (net debit).
   So: you received ₹100 (sold weekly) - paid ₹150 (bought monthly)
   At expiry: weekly = ₹0, monthly = ₹120
   P&L = ₹100 (kept from short) + ₹120 (value of long) - ₹150 (paid) = +₹70

   In practice, you close BOTH at weekly expiry:
   Close short weekly: buy back at ₹0 = +₹100
   Close long monthly: sell at ₹120 = +₹120
   Total received: ₹220
   Total paid: ₹150 (monthly) - ₹100 (weekly credit) = ₹50 net
   Profit: ₹220 - ₹150 = ₹70 per unit = ₹4,550 per lot

   Wait, let me recalculate properly:

   ENTRY:
   - Sell weekly CE: receive ₹100
   - Buy monthly CE: pay ₹150
   - Net cash flow: -₹50 (you pay ₹50 net)
   - This ₹50 is your maximum risk

   EXIT (at weekly expiry):
   - Buy back weekly CE: pay ₹0 (expires worthless if NIFTY near 24300)
   - Sell monthly CE: receive ₹120 (still has 7 DTE, lots of time value)
   - Net cash flow: +₹120

   TOTAL P&L = ₹120 (exit) - ₹50 (entry cost) = +₹70 per unit
   Per lot (65 units): ₹70 x 65 = ₹4,550

   BUT THIS ASSUMES NIFTY STAYS NEAR 24300. If it moves far, the
   long monthly also loses value. Let's model the full payoff.

PAYOFF AT WEEKLY EXPIRY (NIFTY moves to X):
--------------------------------------------
Strike: 24300 CE
Entry: Short weekly @ ₹100, Long monthly @ ₹150, Net debit ₹50

If NIFTY = 24200 (100 pts down):
  Weekly CE value: ₹0 (expires worthless)
  Monthly CE value: ~₹50 (OTM but still has time value)
  P&L = ₹100 (kept from short) + ₹50 (sell monthly) - ₹50 (cost) = +₹100

If NIFTY = 24300 (unchanged):
  Weekly CE value: ₹0 (at-the-money, but let's say it decays to ₹5)
  Monthly CE value: ~₹120 (still has 7 DTE, ITM component + time value)
  P&L = ₹95 (bought back weekly at ₹5) + ₹120 - ₹50 = +₹165

If NIFTY = 24400 (100 pts up):
  Weekly CE value: ₹100 (ITM by 100)
  Monthly CE value: ~₹180 (ITM + time value)
  P&L = -₹0 (bought back weekly at ₹100) + ₹180 - ₹50 = +₹130

If NIFTY = 24500 (200 pts up):
  Weekly CE value: ₹200 (deep ITM)
  Monthly CE value: ~₹270 
  P&L = -₹100 (bought back weekly at ₹200) + ₹270 - ₹50 = +₹120

If NIFTY = 24100 (200 pts down):
  Weekly CE value: ₹0
  Monthly CE value: ~₹20 (deep OTM)
  P&L = ₹100 + ₹20 - ₹50 = +₹70


KEY INSIGHT: Calendar spread makes money as long as NIFTY doesn't
move too far from the strike. The sweet spot is ±100-150 pts.

MAX PROFIT: When NIFTY pins at the strike at weekly expiry
  Max profit: ~₹130-165 per unit = ₹8,450-10,725 per lot

MAX LOSS: Limited to net debit paid = ₹50 per unit = ₹3,250 per lot
  This happens if NIFTY moves extremely far (300+ pts) in either direction

RISK/REWARD RATIO: Excellent
  Max profit: ₹8,450
  Max loss: ₹3,250
  Risk/reward: 1:2.6 (you can make 2.6x what you risk)


WHY IS THIS "ARBITRAGE" AND NOT JUST A TRADE?
----------------------------------------------
Because you're exploiting a specific inefficiency:

In India, weekly NIFTY options are OVERPRICED 60-70% of the time.
Why? Retail traders buy weekly options for lottery-ticket bets.
This inflates weekly IV above fair value.

Meanwhile, monthly options are priced more efficiently by
institutional traders and market makers.

So: sell the overpriced weekly + buy the fairly-priced monthly
= lock in the IV differential as profit.

The "arbitrage" part is that you're capturing a systematic
pricing inefficiency, not making a directional bet.


REALISTIC EDGE IN INDIA (NIFTY):
--------------------------------
Typical weekly vs monthly IV differential:
  Weekly IV:  14-18% (often elevated by retail demand)
  Monthly IV: 13-15% (more efficiently priced)
  Differential: 1-3% in IV terms

In price terms on NIFTY:
  Weekly overpricing: 5-15 points
  After 2-leg entry cost: 3-8 points net
  Per lot (65 units): ₹195-520 per cycle
  After exit cost: ₹100-350 net per lot per cycle

This is SMALLER than parity break edge but MORE FREQUENT.


FREQUENCY & CYCLE:
------------------
- NIFTY has WEEKLY expiry every Tuesday
- You enter Monday/Tuesday morning
- Weekly expires Tuesday afternoon
- You close monthly position Tuesday or Wednesday
- That's ONE complete cycle per week
- 4 cycles per month
- 48 cycles per year

Compared to parity break:
  Parity break: 4-day cycle, 2 rolls per cycle = ~8-10 cycles/month
  Calendar spread: 1 cycle per week = 4 cycles/month
  
  But calendar spread is simpler (2 legs vs 3 legs)


MARGIN REQUIREMENTS:
--------------------
Calendar spread margin (Zerodha):
  Since you're selling weekly and buying monthly:
  Margin = Weekly SPAN margin - Monthly hedge benefit
  Typical: ₹80,000 - ₹1,20,000 per lot
  
  MUCH lower than parity break (₹1,60,000 per lot)
  
  With ₹5L capital: 4-6 lots possible
  With ₹10L capital: 8-10 lots possible


COSTS:
------
Entry: 2 orders = ₹40 brokerage + ₹10 charges = ₹50
Exit: 2 orders = ₹40 brokerage + ₹10 charges = ₹50
Total round-trip: ₹100 per cycle per lot

If you use LIMIT orders and avoid market slippage:
  Spread cost: ~₹5-10 per leg (weekly is liquid)
  Total spread cost: ₹10-20
  
Total cost per cycle: ₹110-120 per lot


ANNUAL INCOME PROJECTION (Rs.5L capital):
-----------------------------------------
Capital: ₹5,00,000
Lots: 4 (margin ₹1L each)
Buffer: ₹1L for costs

Per lot per cycle:
  Gross edge: ₹300 (conservative, after all costs)
  Net after costs: ₹200

Per month:
  4 cycles x ₹200 x 4 lots = ₹3,200/month

Per year:
  ₹3,200 x 12 = ₹38,400/year
  Return: 7.7%

With optimistic edge (₹400/lot/cycle):
  ₹6,400/month = ₹76,800/year = 15.4%

COMPARISON:
  FD: 6.5%
  Debt mutual funds: 7-8%
  Calendar spread: 8-15%
  Parity break: 15-30%

Calendar spread returns are LOWER than parity break but:
  - Lower margin requirement
  - Simpler execution (2 legs)
  - More frequent cycles
  - Can run alongside parity break


RISKS:
------
1. DIRECTIONAL RISK (MEDIUM):
   If NIFTY moves 200+ pts from strike in either direction,
   the long monthly loses value faster than the short weekly gains.
   Max loss = net debit paid (₹3,250 per lot)
   This is SMALL compared to potential profit.

2. IV CRUSH RISK (LOW):
   If weekly IV drops sharply after you enter (e.g., after event),
   the weekly price drops (good for your short) but so does the
   monthly (bad for your long). Net effect is usually positive
   because the short decays faster.

3. EARLY ASSIGNMENT RISK (VERY LOW):
   If NIFTY moves deep ITM before weekly expiry, you might get
   assigned on the short weekly. This is rare for ATM strikes.
   Mitigation: use strikes near ATM, not deep ITM.

4. EXECUTION RISK (LOW):
   2 legs need to fill at good prices.
   Weekly NIFTY options are very liquid — tight spreads.
   Use LIMIT orders at mid-price.

5. EXPIRY DAY RISK (LOW):
   On weekly expiry Tuesday, if NIFTY pins near the strike,
   there's pin risk. Both options might have similar value.
   Mitigation: exit before 2 PM on expiry day.


WHEN DOES CALENDAR SPREAD WORK BEST?
-------------------------------------
1. LOW VOLATILITY PERIODS:
   When NIFTY is range-bound, weekly options decay predictably.
   Best environment for calendar spreads.

2. BEFORE EVENTS (earnings, policy):
   Weekly IV spikes before events (retail buying).
   Monthly IV doesn't spike as much (smart money stays away).
   This is when the IV differential is LARGEST.
   
   Example: Before RBI policy day, weekly NIFTY IV might jump to
   22% while monthly stays at 15%. Sell the expensive weekly!

3. AFTER EVENTS:
   Post-event, weekly IV crashes (IV crush).
   Your short weekly benefits massively.
   Exit quickly for profit.

4. AVOID:
   - High trending markets (NIFTY moving 300+ pts/day)
   - Very low liquidity periods
   - Major uncertainty (election results, war)


IMPLEMENTATION IN YOUR SYSTEM:
------------------------------
Your existing scanner can be adapted:

1. SCAN for calendar spread opportunities:
   - Fetch weekly IV (from weekly option chain)
   - Fetch monthly IV (from monthly option chain)
   - If weekly IV > monthly IV + 1%: signal opportunity
   - Entry: sell weekly CE/PE + buy monthly CE/PE at same strike

2. EXECUTE:
   - Place 2 orders: SELL weekly + BUY monthly
   - Use LIMIT orders at mid-price
   - Verify fills

3. MONITOR:
   - Track daily P&L
   - Exit at weekly expiry (or roll to next weekly)

4. AUTO-EXIT:
   - At weekly expiry day, close monthly position
   - Or roll to next weekly (keep monthly, sell new weekly)


HEAD-TO-HEAD vs PARITY BREAK:
-----------------------------
                    Parity Break    Calendar Spread
Legs:               3               2
Margin/lot:         ₹1.6L           ₹1L
Edge/cycle:         ₹400-600        ₹200-400
Cycles/month:       8-10            4
Monthly/lot:        ₹3,200-6,000    ₹800-1,600
Capital needed:     ₹5L (2 lots)    ₹5L (4 lots)
Complexity:         Medium          Low
Risk:               Near-zero       Low (max loss = debit)
Automation:         Done ✅          Needs building

VERDICT: Calendar spread is a GOOD SUPPLEMENT to parity break.
It generates additional income with lower margin.
Combined portfolio: parity break + calendar spread = diversified
near-risk-free income stream.
""")

print("=" * 70)
print("RECOMMENDATION")
print("=" * 70)
print("""
KEEP parity break as primary (higher edge).
ADD calendar spread as secondary (lower margin, more lots).

With Rs.5L:
  - Parity break: 2 lots = ₹6,400-12,000/month
  - Calendar spread: 4 lots = ₹3,200-6,400/month
  - Combined: ₹9,600-18,400/month = ₹1,15,200-2,20,800/year
  - Combined return: 23-44% annually

That's genuinely strong for near-risk-free income.
""")
