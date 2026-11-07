/* RR (risk/reward) blocks: one Lightweight Charts v5 series primitive drawing a list of blocks.
 * Plain ES5, no imports. Block: { id, side:'long'|'short', start:tSec, end:tSec, entry, stop, target, label? }
 * Times are UTC seconds; they are mapped onto the CURRENT timeframe's bars (setTimeframe), so blocks survive tf switches.
 * Usage: var rr = new RRBlocks(); candles.attachPrimitive(rr); rr.setTimeframe(900, timesArray); rr.setBlocks([...]);
 */
(function () {
  'use strict';

  var MINUS = '−';

  function cssVar(n) { return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }

  // "rgba(r,g,b,a)" -> same colour with alpha scaled by k (capped at 1); anything else returned unchanged
  function scaleAlpha(col, k) {
    var m = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(col);
    if (!m) return col;
    var a = m[4] === undefined ? 1 : parseFloat(m[4]);
    return 'rgba(' + m[1] + ',' + m[2] + ',' + m[3] + ',' + Math.min(1, a * k) + ')';
  }

  // timeScale().logicalToCoordinate() returns 0 for a FRACTIONAL logical index (verified on v5.2.1), so interpolate
  // between the two neighbouring integer indices ourselves (fractions only occur when extrapolating past the data).
  function xOf(ts, l) {
    if (l === Math.floor(l)) return ts.logicalToCoordinate(l);
    var f = Math.floor(l), c0 = ts.logicalToCoordinate(f), c1 = ts.logicalToCoordinate(f + 1);
    if (c0 === null || c1 === null) return null;
    return c0 + (c1 - c0) * (l - f);
  }

  // first variant whose width fits maxW, else null
  function pick(ctx, variants, maxW) {
    for (var i = 0; i < variants.length; i++) if (ctx.measureText(variants[i]).width <= maxW) return variants[i];
    return null;
  }

  function RRBlocks() {
    this._blocks = [];
    this._times = [];
    this._barSec = 900;
    this._precision = 1;
    this._chart = null;
    this._series = null;
    this._requestUpdate = null;
    this._hoverCache = {};
    var self = this;
    this._view = {
      zOrder: function () { return 'normal'; },
      renderer: function () { return self._renderer; }
    };
    this._renderer = {
      draw: function (target) { self._draw(target); }
    };
  }

  RRBlocks.prototype.attached = function (param) {
    this._chart = param.chart;
    this._series = param.series;
    this._requestUpdate = param.requestUpdate;
    this._update();
  };
  RRBlocks.prototype.detached = function () {
    this._chart = null; this._series = null; this._requestUpdate = null;
  };
  RRBlocks.prototype.updateAllViews = function () { /* geometry is computed in draw() from live chart state */ };
  RRBlocks.prototype.paneViews = function () { return [this._view]; };

  RRBlocks.prototype._update = function () { if (this._requestUpdate) this._requestUpdate(); };

  // ---- public API ----
  RRBlocks.prototype.setBlocks = function (arr) { this._blocks = (arr || []).slice(); this._hoverCache = {}; this._update(); };
  RRBlocks.prototype.add = function (b) { this._blocks.push(b); this._update(); };
  RRBlocks.prototype.remove = function (id) {
    this._blocks = this._blocks.filter(function (b) { return b.id !== id; });
    delete this._hoverCache[id];
    this._update();
  };
  RRBlocks.prototype.clear = function () { this._blocks = []; this._hoverCache = {}; this._update(); };
  RRBlocks.prototype.blocks = function () { return this._blocks.slice(); };
  RRBlocks.prototype.setTimeframe = function (tfSec, timesArray) {
    this._barSec = tfSec || 60;
    this._times = timesArray || [];
    this._update();
  };
  RRBlocks.prototype.setPrecision = function (p) { this._precision = p; this._update(); };

  // ---- geometry ----
  // Logical index (may be fractional only when extrapolating) of the bar at/just before time t.
  RRBlocks.prototype._logicalOf = function (t) {
    var a = this._times, n = a.length;
    if (!n) return null;
    if (t < a[0]) return (t - a[0]) / this._barSec;
    if (t > a[n - 1]) return (n - 1) + (t - a[n - 1]) / this._barSec;
    var lo = 0, hi = n - 1;
    while (lo < hi) {  // last index with a[i] <= t
      var mid = (lo + hi + 1) >> 1;
      if (a[mid] <= t) lo = mid; else hi = mid - 1;
    }
    return lo;
  };

  // Returns [{b, x1, x2, yE, yS, yT}] in media (css) pixels for blocks that can be drawn.
  RRBlocks.prototype._layout = function (width) {
    var out = [];
    if (!this._chart || !this._series) return out;
    var ts = this._chart.timeScale();
    for (var i = 0; i < this._blocks.length; i++) {
      var b = this._blocks[i];
      var l1 = this._logicalOf(b.start), l2 = this._logicalOf(b.end);
      if (l1 === null || l2 === null) continue;
      var x1 = xOf(ts, l1), x2 = xOf(ts, l2);
      var yE = this._series.priceToCoordinate(b.entry);
      var yS = this._series.priceToCoordinate(b.stop);
      var yT = this._series.priceToCoordinate(b.target);
      if (x1 === null || x2 === null || yE === null || yS === null || yT === null) continue;
      if (width !== undefined && (x2 < 0 || x1 > width)) continue;
      out.push({ b: b, x1: x1, x2: x2, yE: yE, yS: yS, yT: yT });
    }
    return out;
  };

  RRBlocks.prototype._fmt = function (x) { return x.toFixed(this._precision); };

  RRBlocks.prototype._draw = function (target) {
    var self = this;
    var win = cssVar('--rr-win') || 'rgba(38,166,154,0.18)';
    var loss = cssVar('--rr-loss') || 'rgba(239,83,80,0.18)';
    var line = cssVar('--rr-line') || '#8592a3';
    var fg = cssVar('--fg') || '#d7dde5';
    var items = null;
    target.useBitmapCoordinateSpace(function (scope) {
      var ctx = scope.context, hr = scope.horizontalPixelRatio, vr = scope.verticalPixelRatio;
      items = self._layout(scope.mediaSize.width);
      ctx.save();
      for (var i = 0; i < items.length; i++) {
        var it = items[i];
        var bx1 = Math.round(Math.min(it.x1, it.x2) * hr), bx2 = Math.round(Math.max(it.x1, it.x2) * hr);
        var w = bx2 - bx1;
        if (w < 1) continue;
        var yE = Math.round(it.yE * vr), yS = Math.round(it.yS * vr), yT = Math.round(it.yT * vr);
        var zones = [[yE, yT, win], [yE, yS, loss]];
        for (var z = 0; z < zones.length; z++) {
          var top = Math.min(zones[z][0], zones[z][1]), h = Math.abs(zones[z][1] - zones[z][0]);
          ctx.fillStyle = zones[z][2];
          ctx.fillRect(bx1, top, w, h);
          ctx.strokeStyle = scaleAlpha(zones[z][2], 3);
          ctx.lineWidth = Math.max(1, Math.round(hr));
          var lw = ctx.lineWidth / 2;
          ctx.strokeRect(bx1 + lw, top + lw, Math.max(0, w - ctx.lineWidth), Math.max(0, h - ctx.lineWidth));
        }
        ctx.strokeStyle = line;
        ctx.lineWidth = Math.max(1, Math.round(vr));
        ctx.beginPath();
        ctx.moveTo(bx1, yE + 0.5);
        ctx.lineTo(bx2, yE + 0.5);
        ctx.stroke();
      }
      ctx.restore();
    });
    if (!items || !items.length) return;
    target.useMediaCoordinateSpace(function (scope) {
      var ctx = scope.context;
      ctx.save();
      ctx.font = '11px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
      ctx.fillStyle = fg;
      for (var i = 0; i < items.length; i++) {
        var it = items[i], b = it.b;
        var x1 = Math.min(it.x1, it.x2), x2 = Math.max(it.x1, it.x2);
        if (x2 - x1 < 60) continue;
        var risk = Math.abs(b.entry - b.stop), reward = Math.abs(b.target - b.entry);
        var rr = risk > 0 ? reward / risk : 0;
        var top = Math.min(it.yE, it.yS, it.yT), bot = Math.max(it.yE, it.yS, it.yT);
        ctx.save();
        ctx.beginPath();
        ctx.rect(x1, top, x2 - x1, bot - top);
        ctx.clip();
        // zone texts, right-aligned, on the far edge of each zone
        var maxW = x2 - x1 - 8;
        var zoneTxt = [
          [it.yT, 16, ['Target ' + self._fmt(b.target) + '  +' + self._fmt(reward) + ' pts (' + rr.toFixed(2) + 'R)',
                       'Target ' + self._fmt(b.target) + '  +' + self._fmt(reward),
                       'Target ' + self._fmt(b.target)]],
          [it.yS, 30, ['Stop ' + self._fmt(b.stop) + '  ' + MINUS + self._fmt(risk) + ' pts',
                       'Stop ' + self._fmt(b.stop) + '  ' + MINUS + self._fmt(risk),
                       'Stop ' + self._fmt(b.stop)]]
        ];
        ctx.textAlign = 'right';
        for (var z = 0; z < zoneTxt.length; z++) {
          var yFar = zoneTxt[z][0], h = Math.abs(yFar - it.yE);
          if (h < zoneTxt[z][1]) continue;  // zone too short (the stop zone also hosts the entry label)
          var txt = pick(ctx, zoneTxt[z][2], maxW);
          if (!txt) continue;
          var farIsTop = yFar < it.yE;
          ctx.textBaseline = farIsTop ? 'top' : 'bottom';
          ctx.fillText(txt, x2 - 4, farIsTop ? yFar + 3 : yFar - 3);
        }
        // entry text just beside the entry line, on the reward side's opposite? keep it on the risk side so it never meets the target text
        var riskBelow = it.yS > it.yE;
        ctx.textAlign = 'left';
        ctx.textBaseline = riskBelow ? 'top' : 'bottom';
        var base = (b.side === 'long' ? 'Long ' : 'Short ') + self._fmt(b.entry) + '  RR ' + rr.toFixed(2);
        var head = pick(ctx, [base + (b.label ? '  ' + b.label : ''), base, (b.side === 'long' ? 'Long ' : 'Short ') + self._fmt(b.entry)], maxW);
        if (head) ctx.fillText(head, x1 + 4, riskBelow ? it.yE + 3 : it.yE - 3);
        ctx.restore();
      }
      ctx.restore();
    });
  };

  // ---- autoscale: keep the whole block visible ----
  RRBlocks.prototype.autoscaleInfo = function (startLogical, endLogical) {
    var lo = Infinity, hi = -Infinity;
    for (var i = 0; i < this._blocks.length; i++) {
      var b = this._blocks[i];
      var l1 = this._logicalOf(b.start), l2 = this._logicalOf(b.end);
      if (l1 === null || l2 === null) continue;
      if (Math.max(l1, l2) < startLogical || Math.min(l1, l2) > endLogical) continue;
      lo = Math.min(lo, b.stop, b.target, b.entry);
      hi = Math.max(hi, b.stop, b.target, b.entry);
    }
    if (lo === Infinity) return null;
    return { priceRange: { minValue: lo, maxValue: hi } };
  };

  // ---- hover / click identification ----
  RRBlocks.prototype.hitTest = function (x, y) {
    var items = this._layout();
    for (var i = items.length - 1; i >= 0; i--) {
      var it = items[i];
      var x1 = Math.min(it.x1, it.x2), x2 = Math.max(it.x1, it.x2);
      var top = Math.min(it.yE, it.yS, it.yT), bot = Math.max(it.yE, it.yS, it.yT);
      if (x >= x1 && x <= x2 && y >= top && y <= bot) {
        var id = it.b.id;
        if (!this._hoverCache[id]) this._hoverCache[id] = { cursorStyle: 'pointer', externalId: id, zOrder: 'normal' };
        return this._hoverCache[id];
      }
    }
    return null;
  };

  window.RRBlocks = RRBlocks;
})();
