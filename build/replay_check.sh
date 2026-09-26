#!/usr/bin/env bash
# Runs the replay-equivalence test against a recorded session directory.
# Needs no SDK jar -- flow-core (and this test) compiles and runs under a
# plain JDK, per README "Testing".
set -e
cd "$(dirname "$0")/.."   # repo root

source build/env.sh   # JDK location: env-var overrides, today's path by default
problem="$(flow_env_problem)"
if [ -n "$problem" ]; then
  echo "replay_check.sh: $problem" >&2
  exit 2
fi

if [ -z "$1" ]; then
  echo "usage: $0 <sessionDir>" >&2
  echo "example sessions under logs/:" >&2
  ls -1 logs 2>/dev/null >&2
  exit 2
fi

"$JAVA" -cp "build/classes/core" com.flow.core.ReplayEquivalenceTest "$1"
