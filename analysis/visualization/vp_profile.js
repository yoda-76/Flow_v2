/* Session volume profiles (Gianluca Bruni "SVP HD"): one Lightweight Charts v5 series primitive.
 * Plain ES5, no imports. Data (from viz_build.py profiles.js): { tick, sessions:[{s,e,dev,lo,poc,vah,val,v:[...]}] }
 * s/e = session start/end in UTC seconds; v[i] = volume at price lo + i*tick; poc/vah/val are prices.
 * Per session: a histogram growing rightwards from the session start (value-area rows blue, others grey, POC row
 * orange), POC/VAH/VAL lines across the session, and each COMPLETED session's lines carried on through the next
 * session = "previous value area" (the levels the strategy trades against).
 * Usage: var vp = new VPProfile(); candles.attachPrimitive(vp); vp.setTimeframe(900, times); vp.setProfiles(data);
 */
(function () {
  'use strict';

  function cssVar(n) { return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }

  // timeScale().logicalToCoordinate() returns 0 for a FRACTIONAL logical index (verified on v5.2.1); interpolate.
  function xOf(ts, l) {
    if (l === Math.floor(l)) return ts.logicalToCoordinate(l);
    var f = Math.floor(l), c0 = ts.logicalToCoordinate(f), c1 = ts.logicalToCoordinate(f + 1);
    if (c0 === null || c1 === null) return null;
    return c0 + (c1 - c0) * (l - f);
  }

  function VPProfile() {
    this._sessions = [];
    this._tick = 0.25;
    this._times = [];
    this._barSec = 900;
    this._precision = 2;
    this._visible = true;
    this._chart = null; this._series = null; this._requestUpdate = null;
    var self = this;
    this._view = {
      zOrder: function () { return 'bottom'; },
      renderer: function () { return self._renderer; }
    };
    this._renderer = { draw: function (target) { self._draw(target); } };
  }

  VPProfile.prototype.attached = function (p) { this._chart = p.chart; this._series = p.series; this._requestUpdate = p.requestUpdate; this._update(); };
  VPProfile.prototype.detached = function () { this._chart = null; this._series = null; this._requestUpdate = null; };
  VPProfile.prototype.updateAllViews = function () { };
  VPProfile.prototype.paneViews = function () { return [this._view]; };
  VPProfile.prototype._update = function () { if (this._requestUpdate) this._requestUpdate(); };

  VPProfile.prototype.setProfiles = function (data) {
    this._sessions = (data && data.sessions) || [];
    this._tick = (data && data.tick) || this._tick;
    this._update();
  };
  VPProfile.prototype.setTimeframe = function (tfSec, times) { this._barSec = tfSec || 60; this._times = times || []; this._update(); };
  VPProfile.prototype.setPrecision = function (p) { this._precision = p; this._update(); };
  VPProfile.prototype.setVisible = function (v) { this._visible = !!v; this._update(); };
  VPProfile.prototype.visible = function () { return this._visible; };
  VPProfile.prototype.count = function () { return this._sessions.length; };

  // logical index (fractional only when extrapolating) of the bar at/just before time t
  VPProfile.prototype._logicalOf = function (t) {
    var a = this._times, n = a.length;
    if (!n) return null;
    if (t < a[0]) return (t - a[0]) / this._barSec;
    if (t > a[n - 1]) return (n - 1) + (t - a[n - 1]) / this._barSec;
    var lo = 0, hi = n - 1;
    while (lo < hi) { var mid = (lo + hi + 1) >> 1; if (a[mid] <= t) lo = mid; else hi = mid - 1; }
    return lo;
  };

  VPProfile.prototype._draw = function (target) {
    var self = this;
    if (!this._visible || !this._chart || !this._series || !this._sessions.length || !this._times.length) return;
    var cVa = cssVar('--vp-va') || 'rgba(76,141,255,0.42)';
    var cOut = cssVar('--vp-out') || 'rgba(133,146,163,0.26)';
    var cPoc = cssVar('--vp-poc') || '#f5a623';
    var cEdge = cssVar('--vp-edge') || '#4c8dff';
    var fg = cssVar('--fg') || '#d7dde5';
    var ts = this._chart.timeScale(), ser = this._series, tick = this._tick, S = this._sessions;
    var vis = ts.getVisibleLogicalRange();
    var lines = [];   // collected so labels can be drawn in the media pass

    target.useBitmapCoordinateSpace(function (scope) {
      var ctx = scope.context, hr = scope.horizontalPixelRatio, vr = scope.verticalPixelRatio, W = scope.mediaSize.width;
      ctx.save();
      for (var si = 0; si < S.length; si++) {
        var s = S[si], l1 = self._logicalOf(s.s), l2 = self._logicalOf(s.e);
        if (l1 === null || l2 === null) continue;
        // the previous-VA carry-over runs to the end of the NEXT session
        var nx = S[si + 1], l3 = nx ? self._logicalOf(nx.e) : l2;
        if (vis && (l1 > vis.to || Math.max(l2, l3) < vis.from)) continue;
        var x1 = xOf(ts, l1), x2 = xOf(ts, l2), x3 = xOf(ts, l3);
        if (x1 === null || x2 === null || x3 === null) continue;
        if (x3 < 0 || x1 > W) continue;
        var sw = x2 - x1;
        if (sw < 3) continue;   // too thin to read (e.g. daily bars far zoomed out)

        // histogram rows: merge ticks so a row is at least ~3 css px tall
        var yA = ser.priceToCoordinate(s.lo), yB = ser.priceToCoordinate(s.lo + tick * 50);
        if (yA === null || yB === null) continue;
        var pxTick = Math.abs(yA - yB) / 50;
        if (pxTick <= 0) continue;
        var bin = Math.max(1, Math.ceil(3 / pxTick));
        var n = s.v.length, rows = Math.ceil(n / bin), agg = new Array(rows), vmax = 0, i;
        for (i = 0; i < rows; i++) agg[i] = 0;
        for (i = 0; i < n; i++) agg[(i / bin) | 0] += s.v[i];
        for (i = 0; i < rows; i++) if (agg[i] > vmax) vmax = agg[i];
        if (vmax <= 0) continue;
        var maxW = Math.min(sw * 0.55, 420);
        var pocRow = ((Math.round((s.poc - s.lo) / tick)) / bin) | 0;
        for (i = 0; i < rows; i++) {
          if (agg[i] <= 0) continue;
          var pLo = s.lo + i * bin * tick, pHi = pLo + bin * tick;      // row spans [pLo, pHi) in price
          var yTop = ser.priceToCoordinate(pHi - tick / 2), yBot = ser.priceToCoordinate(pLo - tick / 2);
          if (yTop === null || yBot === null) continue;
          var top = Math.round(Math.min(yTop, yBot) * vr), h = Math.max(1, Math.round(Math.abs(yBot - yTop) * vr) - 1);
          var w = Math.max(1, Math.round(agg[i] / vmax * maxW * hr));
          var inVa = pHi - tick > s.val - 1e-9 && pLo < s.vah + tick * 0.5;
          ctx.fillStyle = i === pocRow ? cPoc : (inVa ? cVa : cOut);
          ctx.fillRect(Math.round(x1 * hr), top, w, h);
        }

        // POC / VAH / VAL across the session, then carried through the next one when this session is complete
        var yP = ser.priceToCoordinate(s.poc), yH = ser.priceToCoordinate(s.vah), yL = ser.priceToCoordinate(s.val);
        var xe = s.dev ? x2 : x3;
        var defs = [[yP, cPoc, false, s.poc, 'POC'], [yH, cEdge, true, s.vah, 'VAH'], [yL, cEdge, true, s.val, 'VAL']];
        for (var d = 0; d < defs.length; d++) {
          if (defs[d][0] === null) continue;
          var y = Math.round(defs[d][0] * vr) + 0.5;
          ctx.strokeStyle = defs[d][1];
          ctx.lineWidth = Math.max(1, Math.round(vr));
          ctx.setLineDash(defs[d][2] ? [4 * hr, 3 * hr] : []);
          ctx.globalAlpha = 1;
          ctx.beginPath(); ctx.moveTo(Math.round(x1 * hr), y); ctx.lineTo(Math.round(x2 * hr), y); ctx.stroke();
          if (!s.dev && xe > x2) {     // carry-over = "previous value area" for the next session
            ctx.globalAlpha = 0.55;
            ctx.beginPath(); ctx.moveTo(Math.round(x2 * hr), y); ctx.lineTo(Math.round(xe * hr), y); ctx.stroke();
          }
          lines.push({ x: s.dev ? x2 : xe, y: defs[d][0], t: defs[d][4] + ' ' + defs[d][3].toFixed(self._precision), w: sw });
        }
        ctx.globalAlpha = 1;
        ctx.setLineDash([]);
      }
      ctx.restore();
    });

    if (!lines.length) return;
    target.useMediaCoordinateSpace(function (scope) {
      var ctx = scope.context, W = scope.mediaSize.width;
      ctx.save();
      ctx.font = '10px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
      ctx.fillStyle = fg; ctx.textAlign = 'right'; ctx.textBaseline = 'bottom';
      for (var i = 0; i < lines.length; i++) {
        var L = lines[i];
        if (L.w < 90 || L.x < 0 || L.x - 2 > W + 200) continue;
        ctx.fillText(L.t, Math.min(L.x - 3, W - 3), L.y - 2);
      }
      ctx.restore();
    });
  };

  window.VPProfile = VPProfile;
})();
