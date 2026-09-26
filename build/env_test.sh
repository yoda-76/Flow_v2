#!/usr/bin/env bash
# Tests build/env.sh's path resolution (D-100). Run from anywhere; exits nonzero on any failure. Touches nothing
# outside a temp dir.
#
# MUST pass on ANY machine, not just the dev one (a clean clone on another machine found the first version
# hard-coding this machine's ../motivewave layout, which failed build.sh's first gate everywhere else):
#   - checks that assert this machine's own layout run only if that layout exists, otherwise they print SKIP;
#   - the JDK the tests need is taken from FLOW_JDK_BIN if set, else the default folder, else javac on PATH.
# SKIPs are not failures, but a machine with no JDK at all skips most of it -- build.sh stops on its own, with a
# message naming FLOW_JDK_BIN, right after this test.
cd "$(dirname "$0")/.."   # repo root, as build.sh sees it
ENV_SH="$(pwd)/build/env.sh"
fail=0

check() { # label, got, want
  if [ "$2" = "$3" ]; then echo "OK   $1"; else echo "FAIL $1: got '$2', want '$3'"; fail=1; fi
}
check_match() { # label, got, substring
  case "$2" in *"$3"*) echo "OK   $1" ;; *) echo "FAIL $1: got '$2', want it to contain '$3'"; fail=1 ;; esac
}
skip() { echo "SKIP $1"; }

# resolve VAR=VALUE ... -- prints "JAVAC|JAVA|SDKJAR|EXT_DIR|SEP|problem-jdk|problem-sdk" from a clean environment
resolve() {
  ( unset FLOW_JDK_BIN MWAVE_SDK_JAR MOTIVEWAVE_EXT_DIR
    for kv in "$@"; do export "$kv"; done
    source "$ENV_SH"
    echo "$JAVAC|$JAVA|$SDKJAR|$EXT_DIR|$SEP|$(flow_env_problem)|$(flow_env_problem sdk)" )
}
field() { echo "$1" | cut -d'|' -f"$2"; }
name_of() { basename "$1" | sed 's/\.exe$//'; }   # 'javac' contains 'java', so compare whole file names

# The default JDK folder (this dev machine's layout), and a JDK folder we can use for the override tests.
DEV_JDK="$(cd ../motivewave/tools/jdk-26.0.2.1+1/bin 2>/dev/null && pwd)"
if [ -n "$FLOW_JDK_BIN" ] && { [ -x "$FLOW_JDK_BIN/javac" ] || [ -x "$FLOW_JDK_BIN/javac.exe" ]; }; then
  GOOD_JDK="$FLOW_JDK_BIN"
elif [ -n "$DEV_JDK" ]; then
  GOOD_JDK="$DEV_JDK"
elif command -v javac >/dev/null 2>&1; then
  GOOD_JDK="$(dirname "$(command -v javac)")"
else
  GOOD_JDK=""
fi
# The same folder in POSIX form, for use inside PATH (a Windows-style "C:/x" has a colon, which PATH would split on).
GOOD_JDK_POSIX="$( [ -n "$GOOD_JDK" ] && { cygpath -u "$GOOD_JDK" 2>/dev/null || echo "$GOOD_JDK"; } )"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 1. Nothing set. The SDK jar and extensions defaults are plain strings -> they must be unchanged everywhere.
r="$(resolve)"
check "defaults: SDK jar path unchanged" "$(field "$r" 3)" "C:/Program Files (x86)/MotiveWave/lib/mwave_sdk.jar"
check "defaults: extensions folder unchanged" "$(field "$r" 4)" "/c/Users/MSI/MotiveWave Extensions"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) want=";" ;; *) want=":" ;; esac
check "separator matches the OS" "$(field "$r" 5)" "$want"
# ...but the JDK default only resolves where the dev layout (../motivewave/tools/...) exists.
if [ -n "$DEV_JDK" ]; then
  check_match "defaults: javac is the portable JDK's" "$(field "$r" 1)" "motivewave/tools/jdk-26.0.2.1+1/bin/javac"
  check_match "defaults: java is the portable JDK's" "$(field "$r" 2)" "motivewave/tools/jdk-26.0.2.1+1/bin/java"
  check "defaults: the java tool is java" "$(name_of "$(field "$r" 2)")" "java"
  check "defaults: the javac tool is javac" "$(name_of "$(field "$r" 1)")" "javac"
  check "defaults: no JDK problem" "$(field "$r" 6)" ""
else
  skip "default-JDK checks: no ../motivewave/tools/jdk-26.0.2.1+1 here (not the dev machine's layout)"
fi

if [ -z "$GOOD_JDK" ]; then
  skip "every check that needs a real JDK: none found (set FLOW_JDK_BIN); build.sh will stop with that message"
else
  # 2. Overrides are honoured.
  r="$(resolve "FLOW_JDK_BIN=$GOOD_JDK" "MWAVE_SDK_JAR=$TMP/x.jar" "MOTIVEWAVE_EXT_DIR=$TMP/ext")"
  check_match "FLOW_JDK_BIN is used" "$(field "$r" 1)" "$GOOD_JDK/javac"
  check "MWAVE_SDK_JAR is used" "$(field "$r" 3)" "$TMP/x.jar"
  check "MOTIVEWAVE_EXT_DIR is used" "$(field "$r" 4)" "$TMP/ext"

  # 3. A missing SDK jar / extensions folder is a problem only when the SDK is needed (replay_check needs neither).
  check "missing SDK jar: fine without the sdk flag" "$(field "$r" 6)" ""
  check_match "missing SDK jar: reported with the sdk flag" "$(field "$r" 7)" "MWAVE_SDK_JAR"
  touch "$TMP/x.jar"
  r="$(resolve "FLOW_JDK_BIN=$GOOD_JDK" "MWAVE_SDK_JAR=$TMP/x.jar" "MOTIVEWAVE_EXT_DIR=$TMP/ext")"
  check_match "missing extensions folder: reported" "$(field "$r" 7)" "MOTIVEWAVE_EXT_DIR"
  mkdir "$TMP/ext"
  r="$(resolve "FLOW_JDK_BIN=$GOOD_JDK" "MWAVE_SDK_JAR=$TMP/x.jar" "MOTIVEWAVE_EXT_DIR=$TMP/ext")"
  check "all present: no problem" "$(field "$r" 7)" ""

  # 4. FLOW_JDK_BIN set but wrong is an error -- never a silent fall-back to another JDK on PATH.
  r="$( PATH="$GOOD_JDK_POSIX:$PATH" resolve "FLOW_JDK_BIN=$TMP/nojdk" )"
  check "bad FLOW_JDK_BIN: no javac chosen" "$(field "$r" 1)" ""
  check_match "bad FLOW_JDK_BIN: the problem names the variable" "$(field "$r" 6)" "FLOW_JDK_BIN"

  # 5. Default JDK location absent (run from a folder with no sibling repo) -> falls back to PATH.
  r="$( cd "$TMP" && PATH="$GOOD_JDK_POSIX:$PATH" resolve )"
  check_match "default missing: falls back to javac on PATH" "$(field "$r" 1)" "javac"
  check "default missing but on PATH: no problem" "$(field "$r" 6)" ""
fi

# 6. Nothing anywhere -> a clear problem, not an empty command later. (Needs no JDK.)
mkdir "$TMP/empty"
r="$( cd "$TMP" && PATH="$TMP/empty" resolve )"
check "no JDK anywhere: no javac" "$(field "$r" 1)" ""
check_match "no JDK anywhere: the problem says how to fix it" "$(field "$r" 6)" "FLOW_JDK_BIN"

if [ $fail -ne 0 ]; then echo "FAILURES"; exit 1; fi
echo "PASS: build environment resolution"
