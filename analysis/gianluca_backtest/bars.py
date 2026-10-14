"""TF-minute bars inside each session, aligned to the 18:00 ET open (plan §5.2)."""
from array import array

import timeutil as tu


class TFBars:
    def __init__(self, tf):
        self.tf = tf
        self.o = array("l"); self.h = array("l"); self.l = array("l"); self.c = array("l")
        self.v = array("q")
        self.close_et = array("q")   # ET-local epoch minute of the bar's close (its 'time')
        self.si = array("l")         # index into the session table
        self.i0 = array("q")         # first 1m index (inclusive)
        self.i1 = array("q")         # last 1m index (exclusive)
        self.s_first = []            # per session: first TF-bar index
        self.s_last = []             # per session: last TF-bar index (exclusive)

    def __len__(self):
        return len(self.c)


def build(base, tf):
    tb = TFBars(tf)
    et, o, h, l, c, v = base.et, base.o, base.h, base.l, base.c, base.v
    for si in range(len(base.s_day)):
        open_min = tu.session_open_et_min(base.s_day[si])
        end_min = open_min + tu.SESSION_LEN_MIN
        tb.s_first.append(len(tb.c))
        k, e = base.s_start[si], base.s_end[si]
        while k < e:
            bucket = (et[k] - open_min) // tf
            close = min(open_min + (bucket + 1) * tf, end_min)
            j = k
            hh, ll, vv = h[k], l[k], 0
            while j < e and et[j] < close:
                if h[j] > hh: hh = h[j]
                if l[j] < ll: ll = l[j]
                vv += v[j]
                j += 1
            tb.o.append(o[k]); tb.h.append(hh); tb.l.append(ll); tb.c.append(c[j - 1]); tb.v.append(vv)
            tb.close_et.append(close); tb.si.append(si); tb.i0.append(k); tb.i1.append(j)
            k = j
        tb.s_last.append(len(tb.c))
    return tb
