#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$project_dir/../.toolchains/android-sdk}}"
adb_bin="$sdk_dir/platform-tools/adb"
device_serial="${ANDROID_SERIAL:-emulator-5554}"
if [[ "$device_serial" != emulator-* && "${ALLOW_REAL_DEVICE:-}" != 1 ]]; then
    echo 'This script changes test permissions. Set ALLOW_REAL_DEVICE=1 to explicitly use a real device.' >&2
    exit 1
fi
"$adb_bin" -s "$device_serial" wait-for-device
if [[ "$("$adb_bin" -s "$device_serial" shell getprop sys.boot_completed | tr -d '\r')" != 1 ]]; then
    echo 'Android has not finished booting; rerun when sys.boot_completed=1.' >&2
    exit 1
fi
cd "$project_dir"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
"$adb_bin" -s "$device_serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$device_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
existing_services="$("$adb_bin" -s "$device_serial" shell settings get secure enabled_accessibility_services | tr -d '\r')"
agent_service='dev.phoneagent.app/dev.phoneagent.app.PhoneAccessibilityService'
other_services=()
IFS=':' read -r -a configured_services <<< "$existing_services"
for configured_service in "${configured_services[@]}"; do
    if [[ -n "$configured_service" && "$configured_service" != null && "$configured_service" != "$agent_service" ]]; then
        other_services+=("$configured_service")
    fi
done
without_agent="$(IFS=:; printf '%s' "${other_services[*]}")"
quote_shell_arg() {
    local quoted_value=${1//\'/\'\\\'\'}
    printf "'%s'" "$quoted_value"
}
"$adb_bin" -s "$device_serial" shell "settings put secure enabled_accessibility_services $(quote_shell_arg "$without_agent")"
existing_services="${without_agent:+$without_agent:}$agent_service"
"$adb_bin" -s "$device_serial" shell "settings put secure enabled_accessibility_services $(quote_shell_arg "$existing_services")"
"$adb_bin" -s "$device_serial" shell settings put secure accessibility_enabled 1
device_api="$("$adb_bin" -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$device_api" -ge 33 ]]; then
    "$adb_bin" -s "$device_serial" shell pm grant dev.phoneagent.app android.permission.POST_NOTIFICATIONS
fi
"$adb_bin" -s "$device_serial" shell am instrument -w -r -e phoneagent.rebind_accessibility true dev.phoneagent.app.test/androidx.test.runner.AndroidJUnitRunner
