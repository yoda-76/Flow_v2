# Sourced by build.sh and replay_check.sh (after they cd to the repo root): where the JDK, the MotiveWave SDK jar
# and the MotiveWave extensions folder are on THIS machine. Every value can be overridden by an environment
# variable; with none set the result is exactly what these scripts hard-coded before (D-98/D-100), so nothing
# changes on the dev machine.
#
#   FLOW_JDK_BIN        directory holding javac/java (javac.exe/java.exe on Windows).
#                       Default: ../motivewave/tools/jdk-26.0.2.1+1/bin; if that default does not exist,
#                       falls back to javac/java on PATH. If you SET it, it is used or it is an error --
#                       never a silent fall-back to some other JDK.
#   MWAVE_SDK_JAR       path to mwave_sdk.jar.   Default: C:/Program Files (x86)/MotiveWave/lib/mwave_sdk.jar
#   MOTIVEWAVE_EXT_DIR  MotiveWave's "MotiveWave Extensions" folder (the deploy target's parent).
#                       Default: /c/Users/MSI/MotiveWave Extensions
#
# Sets: JAVAC JAVA SDKJAR EXT_DIR SEP (classpath separator: ';' on Windows shells, ':' elsewhere)
# and the function flow_env_problem, which prints what is unusable ("" when all is well). This file only
# resolves; it never fails or exits by itself, so it can be sourced and tested safely.

_flow_pick_tool() { # $1 = javac | java  ->  prints the executable path, or nothing
  local dir="${FLOW_JDK_BIN:-../motivewave/tools/jdk-26.0.2.1+1/bin}"
  if [ -x "$dir/$1.exe" ]; then
    echo "$dir/$1.exe"
  elif [ -x "$dir/$1" ]; then
    echo "$dir/$1"
  elif [ -z "${FLOW_JDK_BIN:-}" ] && command -v "$1" >/dev/null 2>&1; then
    command -v "$1"
  fi
}

JAVAC="$(_flow_pick_tool javac)"
JAVA="$(_flow_pick_tool java)"
SDKJAR="${MWAVE_SDK_JAR:-C:/Program Files (x86)/MotiveWave/lib/mwave_sdk.jar}"
EXT_DIR="${MOTIVEWAVE_EXT_DIR:-/c/Users/MSI/MotiveWave Extensions}"
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
  *) SEP=":" ;;
esac

# $1 = "sdk" to also require the SDK jar and the extensions folder (build.sh); anything else = JDK only.
flow_env_problem() {
  if [ -z "$JAVAC" ] || [ -z "$JAVA" ]; then
    if [ -n "${FLOW_JDK_BIN:-}" ]; then
      echo "FLOW_JDK_BIN=$FLOW_JDK_BIN has no javac/java"
    else
      echo "no JDK found (looked in ../motivewave/tools/jdk-26.0.2.1+1/bin and on PATH); set FLOW_JDK_BIN"
    fi
    return
  fi
  if [ "$1" = "sdk" ]; then
    if [ ! -f "$SDKJAR" ]; then
      echo "mwave_sdk.jar not found at '$SDKJAR'; set MWAVE_SDK_JAR"
      return
    fi
    if [ ! -d "$EXT_DIR" ]; then
      echo "MotiveWave extensions folder not found at '$EXT_DIR'; set MOTIVEWAVE_EXT_DIR"
      return
    fi
  fi
}
