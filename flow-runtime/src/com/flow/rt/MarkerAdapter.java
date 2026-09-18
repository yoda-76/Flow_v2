package com.flow.rt;

import com.motivewave.platform.sdk.common.Coordinate;
import com.motivewave.platform.sdk.draw.Marker;

import java.awt.Color;
import java.lang.reflect.Constructor;

/**
 * Builds a CIRCLE Marker via reflection, not direct source references to
 * Enums.MarkerType/Size/Position -- same class of Javadoc-vs-jar mismatch
 * as T-6/TickAdapter's Enums.BarData finding (../../motivewave/docs/
 * dynamic/findings.md): Enums' nested enum types compile into the jar but
 * are not resolvable as source-level types from javac ("cannot find
 * symbol: class MarkerType, location: interface Enums"), confirmed
 * directly here by trying both a qualified reference
 * (Enums.MarkerType.CIRCLE) and a direct import -- both fail to compile
 * the same way T-6 already found for Enums.BarData.
 *
 * Marker itself (the containing class, not these param types) is a
 * perfectly ordinary nameable type -- only the enum constants going INTO
 * its constructor need reflection, so circle() returns a genuinely typed
 * Marker, not a proxy; every other Marker method (setTextValue, etc.) is
 * called normally afterward, same as TickAdapter's proxy returns a
 * genuinely typed Tick that VolumeProfile.onTick() calls normally.
 *
 * Static init loads the enum classes and looks up CIRCLE/MEDIUM/CENTER
 * once -- cheap to do eagerly since a redraw pass constructs many of
 * these per second (D-54: one per big trade in the drawn window).
 */
final class MarkerAdapter {
  private static final Class<?> MARKER_TYPE_CLS;
  private static final Class<?> SIZE_CLS;
  private static final Class<?> POSITION_CLS;
  private static final Object CIRCLE;
  private static final Object ARROW;
  private static final Object MEDIUM;
  private static final Object CENTER;
  private static final Object TOP;
  private static final Object BOTTOM;
  private static final Constructor<?> MARKER_CTOR;

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object enumConst(Class<?> cls, String name) {
    return Enum.valueOf((Class) cls, name);
  }

  static {
    try {
      MARKER_TYPE_CLS = Class.forName("com.motivewave.platform.sdk.common.Enums$MarkerType");
      SIZE_CLS = Class.forName("com.motivewave.platform.sdk.common.Enums$Size");
      POSITION_CLS = Class.forName("com.motivewave.platform.sdk.common.Enums$Position");
      CIRCLE = enumConst(MARKER_TYPE_CLS, "CIRCLE");
      ARROW = enumConst(MARKER_TYPE_CLS, "ARROW");
      MEDIUM = enumConst(SIZE_CLS, "MEDIUM");
      CENTER = enumConst(POSITION_CLS, "CENTER");
      TOP = enumConst(POSITION_CLS, "TOP");
      BOTTOM = enumConst(POSITION_CLS, "BOTTOM");
      MARKER_CTOR = Marker.class.getConstructor(
          Coordinate.class, MARKER_TYPE_CLS, SIZE_CLS, POSITION_CLS, Color.class, Color.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private MarkerAdapter() {}

  static Marker circle(long timeMs, double price, Color color) {
    try {
      return (Marker) MARKER_CTOR.newInstance(new Coordinate(timeMs, price), CIRCLE, MEDIUM, CENTER, color, color);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("failed to construct circle Marker", e);
    }
  }

  /**
   * ARROW MarkerType is one of the types isSupportsOrientation() reports
   * true for -- orientation comes from Position, not a separate
   * rotation/direction param (there isn't one on the Marker ctor).
   * pointingUp=true (long entry) uses BOTTOM (arrow sits under the price,
   * pointing up into it -- the conventional "buy" glyph); false (short
   * entry) uses TOP (sits above, pointing down -- "sell"). Unconfirmed
   * against the live chart yet; matches the standard buy/sell arrow
   * convention, flag to revisit if it renders upside down.
   */
  static Marker arrow(long timeMs, double price, Color color, boolean pointingUp) {
    try {
      Object position = pointingUp ? BOTTOM : TOP;
      return (Marker) MARKER_CTOR.newInstance(new Coordinate(timeMs, price), ARROW, MEDIUM, position, color, color);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("failed to construct arrow Marker", e);
    }
  }
}
