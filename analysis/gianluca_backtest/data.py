"""1m CSV loading, session table, pickle cache (plan §2, §5.1, §5.13).

Prices are stored as int ticks (price * 10; GC tick = 0.1). Everything derived is cached in
reports/gianluca_backtest/cache/, keyed by CSV size + mtime + CODE_VERSION.
"""
import os
import pickle
from array import array
from pathlib import Path

import timeutil as tu

CODE_VERSION = "1"
REPO = Path(__file__).resolve().parents[2]
CSV = REPO / "analysis" / "data" / "GC_1m_latest.csv"
CACHE_DIR = REPO / "reports" / "gianluca_backtest" / "cache"

TICK = 0.1
TICK_USD = 10.0
MIN_SESSION_BARS = 300
GAP_MIN = 30


class Base:
    """1m arrays (only bars inside a session) + session table."""

    def __init__(self):
        self.utc = array("q")      # bar open, UTC seconds
        self.et = array("q")       # bar open, ET-local epoch minutes
        self.o = array("l")
        self.h = array("l")
        self.l = array("l")
        self.c = array("l")
        self.v = array("q")
        self.sess = array("l")     # session day of each bar
        # sessions: parallel lists, only VALID sessions (>= MIN_SESSION_BARS bars)
        self.s_day = []
        self.s_start = []          # first 1m index (inclusive)
        self.s_end = []            # last 1m index (exclusive)
        self.stats = {}


def _ticks(s):
    return int(round(float(s) * 10))


def parse_csv(path, rows=None):
    """Parse rows (iterable of 'ts,o,h,l,c,v' strings) or the file at path into a Base."""
    b = Base()
    dropped_maint = 0
    year_bounds = None
    cur_year_end = -1
    with (open(path, encoding="utf-8") if rows is None else _nullctx(rows)) as fh:
        it = iter(fh)
        if rows is None:
            next(it)                                   # header
        last = -1
        for line in it:
            ts, o, h, l, c, v = line.rstrip("\n").split(",")
            utc = int(ts) // 1000
            if utc <= last:
                raise ValueError(f"timestamps not strictly increasing at {ts}")
            last = utc
            if utc >= cur_year_end:                    # recompute DST bounds once per year
                import datetime as _d
                y = _d.datetime.fromtimestamp(utc, _d.timezone.utc).year
                year_bounds = tu.dst_bounds_utc(y)
                cur_year_end = int(_d.datetime(y + 1, 1, 1, tzinfo=_d.timezone.utc).timestamp())
            off = -14400 if year_bounds[0] <= utc < year_bounds[1] else -18000
            et_min = (utc + off) // 60
            sd = tu.session_of_et_min(et_min)
            if sd is None:
                dropped_maint += 1
                continue
            b.utc.append(utc); b.et.append(et_min)
            b.o.append(_ticks(o)); b.h.append(_ticks(h)); b.l.append(_ticks(l)); b.c.append(_ticks(c))
            b.v.append(int(float(v))); b.sess.append(sd)
    b.stats["rows_in_17h_break"] = dropped_maint
    _build_sessions(b)
    return b


class _nullctx:
    def __init__(self, x):
        self.x = x

    def __enter__(self):
        return self.x

    def __exit__(self, *a):
        return False


def _build_sessions(b):
    """Group bars into sessions, drop short ones (and their bars), count gaps > GAP_MIN inside sessions."""
    n = len(b.sess)
    keep = []      # (start, end) of valid sessions in the original arrays
    short = 0
    i = 0
    while i < n:
        j = i
        sd = b.sess[i]
        while j < n and b.sess[j] == sd:
            j += 1
        if j - i >= MIN_SESSION_BARS:
            keep.append((i, j))
        else:
            short += 1
        i = j
    if short:
        nb = Base()
        nb.stats = b.stats
        for (i, j) in keep:
            for name in ("utc", "et", "o", "h", "l", "c", "v", "sess"):
                getattr(nb, name).extend(getattr(b, name)[i:j])
        b2 = nb
    else:
        b2 = b
    gaps = 0
    pos = 0
    for (i, j) in keep:
        ln = j - i
        b2.s_day.append(b.sess[i]); b2.s_start.append(pos); b2.s_end.append(pos + ln)
        et = b2.et
        for k in range(pos + 1, pos + ln):
            if et[k] - et[k - 1] > GAP_MIN:
                gaps += 1
        pos += ln
    b2.stats.update(short_sessions_dropped=short, sessions=len(keep), gaps_over_30m=gaps, bars=pos)
    if b2 is not b:
        b.__dict__.update(b2.__dict__)


def _cache_key(path):
    st = os.stat(path)
    return f"{st.st_size}_{int(st.st_mtime)}_{CODE_VERSION}"


def cache_load(name, builder, path=CSV):
    """Load cache/<name>_<key>.pkl, or build it with builder() and store it."""
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    f = CACHE_DIR / f"{name}_{_cache_key(path)}.pkl"
    if f.exists():
        with open(f, "rb") as fh:
            return pickle.load(fh)
    obj = builder()
    tmp = f.with_suffix(".tmp")
    with open(tmp, "wb") as fh:
        pickle.dump(obj, fh, protocol=pickle.HIGHEST_PROTOCOL)
    os.replace(tmp, f)
    return obj


def load_base(path=CSV):
    return cache_load("base", lambda: parse_csv(path), path)
