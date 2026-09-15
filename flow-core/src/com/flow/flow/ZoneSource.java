package com.flow.flow;

import java.util.List;

/** A feature that exposes zones of a given kind (D-38's NamedZone). */
public interface ZoneSource {
  List<ZoneView> zonesOfKind(ZoneView.Kind kind);
}
