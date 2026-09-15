#!/usr/bin/env bash
# Runs the replay-equivalence test against a recorded session directory.
# Needs no SDK jar -- flow-core (and this test) compiles and runs under a
# plain JDK, per README "Testing".
set -e
cd "$(dirname "$0")/.."   # repo root

JAVA="../motivewave/tools/jdk-26.0.2.1+1/bin/java.exe"

if [ -z "$1" ]; then
  echo "usage: $0 <sessionDir>" >&2
  echo "example sessions under logs/:" >&2
  ls -1 logs 2>/dev/null >&2
  exit 2
fi

"$JAVA" -cp "build/classes/core" com.flow.core.ReplayEquivalenceTest "$1"
