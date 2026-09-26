package com.flow.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FlowHome path resolution (todo Phase 4). Pure logic plus one temp-dir check
 * that logFile() creates logs/. Plain main(), nonzero exit on failure, wired
 * into build/build.sh.
 */
public final class FlowHomeTest {
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

  private static Path abs(String s) { return Path.of(s).toAbsolutePath().normalize(); }

  public static void main(String[] args) throws Exception {
    // Nothing set: today's path, so an unconfigured machine behaves exactly as before.
    checkEq("default when nothing set", FlowHome.resolve(null, null), abs(FlowHome.DEFAULT));
    checkEq("default when both blank", FlowHome.resolve("  ", ""), abs(FlowHome.DEFAULT));
    checkEq("default is the historic path", FlowHome.DEFAULT, "C:/yadvendra/trading/FLOW_V2");

    // Env used when property is absent; property beats env.
    checkEq("env used", FlowHome.resolve(null, "D:/flow"), abs("D:/flow"));
    checkEq("blank property falls through to env", FlowHome.resolve(" ", "D:/flow"), abs("D:/flow"));
    checkEq("property beats env", FlowHome.resolve("E:/prop", "D:/env"), abs("E:/prop"));
    checkEq("value is trimmed", FlowHome.resolve("  E:/prop  ", null), abs("E:/prop"));

    // Relative values become absolute: MotiveWave's cwd is not ours to rely on.
    Path rel = FlowHome.resolve("some/where", null);
    checkEq("relative made absolute", rel.isAbsolute(), true);
    checkEq("dot segments normalised", FlowHome.resolve("D:/a/../flow", null), abs("D:/flow"));

    // Derived paths hang off the root exactly as the old literals did.
    Path root = abs("D:/flow");
    checkEq("logs dir", FlowHome.logs(root), root.resolve("logs"));
    checkEq("data dir", FlowHome.data(root), root.resolve("data"));
    checkEq("risk config", FlowHome.riskConfig(root), root.resolve("config").resolve("risk.json"));

    // Default root reproduces the three literals that used to be hard-coded.
    Path def = FlowHome.resolve(null, null);
    checkEq("default logs == old LOG_ROOT", FlowHome.logs(def), abs("C:/yadvendra/trading/FLOW_V2/logs"));
    checkEq("default data == old DATA_ROOT", FlowHome.data(def), abs("C:/yadvendra/trading/FLOW_V2/data"));
    checkEq("default risk == old RISK_CONFIG_PATH", FlowHome.riskConfig(def),
        abs("C:/yadvendra/trading/FLOW_V2/config/risk.json"));

    // logFile() creates logs/ (a fresh home would otherwise silently lose every feature log).
    Path tmp = Files.createTempDirectory("flowhome_test");
    try {
      String f = FlowHome.logFile(tmp, "vwap_feature.log");
      checkEq("logFile path", Path.of(f), tmp.resolve("logs").resolve("vwap_feature.log"));
      checkEq("logFile created logs/", Files.isDirectory(tmp.resolve("logs")), true);
      // Idempotent.
      FlowHome.logFile(tmp, "vwap_feature.log");
      checkEq("logFile idempotent", Files.isDirectory(tmp.resolve("logs")), true);
    } finally {
      Files.deleteIfExists(tmp.resolve("logs"));
      Files.deleteIfExists(tmp);
    }

    if (failures > 0) {
      System.out.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("ALL PASSED");
  }
}
