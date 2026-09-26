package com.flow.flow;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * VolumeProfileMath on small profiles worked out by hand (D-104). The math was moved verbatim out of the
 * SDK-bound feature; these cases pin every rule of it so a later edit cannot change POC / value-area /
 * HVN-LVN behaviour unnoticed, and RecordingReplayTest then checks it against real live output.
 * Plain main(), nonzero exit on failure, wired into build/build.sh.
 */
public final class VolumeProfileMathTest {
  private static int failures = 0;

  private static void checkEq(String label, Object got, Object want) {
    boolean ok = want == null ? got == null : want.equals(got);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  /** key -> total volume (all traded at the ask; delta == volume). */
  private static TreeMap<Integer, double[]> profile(int... keyVolPairs) {
    TreeMap<Integer, double[]> m = new TreeMap<>();
    for (int i = 0; i < keyVolPairs.length; i += 2) m.put(keyVolPairs[i], new double[]{keyVolPairs[i + 1], 0});
    return m;
  }

  public static void main(String[] args) {
    // Empty: nothing to compute.
    checkEq("empty profile -> null", VolumeProfileMath.compute(new TreeMap<>()), null);

    // One bucket: it is POC, VAH and VAL; no HVN/LVN (fewer than 3 buckets).
    VolumeProfileMath.Result one = VolumeProfileMath.compute(profile(5, 10));
    checkEq("single bucket: poc/vah/val all that bucket", List.of(one.poc(), one.vah(), one.val()), List.of(5, 5, 5));
    checkEq("single bucket: no HVN/LVN", List.of(one.lvnKeys().size(), one.hvnKeys().size()), List.of(0, 0));

    // Delta and total: ask minus bid, ask plus bid.
    TreeMap<Integer, double[]> ab = new TreeMap<>();
    ab.put(0, new double[]{7, 3});
    ab.put(1, new double[]{2, 8});
    VolumeProfileMath.Result d = VolumeProfileMath.compute(ab);
    checkEq("total volume is ask + bid", d.totalVolume(), 20.0);
    checkEq("delta is ask - bid", d.totalDelta(), (7.0 - 3) + (2.0 - 8));

    // POC tie: the FIRST (lowest) maximum wins (strict >).
    VolumeProfileMath.Result tie = VolumeProfileMath.compute(profile(0, 10, 1, 10, 2, 1));
    checkEq("POC tie goes to the lowest key", tie.poc(), 0);

    // Value area: 10 buckets 0..9 with volumes 1,2,3,4,10,4,3,2,1,1 (total 31, 70% = 21.7).
    // POC = key 4 (10). Expand: below=4(key3) vs above=4(key5): tie -> above (>=) -> hi=5 acc=14;
    // below=4(key3) vs above=3(key6): below -> lo=3 acc=18; below=3(key2) vs above=3(key6): tie -> above -> hi=6 acc=21;
    // below=3(key2) vs above=2(key7): below -> lo=2 acc=24 >= 21.7 stop. VAL=2, VAH=6.
    VolumeProfileMath.Result va = VolumeProfileMath.compute(
        profile(0, 1, 1, 2, 2, 3, 3, 4, 4, 10, 5, 4, 6, 3, 7, 2, 8, 1, 9, 1));
    checkEq("value area: POC", va.poc(), 4);
    checkEq("value area: VAL (expansion prefers the side with more volume)", va.val(), 2);
    checkEq("value area: VAH (a tie expands upward)", va.vah(), 6);

    // A tie must decide the RESULT, not just the path: POC key 2 (10) with equal neighbours (4, 4); total 19,
    // target 13.3, so ONE step reaches it. Up on a tie -> VAL 2 / VAH 3; down would give VAL 1 / VAH 2.
    VolumeProfileMath.Result tieVa = VolumeProfileMath.compute(profile(0, 1, 1, 4, 2, 10, 3, 4, 4, 0));
    checkEq("tie when one step suffices: expands UP (VAH is the upper neighbour)", List.of(tieVa.val(), tieVa.vah()), List.of(2, 3));

    // Expansion stops at the profile's edge instead of running off it.
    VolumeProfileMath.Result edge = VolumeProfileMath.compute(profile(0, 100, 1, 1, 2, 1));
    checkEq("value area at the lower edge: VAL stays at the POC", edge.val(), 0);

    // 70% is reached exactly -> stop (acc < target is strict): total 10, target 7, POC alone has 7.
    VolumeProfileMath.Result exact = VolumeProfileMath.compute(profile(0, 1, 1, 7, 2, 2));
    checkEq("target met exactly by the POC alone: vah == val == poc",
        List.of(exact.val(), exact.poc(), exact.vah()), List.of(1, 1, 1));

    // HVN/LVN: a spike between quiet buckets. Volumes 10,10,50,10,10,2,10,10 keys 0..7. Window is 5 each side.
    // key 2 (50): neighbours avg (10+10+10+10+2+10)/... > 0 -> 50 >= 1.5*avg -> HVN.
    // key 5 (2): neighbours all ~10+ -> 2 <= 0.5*avg -> LVN.
    VolumeProfileMath.Result hl = VolumeProfileMath.compute(profile(0, 10, 1, 10, 2, 50, 3, 10, 4, 10, 5, 2, 6, 10, 7, 10));
    checkEq("HVN found at the spike", hl.hvnKeys(), List.of(2));
    checkEq("LVN found at the dip", hl.lvnKeys(), List.of(5));

    // Thresholds, tested just either side of each boundary. Three buckets [20, m, 20]: the middle bucket's
    // neighbours average exactly 20, so m/20 is its ratio.
    checkEq("ratio 1.55 (>= 1.5, < 1.6) is an HVN", VolumeProfileMath.compute(profile(0, 20, 1, 31, 2, 20)).hvnKeys().contains(1), true);
    checkEq("ratio 1.45 (< 1.5, > 1.4) is NOT an HVN", VolumeProfileMath.compute(profile(0, 20, 1, 29, 2, 20)).hvnKeys().contains(1), false);
    checkEq("ratio 0.45 (<= 0.5, < 0.6) is an LVN", VolumeProfileMath.compute(profile(0, 20, 1, 9, 2, 20)).lvnKeys().contains(1), true);
    checkEq("ratio 0.55 (> 0.5, < 0.6) is NOT an LVN", VolumeProfileMath.compute(profile(0, 20, 1, 11, 2, 20)).lvnKeys().contains(1), false);
    checkEq("ratio 0.35 (well under) is an LVN, not an HVN", List.of(
        VolumeProfileMath.compute(profile(0, 20, 1, 7, 2, 20)).lvnKeys().contains(1),
        VolumeProfileMath.compute(profile(0, 20, 1, 7, 2, 20)).hvnKeys().contains(1)), List.of(true, false));
    // Exactly on the boundaries: 30/20 = 1.5 is an HVN (>=); 10/20 = 0.5 is an LVN (<=).
    checkEq("ratio exactly 1.5 is an HVN (>=)", VolumeProfileMath.compute(profile(0, 20, 1, 30, 2, 20)).hvnKeys().contains(1), true);
    checkEq("ratio exactly 0.5 is an LVN (<=)", VolumeProfileMath.compute(profile(0, 20, 1, 10, 2, 20)).lvnKeys().contains(1), true);

    // Fewer than 3 buckets -> never any HVN/LVN, whatever the volumes.
    VolumeProfileMath.Result two = VolumeProfileMath.compute(profile(0, 100, 1, 1));
    checkEq("two buckets: no HVN/LVN", List.of(two.lvnKeys().size(), two.hvnKeys().size()), List.of(0, 0));

    // A bucket whose neighbours are all empty (avg == 0) but which has volume is an HVN (the avg==0 branch).
    VolumeProfileMath.Result lone = VolumeProfileMath.compute(profile(0, 0, 1, 0, 2, 5));
    checkEq("lone traded bucket among empty ones is an HVN", lone.hvnKeys(), List.of(2));

    // Inputs are not modified, and an unsorted map gives the same answer as a sorted one.
    // Keys chosen so a HashMap does NOT iterate them in ascending order (small consecutive ints would).
    Map<Integer, double[]> unsorted = new java.util.HashMap<>(
        profile(17, 4, -3, 30, 100, 9, 5, 12, 40, 2, 41, 6, 42, 5, 43, 7));
    List<Integer> hashOrder = new java.util.ArrayList<>(unsorted.keySet());
    List<Integer> ascending = new java.util.ArrayList<>(new TreeMap<>(unsorted).keySet());
    checkEq("precondition: the hash map really iterates out of key order", hashOrder.equals(ascending), false);
    checkEq("an unsorted (hash) map gives exactly the answer the sorted map gives",
        VolumeProfileMath.compute(unsorted), VolumeProfileMath.compute(new TreeMap<>(unsorted)));
    checkEq("the input map is left alone", unsorted.size(), 8);

    // cluster(): consecutive-by-rangeTicks runs.
    checkEq("cluster: runs of consecutive keys", asString(VolumeProfileMath.cluster(List.of(1, 2, 3, 7, 9, 10), 1)),
        "[1-3, 7-7, 9-10]");
    checkEq("cluster: rangeTicks 2 joins keys two apart", asString(VolumeProfileMath.cluster(List.of(0, 2, 4, 7), 2)),
        "[0-4, 7-7]");
    checkEq("cluster: empty in, empty out", VolumeProfileMath.cluster(List.of(), 1).size(), 0);
    checkEq("cluster: negative keys", asString(VolumeProfileMath.cluster(List.of(-3, -2, 0), 1)), "[-3--2, 0-0]");

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: VolumeProfileMath checks passed.");
  }

  private static String asString(List<int[]> clusters) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < clusters.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(clusters.get(i)[0]).append('-').append(clusters.get(i)[1]);
    }
    return sb.append(']').toString();
  }
}
