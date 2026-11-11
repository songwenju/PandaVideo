#!/bin/sh
set -eu

MOBILE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

# Keep GUI and command-line builds on the same local toolchain and caches.
if [ -f "$MOBILE_ROOT/.xcode.env" ]; then
    . "$MOBILE_ROOT/.xcode.env"
fi

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21)
fi
export JAVA_HOME

if [ "$#" -eq 0 ]; then
    CONFIGURATION_NAME=${CONFIGURATION:-Debug}
    case "${SDK_NAME:-}" in
        iphoneos*) KOTLIN_TARGET=IosArm64 ;;
        *) KOTLIN_TARGET=IosSimulatorArm64 ;;
    esac
    set -- ":shared:assemble${CONFIGURATION_NAME}AppleFrameworkForXcode${KOTLIN_TARGET}"
fi

exec "$MOBILE_ROOT/gradlew" -p "$MOBILE_ROOT" "$@"
