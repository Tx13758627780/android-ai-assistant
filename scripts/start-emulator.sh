#!/usr/bin/env bash
set -euo pipefail

# Reproducible AOSP device for local smoke tests. Software emulation defaults to
# the supported minimum API30 because API35 may hit watchdogs without KVM.
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN_DIR="${AI_ASSISTANT_TOOLCHAIN_DIR:-$(dirname -- "$PROJECT_DIR")/.toolchains}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN_DIR/android-sdk}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$TOOLCHAIN_DIR/avd}"
API="${AI_ASSISTANT_EMULATOR_API:-35}"
CORES=2
MEMORY=2048
acceleration=()
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    API="${AI_ASSISTANT_EMULATOR_API:-30}"
    CORES=1
    MEMORY=1536
    acceleration=(-accel off)
fi
PORT="${AI_ASSISTANT_EMULATOR_PORT:-5554}"
if [[ ! "$API" =~ ^(30|35)$ || ! "$PORT" =~ ^[0-9]+$ ]]; then
    printf 'Supported API values: 30 or 35. The emulator port must be numeric.\n' >&2
    exit 2
fi
AVD_NAME=assistant-smoke
if [[ "$API" == 30 ]]; then AVD_NAME=assistant-smoke30; fi
if [[ -z "${JAVA_HOME:-}" && -x "$TOOLCHAIN_DIR/jdk-21/bin/java" ]]; then
    export JAVA_HOME="$TOOLCHAIN_DIR/jdk-21"
fi
SDKMANAGER="$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
    printf 'Run ./scripts/bootstrap-android.sh first.\n' >&2
    exit 1
fi
proxy_args=()
if [[ -n "${HTTPS_PROXY:-${HTTP_PROXY:-}}" ]]; then
    mapfile -t proxy_parts < <(python3 - <<'PY'
import os
from urllib.parse import urlsplit
p = urlsplit(os.environ.get('HTTPS_PROXY') or os.environ.get('HTTP_PROXY', ''))
if p.hostname:
    print(p.hostname)
    print(p.port or (443 if p.scheme == 'https' else 80))
PY
    )
    if [[ ${#proxy_parts[@]} -eq 2 ]]; then
        proxy_args=(--proxy=http "--proxy_host=${proxy_parts[0]}" "--proxy_port=${proxy_parts[1]}")
    fi
fi
if [[ ! -x "$ANDROID_HOME/emulator/emulator" || ! -f "$ANDROID_HOME/system-images/android-$API/default/x86_64/system.img" ]]; then
    "$SDKMANAGER" "--sdk_root=$ANDROID_HOME" "${proxy_args[@]}" 'emulator' "system-images;android-$API;default;x86_64"
fi
mkdir -p "$ANDROID_AVD_HOME"
if [[ ! -f "$ANDROID_AVD_HOME/$AVD_NAME.ini" ]]; then
    printf 'no\n' | "$AVDMANAGER" create avd --name "$AVD_NAME" --package "system-images;android-$API;default;x86_64" --device pixel_4
    printf '\nhw.lcd.density=240\n' >> "$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini"
fi
printf 'Starting emulator-%s (API%s). Readiness: adb -s emulator-%s shell getprop sys.boot_completed (must return 1).\n' "$PORT" "$API" "$PORT"
exec "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory "$MEMORY" -cores "$CORES" -skin 540x960 -port "$PORT" "${acceleration[@]}" "$@"
