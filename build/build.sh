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

echo "== market structure backtest engine synthetic test =="
"$JAVA" -cp build/classes/core com.flow.backtest.MarketStructureBacktestTest
echo "(backtest engine test passed -- see output above)"

echo "== risk chain synthetic test =="
"$JAVA" -cp build/classes/core com.flow.core.RiskChainTest
echo "(risk chain test passed -- see output above)"

echo "== journal backpressure synthetic test =="
"$JAVA" -cp build/classes/core com.flow.journal.JournalBackpressureTest
echo "(journal backpressure test passed -- see output above)"

echo "== sequencer backpressure synthetic test =="
"$JAVA" -cp build/classes/core com.flow.core.SequencerTest
echo "(sequencer test passed -- see output above)"

echo "== pipeline exception boundary / kill switch synthetic test =="
"$JAVA" -cp build/classes/core com.flow.core.PipelineExceptionBoundaryTest
echo "(pipeline exception boundary test passed -- see output above)"

echo "== trading window clock test (D-92) =="
"$JAVA" -cp build/classes/core com.flow.core.TradingWindowTest
echo "(trading window test passed -- see output above)"

echo "== session-end flatten test (RiskChain + Pipeline, D-92) =="
"$JAVA" -cp build/classes/core com.flow.core.SessionEndTest
echo "(session-end test passed -- see output above)"

echo "== per-construct data recorder synthetic test (D-88) =="
"$JAVA" -cp build/classes/core com.flow.core.DataRecorderTest
echo "(data recorder test passed -- see output above)"

echo "== recording replay test (real 5-minute @GC recording, D-88) =="
"$JAVA" -cp build/classes/core com.flow.core.RecordingReplayTest flow-core/fixtures/recording_gc_20260924_5min
echo "(recording replay test passed -- see output above)"

echo "== replay-equivalence test (null_strategy fixture -- see flow-core/fixtures/replay_fixture_null_strategy/README.md for its degenerate-0-vs-0 caveat) =="
"$JAVA" -cp build/classes/core com.flow.core.ReplayEquivalenceTest flow-core/fixtures/replay_fixture_null_strategy
echo "(replay-equivalence test passed -- see output above)"

echo "== flow-runtime (SDK + flow-core) =="
rm -rf build/classes/runtime
mkdir -p build/classes/runtime
"$JAVAC" -encoding UTF-8 -cp "$SDKJAR;build/classes/core" -d build/classes/runtime $(find flow-runtime/src -name "*.java")

echo "== safety reflection test =="
"$JAVA" -cp "$SDKJAR;build/classes/core;build/classes/runtime" com.flow.rt.SafetyHookReflectionTest
echo "(safety test passed -- see output above)"

echo "== order gateway test against the fake broker (D-91, plumbingEdgeCases.md 13) =="
"$JAVA" -cp "$SDKJAR;build/classes/core;build/classes/runtime" com.flow.rt.OrderGatewayTest
echo "(order gateway test passed -- see output above)"

echo "== live order tracker test against the fake broker (D-91) =="
"$JAVA" -cp "$SDKJAR;build/classes/core;build/classes/runtime" com.flow.rt.LiveOrderTrackerTest
echo "(live order tracker test passed -- see output above)"

echo "== daily report tests (python, D-94) =="
if command -v python >/dev/null 2>&1; then
  python analysis/test_daily_report.py
  echo "(daily report tests passed -- see output above)"
else
  echo "(python not found -- daily report tests SKIPPED; the report is an offline tool and does not gate the deploy)"
fi

echo "== deploy =="
rm -rf "$DEV_DIR"
mkdir -p "$DEV_DIR"
cp -r build/classes/core/* "$DEV_DIR/"
cp -r build/classes/runtime/* "$DEV_DIR/"
touch "$EXT_DIR/.last_updated"
echo "Deployed to $DEV_DIR"
