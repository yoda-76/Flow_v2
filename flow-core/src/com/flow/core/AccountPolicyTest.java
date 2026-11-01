package com.flow.core;

/** The Simulated-only rule's classifier (2026-09-28). */
public final class AccountPolicyTest {
  private static int failures = 0;

  private static void check(String label, Object got, Object want) {
    boolean ok = want.equals(got);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + label + ": got " + got + ", want " + want);
    } else {
      System.out.println("OK   " + label);
    }
  }

  public static void main(String[] args) {
    check("'simulated' is the simulated account", AccountPolicy.classify("simulated"), AccountPolicy.Kind.SIMULATED);
    check("case does not matter", AccountPolicy.classify("SIMULATED"), AccountPolicy.Kind.SIMULATED);
    check("surrounding spaces do not matter", AccountPolicy.classify("  Simulated "), AccountPolicy.Kind.SIMULATED);
    check("a Rithmic account name is OTHER", AccountPolicy.classify("LFE025-R46U7LT7-TEST001"), AccountPolicy.Kind.OTHER);
    check("a name merely containing 'sim' is OTHER (exact match only)", AccountPolicy.classify("simulated2"), AccountPolicy.Kind.OTHER);
    check("'sim' alone is OTHER", AccountPolicy.classify("sim"), AccountPolicy.Kind.OTHER);
    check("null is UNKNOWN", AccountPolicy.classify(null), AccountPolicy.Kind.UNKNOWN);
    check("blank is UNKNOWN", AccountPolicy.classify("   "), AccountPolicy.Kind.UNKNOWN);
    // 2026-11-02 (forwardTest_plan_v1.md): extra Simulated accounts from config
    check("before configure, an extra Sim name is OTHER", AccountPolicy.classify("FT-S1"), AccountPolicy.Kind.OTHER);
    AccountPolicy.configure(java.util.List.of(" FT-S1 ", "ft-av", "", "  "));
    check("a configured id is SIMULATED", AccountPolicy.classify("FT-S1"), AccountPolicy.Kind.SIMULATED);
    check("configured ids ignore case/spaces", AccountPolicy.classify(" FT-AV "), AccountPolicy.Kind.SIMULATED);
    check("the built-in id stays allowed", AccountPolicy.classify("simulated"), AccountPolicy.Kind.SIMULATED);
    check("an unlisted name is still OTHER", AccountPolicy.classify("FT-S2"), AccountPolicy.Kind.OTHER);
    check("a listed name's prefix is still OTHER (exact match only)", AccountPolicy.classify("FT-S"), AccountPolicy.Kind.OTHER);
    check("a real account name is still OTHER", AccountPolicy.classify("LFE025-R46U7LT7-TEST001"), AccountPolicy.Kind.OTHER);
    check("blank entries were dropped", AccountPolicy.allowedIds().size() == 3, true);
    AccountPolicy.configure(null);
    check("configure(null) resets to the built-in id only", AccountPolicy.classify("FT-S1"), AccountPolicy.Kind.OTHER);
    check("...which stays allowed", AccountPolicy.classify("simulated"), AccountPolicy.Kind.SIMULATED);
    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: all AccountPolicy checks passed.");
  }
}
