package com.flow.core;

import com.flow.journal.ConstructDataStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 2026-09-27 (laptop trial): the instrument guard ("for now only GC is allowed") and the per-instrument data
 * folders that fix findings F-1. Plain main(), nonzero exit on failure, wired into build/build.sh.
 */
public final class InstrumentAndDataLayoutTest {
  private static int failures = 0;

  private static void checkEq(String label, Object got, Object want) {
    boolean ok = java.util.Objects.equals(got, want);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) throws Exception {
    testPolicy();
    testSymbolDir();
    testStoreWritesIntoTheSymbolFolder();
    testOldFlatLayoutUnchanged();
    testPruneReachesSymbolFolders();

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: instrument policy and data layout checks passed.");
  }

  private static void testPolicy() {
    checkEq("continuous gold @GC is allowed", InstrumentPolicy.refuseReason("@GC", 0.1), null);
    checkEq("an explicit gold contract GCZ6 is allowed", InstrumentPolicy.refuseReason("GCZ6", 0.1), null);
    checkEq("a two-digit-year contract GCG27 is allowed", InstrumentPolicy.refuseReason("GCG27", 0.1), null);
    checkEq("lower case / padded still gold", InstrumentPolicy.refuseReason(" @gc ", 0.1), null);
    checkEq("ES (the laptop mistake) is refused", InstrumentPolicy.refuseReason("ESZ6", 0.25) != null, true);
    checkEq("@ES is refused", InstrumentPolicy.refuseReason("@ES", 0.25) != null, true);
    checkEq("micro gold MGC is refused (a different contract: not GC)", InstrumentPolicy.refuseReason("MGCZ6", 0.1) != null, true);
    checkEq("silver SI is refused", InstrumentPolicy.refuseReason("@SI", 0.005) != null, true);
    checkEq("gold with the wrong tick size is refused", InstrumentPolicy.refuseReason("@GC", 0.25) != null, true);
    checkEq("a null symbol is refused", InstrumentPolicy.refuseReason(null, 0.1) != null, true);
    checkEq("garbage is refused", InstrumentPolicy.refuseReason("GC-foo", 0.1) != null, true);
    checkEq("the refusal names the instrument", InstrumentPolicy.refuseReason("ESZ6", 0.25).contains("ESZ6"), true);
  }

  private static void testSymbolDir() {
    checkEq("@GC -> GC", InstrumentPolicy.symbolDir("@GC"), "GC");
    checkEq("GCZ6 -> GCZ6", InstrumentPolicy.symbolDir("GCZ6"), "GCZ6");
    checkEq("unsafe characters become _", InstrumentPolicy.symbolDir("A/B:C"), "A_B_C");
    checkEq("null -> unknown", InstrumentPolicy.symbolDir(null), "unknown");
    checkEq("just @ -> unknown", InstrumentPolicy.symbolDir("@"), "unknown");
  }

  private static void testStoreWritesIntoTheSymbolFolder() throws Exception {
    Path root = Files.createTempDirectory("flow_v2_layout");
    ConstructDataStore gold = new ConstructDataStore(root, "GC");
    ConstructDataStore es = new ConstructDataStore(root, "ESZ6");
    gold.start();
    es.start();
    gold.write("liquidity_map", 20722, "{\"t\":1}");
    es.write("liquidity_map", 20722, "{\"t\":2}");
    gold.flushAndClose();
    es.flushAndClose();
    checkEq("F-1: gold's day file is in data/<construct>/GC/", Files.readAllLines(root.resolve("liquidity_map/GC/20722.jsonl")),
        List.of("{\"t\":1}"));
    checkEq("F-1: another instrument on the same day gets its own file", Files.readAllLines(root.resolve("liquidity_map/ESZ6/20722.jsonl")),
        List.of("{\"t\":2}"));
    checkEq("F-1: nothing is written to the old shared path", Files.exists(root.resolve("liquidity_map/20722.jsonl")), false);

    // The recorder's header names the instrument.
    Path r2 = Files.createTempDirectory("flow_v2_layout_hdr");
    ConstructDataStore st = new ConstructDataStore(r2, "GC");
    st.start();
    DataRecorder rec = new DataRecorder(st, java.util.Map.of(), t -> t * 0.1, 1, 1, 7);
    long t0 = 1_790_000_000_000L;
    rec.onEvent(new ClockEvent(1, t0, t0));
    rec.onEvent(new TickEvent(2, t0 + 100, t0 + 100, 10, 1, true, 10, 10, 0L, 0L));
    rec.onEvent(new ClockEvent(3, t0 + 1000, t0 + 1000));
    st.flushAndClose();
    long sid = SessionBoundary.sessionIdFor(t0 + 1000);
    List<String> fp = Files.readAllLines(r2.resolve("footprint/GC/" + sid + ".jsonl"));
    checkEq("F-1: the header line carries the symbol", fp.get(0).contains("\"symbol\":\"GC\""), true);
  }

  private static void testOldFlatLayoutUnchanged() throws Exception {
    Path root = Files.createTempDirectory("flow_v2_layout_old");
    ConstructDataStore old = new ConstructDataStore(root);
    old.start();
    old.write("vwap", 7, "{\"t\":1}");
    old.flushAndClose();
    checkEq("the one-argument store keeps the old flat layout (replay, tests)", Files.exists(root.resolve("vwap/7.jsonl")), true);
    checkEq("...and says so", old.symbolDir(), null);
  }

  private static void testPruneReachesSymbolFolders() throws Exception {
    Path root = Files.createTempDirectory("flow_v2_layout_prune");
    for (String p : new String[] {"vwap/GC/100.jsonl", "vwap/GC/101.jsonl", "vwap/GC/102.jsonl", "vwap/ESZ6/100.jsonl",
        "vwap/99.jsonl", "liquidity_map/GC/102.jsonl"}) {
      Path f = root.resolve(p);
      Files.createDirectories(f.getParent());
      Files.writeString(f, "x");
    }
    int deleted = ConstructDataStore.pruneNow(root, 2, 102);    // keep days 101 and 102
    checkEq("prune: old days inside symbol folders are deleted", Files.exists(root.resolve("vwap/GC/100.jsonl")), false);
    checkEq("prune: ...for every instrument", Files.exists(root.resolve("vwap/ESZ6/100.jsonl")), false);
    checkEq("prune: an old flat-layout file is still pruned", Files.exists(root.resolve("vwap/99.jsonl")), false);
    checkEq("prune: kept days survive", Files.exists(root.resolve("vwap/GC/101.jsonl"))
        && Files.exists(root.resolve("vwap/GC/102.jsonl")) && Files.exists(root.resolve("liquidity_map/GC/102.jsonl")), true);
    checkEq("prune: three files deleted", deleted, 3);
    checkEq("prune: the symbol folders themselves are left in place", Files.isDirectory(root.resolve("vwap/ESZ6")), true);
  }
}
