package com.flow.rt;

import com.motivewave.platform.sdk.order_mgmt.OrderContext;
import com.motivewave.platform.sdk.study.Study;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural safety test (D-14, CLAUDE.md hard rule): enumerates every
 * OrderContext-taking method on Study directly from the actual jar via
 * reflection, then asserts FlowRuntimeStudy declares its own override for
 * each one. This is deliberately not a hand-maintained list -- the whole
 * point (per CLAUDE.md: "keeps that true the day MotiveWave ships a new
 * hook") is that a new hook in a future SDK version fails this test
 * automatically instead of silently inheriting a possibly-dangerous
 * default, the way onEnterNow did on 2026-09-11.
 *
 * No JUnit on this classpath (portable JDK, no package manager) -- plain
 * main(), nonzero exit on failure, intended to gate build/deploy.sh.
 */
public final class SafetyHookReflectionTest {
  public static void main(String[] args) {
    List<Method> hooks = new ArrayList<>();
    for (Method m : Study.class.getMethods()) {
      Class<?>[] params = m.getParameterTypes();
      if (params.length > 0 && params[0] == OrderContext.class) {
        hooks.add(m);
      }
    }

    if (hooks.isEmpty()) {
      System.err.println("FAIL: found zero OrderContext-taking methods on Study via reflection "
          + "-- that means this test is broken, not that there is nothing to guard.");
      System.exit(1);
    }

    boolean allOk = true;
    for (Method hook : hooks) {
      try {
        FlowRuntimeStudy.class.getDeclaredMethod(hook.getName(), hook.getParameterTypes());
        System.out.println("OK   " + signature(hook));
      } catch (NoSuchMethodException e) {
        System.out.println("FAIL " + signature(hook) + " -- NOT overridden, would inherit Study's default");
        allOk = false;
      }
    }

    System.out.println(hooks.size() + " OrderContext-taking hooks found on Study.");
    if (!allOk) {
      System.err.println("FAIL: FlowRuntimeStudy is missing an override for at least one "
          + "OrderContext-taking hook. Do not deploy.");
      System.exit(1);
    }
    System.out.println("PASS: every OrderContext-taking hook on Study is explicitly "
        + "overridden by FlowRuntimeStudy.");
  }

  private static String signature(Method m) {
    StringBuilder sb = new StringBuilder(m.getName()).append('(');
    Class<?>[] p = m.getParameterTypes();
    for (int i = 0; i < p.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(p[i].getSimpleName());
    }
    return sb.append(')').toString();
  }
}
