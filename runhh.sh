#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

APP_COMPONENT="com.zebra.rfid.demo.sdksample/.MainActivity"
OPERATE_WHILE_CHARGING_ACTION="rfid.intent.action.OPERATE_WHILE_CHARGING"
JAVA_HOME_DEFAULT="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
GRADLE_TASKS=(:app:assembleDebug :app:installDebug)

SHOW_HELP=0
DO_CLEAN=0
SKIP_BUILD=0
SKIP_INSTALL=0
DEVICE_SERIAL="${ANDROID_SERIAL:-}"

usage() {
  cat <<'EOF'
Usage: ./runhh.sh [options]

Automates Android debug build + install + launch.

Options:
  -s, --serial <serial>   Target a specific adb device serial
  --clean                 Run ./gradlew clean before building
  --skip-build            Skip gradle build/install steps
  --skip-install          Build only, skip install step
  -h, --help              Show this help

Environment variables:
  JAVA_HOME               Override Java home (default uses Android Studio JBR)
  ANDROID_SERIAL          Default target device serial (same as -s)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      SHOW_HELP=1
      shift
      ;;
    --clean)
      DO_CLEAN=1
      shift
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --skip-install)
      SKIP_INSTALL=1
      shift
      ;;
    -s|--serial)
      if [[ $# -lt 2 ]]; then
        echo "Error: missing value for $1"
        usage
        exit 2
      fi
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    *)
      echo "Error: unknown argument: $1"
      usage
      exit 2
      ;;
  esac
done

if [[ "$SHOW_HELP" -eq 1 ]]; then
  usage
  exit 0
fi

export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -d "$JAVA_HOME" ]]; then
  echo "Error: JAVA_HOME directory does not exist: $JAVA_HOME"
  echo "Tip: set JAVA_HOME to a valid JDK/JBR 17+ path"
  exit 1
fi

if [[ ! -x "./gradlew" ]]; then
  chmod +x ./gradlew
fi

if ! command -v adb >/dev/null 2>&1; then
  if [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
    export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
  fi
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb not found in PATH"
  echo "Tip: install Android SDK platform-tools and add it to PATH"
  exit 1
fi

adb start-server >/dev/null

if [[ -n "$DEVICE_SERIAL" ]]; then
  if ! adb devices | awk 'NR>1 {print $1}' | grep -qx "$DEVICE_SERIAL"; then
    echo "Error: device serial not found: $DEVICE_SERIAL"
    echo "Connected devices:"
    adb devices -l
    exit 1
  fi

  DEVICE_STATE=$(adb -s "$DEVICE_SERIAL" get-state 2>/dev/null || true)
  if [[ "$DEVICE_STATE" != "device" ]]; then
    echo "Error: device $DEVICE_SERIAL is not ready (state=$DEVICE_STATE)"
    adb devices -l
    exit 1
  fi
  ADB_TARGET=(adb -s "$DEVICE_SERIAL")
else
  DEVICE_COUNT=$(adb devices | awk 'NR>1 {for (i=1; i<=NF; i++) if ($i=="device") {count++; break}} END{print count+0}')
  if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "Error: no connected Android device found in 'device' state"
    echo "adb devices output:"
    adb devices -l
    echo "Tip: authorize USB debugging on the device and check cable/USB mode"
    exit 1
  fi

  if [[ "$DEVICE_COUNT" -gt 1 ]]; then
    echo "Error: multiple devices detected. Use --serial <serial> to target one."
    adb devices -l
    exit 1
  fi
  ADB_TARGET=(adb)
fi

echo "Using JAVA_HOME=$JAVA_HOME"
echo "Using adb=$(command -v adb)"

if [[ "$DO_CLEAN" -eq 1 ]]; then
  echo "Running clean..."
  ./gradlew clean
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  if [[ "$SKIP_INSTALL" -eq 1 ]]; then
    echo "Building debug APK (install skipped)..."
    ./gradlew :app:assembleDebug
  else
    echo "Building + installing debug APK..."
    ./gradlew "${GRADLE_TASKS[@]}"
  fi
else
  echo "Skipping build/install steps by request"
fi

if [[ "$SKIP_INSTALL" -eq 0 ]]; then
  echo "Launching $APP_COMPONENT"
  "${ADB_TARGET[@]}" shell am start -n "$APP_COMPONENT"
  echo "Enabling operate-while-charging"
  "${ADB_TARGET[@]}" shell am broadcast -a "$OPERATE_WHILE_CHARGING_ACTION" --ez ON 1

  APP_PID=$("${ADB_TARGET[@]}" shell pidof com.zebra.rfid.demo.sdksample 2>/dev/null | tr -d '\r' || true)
  if [[ -n "$APP_PID" ]]; then
    echo "App process started (pid=$APP_PID)"
  else
    echo "Warning: launch command sent but app pid not found yet"
  fi
else
  echo "Install/launch skipped by request"
fi

echo "Done."
