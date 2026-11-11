#!/usr/bin/env bash
# Dual-platform verification for the mobile KMP module.
# Runs host tests, Android APK assembly and iOS simulator framework link.
# Usage: verify.sh [--clean]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
MOBILE_DIR="$REPO_ROOT/mobile"

if [ ! -f "$MOBILE_DIR/gradlew" ]; then
  echo "ERROR: mobile Gradle project not found at $MOBILE_DIR" >&2
  exit 1
fi
cd "$MOBILE_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ ! -d "$ANDROID_HOME" ]; then
  echo "ERROR: Android SDK not found at $ANDROID_HOME (set ANDROID_HOME)" >&2
  exit 1
fi
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

GRADLE="./gradlew --console=plain"
STEP=0
total_steps=4

run() {
  STEP=$((STEP + 1))
  echo ""
  echo "==> [$STEP/$total_steps] $*"
  "$@"
}

if [ "${1:-}" = "--clean" ]; then
  echo "==> Cleaning :shared before verification"
  $GRADLE :shared:clean
fi

# 1. Host (JVM) unit tests for the KMP shared module.
run $GRADLE :shared:testAndroidHostTest

RESULT_DIR="shared/build/test-results/testAndroidHostTest"
if [ ! -d "$RESULT_DIR" ]; then
  echo "ERROR: test result directory missing: $RESULT_DIR" >&2
  exit 1
fi
TESTS=0; FAILURES=0; ERRORS=0; SUITES=0
for xml in "$RESULT_DIR"/TEST-*.xml; do
  [ -e "$xml" ] || continue
  SUITES=$((SUITES + 1))
  TESTS=$((TESTS + $(sed -n 's/.* tests="\([0-9]*\)".*/\1/p' "$xml" | head -1)))
  FAILURES=$((FAILURES + $(sed -n 's/.* failures="\([0-9]*\)".*/\1/p' "$xml" | head -1)))
  ERRORS=$((ERRORS + $(sed -n 's/.* errors="\([0-9]*\)".*/\1/p' "$xml" | head -1)))
done
echo "    Host tests: $SUITES suites, $TESTS cases, failures=$FAILURES errors=$ERRORS"
if [ "$FAILURES" -ne 0 ] || [ "$ERRORS" -ne 0 ]; then
  echo "ERROR: host tests failed" >&2
  exit 1
fi

# 2. Android China debug APK.
run $GRADLE :app:assembleChinaDebug
APK="app/build/outputs/apk/china/debug/pandavideo.apk"
[ -f "$APK" ] || { echo "ERROR: APK not found at $APK" >&2; exit 1; }

# 3. iOS simulator arm64 framework (device target: linkDebugFrameworkIosArm64).
run $GRADLE :shared:linkDebugFrameworkIosSimulatorArm64
FW="shared/build/bin/iosSimulatorArm64/debugFramework/SuiXinKanShared.framework/SuiXinKanShared"
[ -f "$FW" ] || { echo "ERROR: iOS framework binary not found at $FW" >&2; exit 1; }

# 4. Artifact summary with timestamps so stale UP-TO-DATE outputs are visible.
echo ""
echo "==> [4/$total_steps] Verification passed"
echo "    Host tests : $SUITES suites / $TESTS cases, 0 failures"
echo "    Android APK: $MOBILE_DIR/$APK"
ls -l "$APK" | awk '{print "                 mtime:", $6, $7, $8, " size:", $5}'
echo "    iOS FW     : $MOBILE_DIR/$FW"
ls -l "$FW" | awk '{print "                 mtime:", $6, $7, $8, " size:", $5}'
echo ""
echo "Reminder: open iosApp/PandaVideo.xcodeproj and press Cmd+R to deploy the new framework to simulator/device."
