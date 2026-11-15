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
    # Assembling the static framework does not copy Compose resources into the app.
    # Sync them using Xcode's target environment for both simulator and device builds;
    # otherwise painterResource succeeds at compile time but crashes when first rendered.
    if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then
        # A prebuilt framework still needs its resources in this specific app bundle.
        # Skipping the whole phase produces a valid binary that crashes on painterResource.
        exec "$MOBILE_ROOT/gradlew" -p "$MOBILE_ROOT" ":shared:syncComposeResourcesForIos"
    fi

    # Xcode-specific assembly tasks depend on the plugin recognizing the SDK environment.
    # Link through the always-registered target task and stage the static framework at
    # FRAMEWORK_SEARCH_PATHS, including when Xcode uses a newer SDK.
    TARGET_DIR=$(printf '%s' "$KOTLIN_TARGET" | cut -c1 | tr '[:upper:]' '[:lower:]')$(printf '%s' "$KOTLIN_TARGET" | cut -c2-)
    CONFIG_DIR=$(printf '%s' "$CONFIGURATION_NAME" | tr '[:upper:]' '[:lower:]')"Framework"
    BIN_DIR="$MOBILE_ROOT/shared/build/bin/$TARGET_DIR/$CONFIG_DIR"
    STAGE_DIR="$MOBILE_ROOT/shared/build/xcode-frameworks/$CONFIGURATION_NAME/${SDK_NAME:-iphonesimulator}"

    "$MOBILE_ROOT/gradlew" -p "$MOBILE_ROOT" \
        ":shared:link${CONFIGURATION_NAME}Framework${KOTLIN_TARGET}" \
        ":shared:syncComposeResourcesForIos"

    FRAMEWORK_SRC=$(find "$BIN_DIR" -maxdepth 1 -name '*.framework' 2>/dev/null | head -n 1)
    if [ -z "$FRAMEWORK_SRC" ]; then
        echo "error: no framework produced in $BIN_DIR" >&2
        exit 1
    fi
    mkdir -p "$STAGE_DIR"
    rm -rf "$STAGE_DIR/$(basename "$FRAMEWORK_SRC")"
    # ditto preserves the framework bundle layout, including symlinks and Info.plist.
    ditto "$FRAMEWORK_SRC" "$STAGE_DIR/$(basename "$FRAMEWORK_SRC")"
    exit 0
fi

exec "$MOBILE_ROOT/gradlew" -p "$MOBILE_ROOT" "$@"
