"""Per-TF feature bundle (bars + indicators) and completed-session VAs, cached (plan §5.13)."""
import time

import bars as _bars
import data
import indicators as ind
import vprofile as prof

TFS = (15, 5, 10, 30)
SWING_NS = (3, 2, 5)
VA_PCTS = (70, 60, 80)


class TFFeatures:
    pass


def build_tf(base, tf):
    f = TFFeatures()
    f.tf = tf
    f.bars = b = _bars.build(base, tf)
    f.vol_sma = ind.vol_sma(b.v)
    f.vol_ema = ind.vol_ema(b.v)
    f.atr = ind.atr(b.h, b.l, b.c)
    f.atr_pct = ind.atr_pct(f.atr)
    f.age = ind.bocpd_age(b.h, b.l)
    f.swings = {n: ind.swings(b.h, b.l, n) for n in SWING_NS}
    return f


def load_vas(base):
    return data.cache_load("vas", lambda: prof.completed_vas(base, VA_PCTS))


def load_tf(base, tf):
    return data.cache_load(f"tf{tf}", lambda: build_tf(base, tf))


def build_all(tfs=TFS):
    """Build every cache; returns timings in seconds."""
    t = {}
    t0 = time.time(); base = data.load_base(); t["base"] = time.time() - t0
    t0 = time.time(); load_vas(base); t["vas"] = time.time() - t0
    for tf in tfs:
        t0 = time.time(); load_tf(base, tf); t[f"tf{tf}"] = time.time() - t0
    return base, t


if __name__ == "__main__":
    import sys
    import features   # build through the module, so pickles reference features.TFFeatures, not __main__
    tfs = tuple(int(x) for x in sys.argv[1:]) or TFS
    base, t = features.build_all(tfs)
    print({k: round(v, 1) for k, v in t.items()}, base.stats)
