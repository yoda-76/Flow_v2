"""Fixed-dollar stop test for the anchored-VWAP strategy on MES (3m, EMA 11, rel ATR 1.1, 3 contracts).

Stop is for the whole position (3 contracts), set at entry. 0 = no stop. Each run goes through the full engine so the attempts / re-entry
logic reacts to the stop-outs. Roll-day entries removed. Windows: 2022 (never-seen year) and 2023 -> end.

    python analysis/gb_nq_backtest/stop_test.py
"""
import csv, subprocess, sys, os, re
STOPS = [0, 60, 180, 250, 500, 1000]
HERE = os.path.dirname(os.path.abspath(__file__))
CSVF = "analysis/data/MES_1m_latest.csv"


def run(stop, start, end, out):
    cmd = [sys.executable, f"{HERE}/anchored_vwap.py", "--csv", CSVF, "--start", start, "--tf", "3", "--ema", "11", "--contracts", "3",
           "--max-rel-atr", "1.1", "--mult", "5", "--tick", "0.25", "--equity", "25000", "--comm", "1.0", "--out", out]
    cmd += ["--stop-usd", str(stop)] if stop else ["--no-atr-stop"]
    if end: cmd += ["--end", end]
    subprocess.run(cmd, capture_output=True, check=True)
    flt = out + "/trades_noroll.csv"
    subprocess.run([sys.executable, f"{HERE}/roll_filter.py", "--csv", CSVF, "--trades", out + "/trades.csv", "--equity", "25000",
                    "--write-filtered", flt], capture_output=True, check=True)
    return list(csv.DictReader(open(flt)))


def summ(tr):
    p = [float(r["pnl_usd"]) for r in tr]
    gp, gl = sum(x for x in p if x > 0), -sum(x for x in p if x <= 0)
    eq = pk = dd = 0.0
    for x in p:
        eq += x; pk = max(pk, eq); dd = max(dd, pk - eq)
    st = sum(r["reason"] == "stop" for r in tr)
    return len(p), sum(p), gp / gl if gl else 9.99, dd, sum(x > 0 for x in p) / max(len(p), 1), min(p), st


print(f"{'stop $':>7} | {'window':<11} {'trades':>6} {'net':>8} {'PF':>5} {'maxDD':>6} {'win%':>5} {'worst':>7} {'stopped':>7}")
for stop in STOPS:
    for tag, st, en in (("2022", "2022-01-01", "2023-01-01"), ("2023->now", "2023-01-01", None)):
        tr = run(stop, st, en, f"reports/stop_test/s{stop}_{tag[:4]}")
        n, net, pf, dd, w, worst, ns = summ(tr)
        print(f"{stop or 'none':>7} | {tag:<11} {n:6d} {net:+8,.0f} {pf:5.2f} {dd:6,.0f} {w:5.0%} {worst:+7,.0f} {ns:7d}", flush=True)
