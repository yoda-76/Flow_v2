package com.flow.flow;

import com.flow.core.Feature;

import java.util.List;

/**
 * Order-id repeat tracking (D-56) -- the aggregation mode D-53 originally
 * built for BigTradeView before D-55 switched that construct to a
 * time+price window instead. Kept alive here as its own construct per the
 * user's explicit request: "we might need both to identify iceberg
 * orders and more analyses" -- a single order id trading repeatedly at
 * the same price over time (regardless of any size threshold) is the raw
 * signal iceberg/hidden-liquidity analysis needs, which is a genuinely
 * different question from "was this a big print" (BigTradeView's job).
 *
 * Ready immediately (D-22 class 1) -- no threshold, nothing to warm up.
 */
public interface OrderRepeatView extends Feature {
  String FEATURE_ID = "order_repeats";

  /** Order ids seen more than once this session, oldest first, bounded window. */
  List<OrderRepeatEvent> recent();
}
