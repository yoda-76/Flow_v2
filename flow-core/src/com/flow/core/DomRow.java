package com.flow.core;

/**
 * One resting-liquidity row from a DOM update -- aggregate size at a
 * price, one side only (bid rows and ask rows never share a price in a
 * real book, unlike traded volume which can print on both sides at the
 * same price). Per-order detail (individual resting orders, ages) is
 * deliberately not carried here (D-58) -- see LiquidityMapView's javadoc
 * and the deferred experimentation item in todo.md about whether that
 * detail is even reliably available and whether it's worth anything for
 * intent analysis (iceberg/manipulation detection) before building
 * toward it.
 */
public record DomRow(int priceTicks, double size) {}
