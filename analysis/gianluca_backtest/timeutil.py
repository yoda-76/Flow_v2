"""UTC <-> US Eastern without zoneinfo (plan §5.1), and session assignment.

EDT (UTC-4) from the 2nd Sunday of March 02:00 local (07:00 UTC) to the 1st Sunday of November 02:00 local
(06:00 UTC); EST (UTC-5) otherwise. Session = 18:00 ET -> 17:00 ET next day, keyed by the ET date of its close,
expressed as days since 1970-01-01 (an int). 17:00-17:59 ET belongs to no session.
"""
import datetime as _dt

_EPOCH = _dt.date(1970, 1, 1)
_DST_CACHE = {}

SESSION_OPEN_MIN = 18 * 60      # ET minute-of-day
SESSION_CLOSE_MIN = 17 * 60
SESSION_LEN_MIN = 23 * 60


def _nth_sunday(year, month, n):
    d = _dt.date(year, month, 1)
    first = d + _dt.timedelta(days=(6 - d.weekday()) % 7)
    return first + _dt.timedelta(days=7 * (n - 1))


def dst_bounds_utc(year):
    """(start, end) of EDT in UTC seconds for `year`."""
    b = _DST_CACHE.get(year)
    if b is None:
        s = _nth_sunday(year, 3, 2)
        e = _nth_sunday(year, 11, 1)
        start = ((s - _EPOCH).days * 86400) + 7 * 3600
        end = ((e - _EPOCH).days * 86400) + 6 * 3600
        b = _DST_CACHE[year] = (start, end)
    return b


def et_offset_s(utc_s):
    """-14400 (EDT) or -18000 (EST) for a UTC epoch second."""
    year = _dt.datetime.fromtimestamp(utc_s, _dt.timezone.utc).year
    start, end = dst_bounds_utc(year)
    return -14400 if start <= utc_s < end else -18000


def et_seconds(utc_s):
    """ET-local 'epoch' seconds (local wall clock read as if it were UTC)."""
    return utc_s + et_offset_s(utc_s)


def session_of_et_min(et_min):
    """Session day (ET date of close, days since epoch) for an ET-local epoch minute, or None in 17:00-17:59."""
    day, mod = divmod(et_min, 1440)
    if mod >= SESSION_OPEN_MIN:
        return day + 1
    if mod >= SESSION_CLOSE_MIN:
        return None
    return day


def session_open_et_min(session_day):
    return (session_day - 1) * 1440 + SESSION_OPEN_MIN


def day_to_date(day):
    return _EPOCH + _dt.timedelta(days=day)


def weekday_of_session(session_day):
    """0 = Monday ... (weekday of the session's close date)."""
    return day_to_date(session_day).weekday()
