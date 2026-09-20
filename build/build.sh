#!/usr/bin/env bash
# Compiles flow-core WITHOUT mwave_sdk.jar (D-09 -- a strategy importing
# com.motivewave.* fails right here, not at review time), then flow-runtime
# WITH the SDK jar + flow-core's classes, runs the safety reflection test
# and refuses to deploy if it fails, then deploys both class trees.
set -e
cd "$(dirname "$0")/.."   # repo root

JAVAC="../motivewave/tools/jdk-26.0.2.1+1/bin/javac.exe"
JAVA="../motivewave/tools/jdk-26.0.2.1+1/bin/java.exe"
SDKJAR="C:/Program Files (x86)/MotiveWave/lib/mwave_sdk.jar"
EXT_DIR="/c/Users/MSI/MotiveWave Extensions"
DEV_DIR="$EXT_DIR/dev"

echo "== flow-core (no SDK on classpath) =="
rm -rf build/classes/core
mkdir -p build/classes/core
"$JAVAC" -encoding UTF-8 -d build/classes/core $(find flow-core/src -name "*.java")

echo "== trigger evaluator synthetic test =="
"$JAVA" -cp build/classes/core com.flow.core.TriggerEvaluatorTest
echo "(trigger test passed -- see output above)"

echo "== market structure feature synthetic test =="
"$JAVA" -cp build/classes/core com.flow.flow.MarketStructureFeatureTest
echo "(market structure test passed -- see output above)"

echo "== session boundary synthetic test =="
"$JAVA" -cp build/classes/core com.flow.core.SessionBoundaryTest
echo "(session boundary test passed -- see output above)"

echo "== session-reset wiring synthetic test (RiskChain + VWAPFeature) =="
"$JAVA" -cp build/classes/core com.flow.core.SessionResetWiringTest
echo "(session-reset wiring test passed -- see output above)"

echo "== liquidity map book-imbalance synthetic test =="
"$JAVA" -cp build/classes/core com.flow.flow.LiquidityMapFeatureTest
echo "(liquidity map test passed -- see output above)"

echo "== log retention synthetic test =="
"$JAVA" -cp build/classes/core com.flow.journal.LogRetentionTest
echo "(log retention test passed -- see output above)"

echo "== flow-runtime (SDK + flow-core) =="
rm -rf build/classes/runtime
mkdir -p build/classes/runtime
"$JAVAC" -encoding UTF-8 -cp "$SDKJAR;build/classes/core" -d build/classes/runtime $(find flow-runtime/src -name "*.java")

echo "== safety reflection test =="
"$JAVA" -cp "$SDKJAR;build/classes/core;build/classes/runtime" com.flow.rt.SafetyHookReflectionTest
echo "(safety test passed -- see output above)"

echo "== deploy =="
rm -rf "$DEV_DIR"
mkdir -p "$DEV_DIR"
cp -r build/classes/core/* "$DEV_DIR/"
cp -r build/classes/runtime/* "$DEV_DIR/"
touch "$EXT_DIR/.last_updated"
echo "Deployed to $DEV_DIR"
