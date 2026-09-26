package com.flow.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where FLOW_V2 lives on this machine: the one root every runtime path hangs
 * off (logs/, data/, config/risk.json). Replaces the hard-coded
 * "C:/yadvendra/trading/FLOW_V2/..." literals that were scattered through
 * flow-runtime (todo.md Phase 4, cloud readiness).
 *
 * Resolution order, first non-blank wins:
 *   1. JVM system property  flow.home   (-Dflow.home=... on MotiveWave's JVM)
 *   2. environment variable FLOW_HOME
 *   3. DEFAULT -- today's path on the dev machine, so nothing changes unless
 *      one of the two above is set.
 * The result is always absolute (MotiveWave's working directory is not ours
 * to rely on) and is resolved once, at first use: changing the variable needs
 * a MotiveWave restart, not just re-activating the study.
 *
 * Deliberately not a study setting: the loggers and the data store are built
 * deep inside features that have no access to the study's settings, and the
 * location is a property of the machine, not of one chart.
 */
public final class FlowHome {
  public static final String PROPERTY = "flow.home";
  public static final String ENV = "FLOW_HOME";
  public static final String DEFAULT = "C:/yadvendra/trading/FLOW_V2";

  private static final Path ROOT = resolve(System.getProperty(PROPERTY), System.getenv(ENV));

  private FlowHome() {}

  /** Pure resolution logic, split out so it can be tested without touching the process environment. */
  public static Path resolve(String property, String env) {
    String chosen = DEFAULT;
    if (property != null && !property.isBlank()) {
      chosen = property.trim();
    } else if (env != null && !env.isBlank()) {
      chosen = env.trim();
    }
    return Path.of(chosen).toAbsolutePath().normalize();
  }

  /** Which source won, for the activation log line -- so a wrong path is visible, not silent. */
  public static String source() {
    String p = System.getProperty(PROPERTY);
    if (p != null && !p.isBlank()) return "system property " + PROPERTY;
    String e = System.getenv(ENV);
    if (e != null && !e.isBlank()) return "environment variable " + ENV;
    return "built-in default";
  }

  public static Path root() { return ROOT; }
  public static Path logs() { return logs(ROOT); }
  public static Path data() { return data(ROOT); }
  public static Path riskConfig() { return riskConfig(ROOT); }
  public static Path riskLocalConfig() { return riskLocalConfig(ROOT); }

  public static Path logs(Path root) { return root.resolve("logs"); }
  public static Path data(Path root) { return root.resolve("data"); }
  public static Path riskConfig(Path root) { return root.resolve("config").resolve("risk.json"); }
  /** D-106: the optional per-machine override laid over risk.json (git-ignored). */
  public static Path riskLocalConfig(Path root) { return root.resolve("config").resolve("risk.local.json"); }

  /**
   * Path of a per-feature diagnostic log directly under logs/, creating logs/
   * first. The feature loggers treat a failed open as "no log" rather than an
   * error, so without this a home whose logs/ does not exist yet would silently
   * lose every feature log.
   */
  public static String logFile(String name) {
    return logFile(ROOT, name);
  }

  public static String logFile(Path root, String name) {
    Path dir = logs(root);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      // Fall through: the caller's own open will fail the same way and handle it as before.
    }
    return dir.resolve(name).toString();
  }
}
