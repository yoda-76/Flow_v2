package com.flow.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ExternalConfig's layering (D-106): config/risk.json with an optional per-machine config/risk.local.json laid over
 * it, and the logRetentionHours key. Plain main(), nonzero exit on failure, wired into build/build.sh.
 */
public final class ExternalConfigTest {
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

  private static Path write(Path dir, String name, String json) throws Exception {
    Path f = dir.resolve(name);
    Files.writeString(f, json);
    return f;
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("flow_extcfg_test");
    try {
      Path base = write(dir, "risk.json", "{\"fixedContracts\": 1, \"dailyLossLimitTicks\": 200, \"flattenLeadMinutes\": 5}");
      Path local = dir.resolve("risk.local.json");

      // The new key: default keeps everything; the file can set it; nonsense is clamped to "keep".
      checkEq("logRetentionHours defaults to 0 (keep everything)", ExternalConfig.empty().logRetentionHours(), 0);
      checkEq("plain load: absent key -> 0", ExternalConfig.load(base).logRetentionHours(), 0);
      Path withKey = write(dir, "with.json", "{\"logRetentionHours\": 48}");
      checkEq("plain load: the key is read", ExternalConfig.load(withKey).logRetentionHours(), 48);
      Path neg = write(dir, "neg.json", "{\"logRetentionHours\": -5}");
      checkEq("a negative value is treated as 0 (keep), never as a huge window", ExternalConfig.load(neg).logRetentionHours(), 0);

      // No override file -> exactly the base, nothing reported as overridden.
      ExternalConfig none = ExternalConfig.loadLayered(base, local);
      checkEq("no override file: base value used", none.dailyLossLimitTicks(), 200);
      checkEq("no override file: nothing overridden", none.localOverrideKeys().size(), 0);
      checkEq("no override file: no override mtime", none.localOverrideLastModifiedMs(), 0L);
      checkEq("no override file: no error", none.localOverrideError(), null);
      checkEq("null override path behaves the same", ExternalConfig.loadLayered(base, null).dailyLossLimitTicks(), 200);

      // An override wins for the keys it names, and only those.
      write(dir, "risk.local.json", "{\"logRetentionHours\": 48, \"dailyLossLimitTicks\": 150}");
      ExternalConfig lay = ExternalConfig.loadLayered(base, local);
      checkEq("override wins: logRetentionHours", lay.logRetentionHours(), 48);
      checkEq("override wins: dailyLossLimitTicks", lay.dailyLossLimitTicks(), 150);
      checkEq("keys the override does not name keep the base value", lay.flattenLeadMinutes(), 5);
      checkEq("keys neither file names keep their default", lay.rateLimitPerMinute(), 6);
      checkEq("the overridden keys are reported, sorted", lay.localOverrideKeys().toString(),
          "[dailyLossLimitTicks, logRetentionHours]");
      checkEq("the override file's mtime is reported", lay.localOverrideLastModifiedMs() > 0, true);
      checkEq("the base file's mtime is still the base's", lay.fileLastModifiedMs(), Files.getLastModifiedTime(base).toMillis());
      checkEq("no error when the override is fine", lay.localOverrideError(), null);

      // Unknown keys in the override are ignored, like everywhere else -- and are not reported as overrides.
      write(dir, "risk.local.json", "{\"logRetentionHour\": 48, \"maxContracts\": 1}");
      ExternalConfig typo = ExternalConfig.loadLayered(base, local);
      checkEq("a misspelt key is ignored (documented: unknown keys are silent)", typo.logRetentionHours(), 0);
      checkEq("only known keys are reported as overridden", typo.localOverrideKeys().toString(), "[maxContracts]");

      // A broken override must not stop the session, and must not be silent: base applies, the error is exposed.
      write(dir, "risk.local.json", "{\"logRetentionHours\": \"48\"");
      ExternalConfig broken = ExternalConfig.loadLayered(base, local);
      checkEq("unreadable override: the base values apply", broken.dailyLossLimitTicks(), 200);
      checkEq("unreadable override: retention falls back to keep-everything", broken.logRetentionHours(), 0);
      checkEq("unreadable override: nothing reported as overridden", broken.localOverrideKeys().size(), 0);
      checkEq("unreadable override: the error is exposed", broken.localOverrideError() != null, true);
      // ...and all-or-nothing: a good key that PRECEDES a bad value must not be applied on its own.
      write(dir, "risk.local.json", "{\"dailyLossLimitTicks\": 150, \"logRetentionHours\": 4.5}");
      ExternalConfig partial = ExternalConfig.loadLayered(base, local);
      checkEq("a broken override applies NONE of its keys (dailyLossLimitTicks stays at the base 200)", partial.dailyLossLimitTicks(), 200);
      checkEq("a broken override reports no overridden keys", partial.localOverrideKeys().size(), 0);
      write(dir, "risk.local.json", "{\"logRetentionHours\": 4.5}");
      checkEq("a non-integer override value is an error too, not a silent 4",
          ExternalConfig.loadLayered(base, local).localOverrideError() != null, true);

      // The base is still required, exactly as before.
      boolean threw = false;
      try {
        ExternalConfig.loadLayered(dir.resolve("nope.json"), local);
      } catch (java.io.IOException e) {
        threw = true;
      }
      checkEq("a missing base file still throws IOException (the runtime logs RISK_CONFIG_MISSING)", threw, true);

      // Path helpers.
      Path root = Path.of("D:/flow").toAbsolutePath().normalize();
      checkEq("risk.local.json lives next to risk.json", FlowHome.riskLocalConfig(root),
          root.resolve("config").resolve("risk.local.json"));
    } finally {
      try (var s = Files.list(dir)) { for (Path p : (Iterable<Path>) s::iterator) Files.deleteIfExists(p); }
      Files.deleteIfExists(dir);
    }

    if (failures > 0) {
      System.err.println(failures + " FAILURE(S)");
      System.exit(1);
    }
    System.out.println("PASS: ExternalConfig layering checks passed.");
  }
}
