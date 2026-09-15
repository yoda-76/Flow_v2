package com.flow.flow;

import com.flow.core.Feature;

import java.util.List;

/**
 * Read-only volume-profile view (D-36): POC/VAH/VAL plus LVN/HVN zones,
 * in integer tick offsets (D-21). No SDK import here or anywhere in this
 * package -- the implementation (SdkVolumeProfileFeature, D-36/D-43)
 * lives in flow-runtime; this interface is what a strategy or entry
 * evaluator in flow-core actually sees.
 *
 * All accessors return null/empty before the feature is ready (D-37:
 * forward-only from attach, no historical warm-start) -- check isReady()
 * first, same as every other Feature.
 *
 * Extends LevelSource/ZoneSource (default-implemented below) so
 * TriggerEvaluator can drive D-38's LevelCross/ZoneTransition triggers
 * against this feature without knowing it is volume-profile-specific.
 */
public interface VolumeProfileView extends Feature, LevelSource, ZoneSource {
  Integer poc();
  Integer vah();
  Integer val();

  /** Total volume accumulated so far this session (all buckets, ask+bid). */
  double totalVolume();

  /** Ask volume minus bid volume, summed across all buckets. */
  double totalDelta();

  /** Volume at a specific bucket, or null if that bucket has never traded. */
  Double volumeAt(int bucketPriceTicks);

  /** LVN and HVN clusters, persistent ids (D-38). Order not significant. */
  List<ZoneView> zones();

  @Override
  default Integer levelValue(String levelName) {
    return switch (levelName) {
      case "POC" -> poc();
      case "VAH" -> vah();
      case "VAL" -> val();
      default -> null;
    };
  }

  @Override
  default List<ZoneView> zonesOfKind(ZoneView.Kind kind) {
    return zones().stream().filter(z -> z.kind() == kind).toList();
  }
}
