#!/usr/bin/env bash
set -euo pipefail

# Reproducible Linux toolchain setup. Downloads use the inherited proxy and CA trust.
# Usage: ./scripts/bootstrap-android.sh [--accept-licenses]
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN_DIR="${AI_ASSISTANT_TOOLCHAIN_DIR:-$(dirname -- "$PROJECT_DIR")/.toolchains}"
SDK_DIR="${ANDROID_HOME:-$TOOLCHAIN_DIR/android-sdk}"
CLI_VERSION=19.0
CLI_ARCHIVE=commandlinetools-linux-13114758_latest.zip
CLI_SHA256=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
JDK_ARCHIVE=jdk-21.0.12.1_linux-x64_bin.tar.gz
JDK_SHA256=12f870b21301b42292558a3f872ce543affa2b86cb6458591c78388c41ddb111
ACCEPT_LICENSES=false

if [[ "${1:-}" == --accept-licenses ]]; then
    ACCEPT_LICENSES=true
elif [[ $# -gt 0 ]]; then
    printf 'Usage: %s [--accept-licenses]\n' "$0" >&2
    exit 2
fi
for prerequisite in curl unzip tar sha256sum python3; do
    command -v "$prerequisite" >/dev/null || { printf 'Missing prerequisite: %s\n' "$prerequisite" >&2; exit 1; }
done
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    printf 'This bootstrap script supports Linux x86_64. On other platforms use Android Studio with SDK 35 and JDK 21.\n' >&2
    exit 1
fi
mkdir -p "$TOOLCHAIN_DIR/downloads" "$SDK_DIR/cmdline-tools"
# Gradle and Android's jlink transform need a full JDK, not a JRE. Use an
# existing JDK21 when selected; otherwise install a pinned development JDK.
javac_command="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
javac_version="$("$javac_command" -version 2>&1 || true)"
if [[ "$javac_version" != 'javac 21.'* ]]; then
    if [[ ! -x "$TOOLCHAIN_DIR/jdk-21/bin/javac" ]]; then
        jdk_archive="$TOOLCHAIN_DIR/downloads/$JDK_ARCHIVE"
        if [[ ! -f "$jdk_archive" ]]; then
            curl --fail --location --retry 3 --output "$jdk_archive.part" "https://download.oracle.com/java/21/archive/$JDK_ARCHIVE"
            mv -- "$jdk_archive.part" "$jdk_archive"
        fi
        printf '%s  %s\n' "$JDK_SHA256" "$jdk_archive" | sha256sum --check --status
        mkdir -p "$TOOLCHAIN_DIR/jdk-21"
        tar -xzf "$jdk_archive" -C "$TOOLCHAIN_DIR/jdk-21" --strip-components=1
        # Reuse the OS Java CA trust, including any configured HTTPS proxy CA.
        if [[ -r /etc/ssl/certs/java/cacerts ]]; then
            rm -- "$TOOLCHAIN_DIR/jdk-21/lib/security/cacerts"
            ln -s /etc/ssl/certs/java/cacerts "$TOOLCHAIN_DIR/jdk-21/lib/security/cacerts"
        fi
    fi
    export JAVA_HOME="$TOOLCHAIN_DIR/jdk-21"
fi
if [[ ! -x "$SDK_DIR/cmdline-tools/$CLI_VERSION/bin/sdkmanager" ]]; then
    archive="$TOOLCHAIN_DIR/downloads/$CLI_ARCHIVE"
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --retry 3 --output "$archive.part" "https://dl.google.com/android/repository/$CLI_ARCHIVE"
        mv -- "$archive.part" "$archive"
    fi
    printf '%s  %s\n' "$CLI_SHA256" "$archive" | sha256sum --check --status
    staging="$(mktemp -d "$SDK_DIR/cmdline-tools/.unpack.XXXXXX")"
    trap 'rm -rf -- "$staging"' EXIT
    unzip -q "$archive" -d "$staging"
    mv -- "$staging/cmdline-tools" "$SDK_DIR/cmdline-tools/$CLI_VERSION"
    rm -rf -- "$staging"
    trap - EXIT
fi

# sdkmanager (Java) does not read HTTP(S)_PROXY itself. Preserve the configured
# network route rather than falling back to a direct connection.
proxy_args=()
if [[ -n "${HTTPS_PROXY:-${HTTP_PROXY:-}}" ]]; then
    mapfile -t proxy_parts < <(python3 - <<'PY'
import os
from urllib.parse import urlsplit
proxy = urlsplit(os.environ.get('HTTPS_PROXY') or os.environ.get('HTTP_PROXY', ''))
if proxy.hostname:
    print(proxy.hostname)
    print(proxy.port or (443 if proxy.scheme == 'https' else 80))
PY
    )
    if [[ ${#proxy_parts[@]} -eq 2 ]]; then
        proxy_args=(--proxy=http "--proxy_host=${proxy_parts[0]}" "--proxy_port=${proxy_parts[1]}")
    fi
fi
sdkmanager="$SDK_DIR/cmdline-tools/$CLI_VERSION/bin/sdkmanager"
if $ACCEPT_LICENSES; then
    # yes receives SIGPIPE after sdkmanager exits; retain sdkmanager's status.
    set +o pipefail
    yes | "$sdkmanager" "--sdk_root=$SDK_DIR" "${proxy_args[@]}" --licenses
    result=${PIPESTATUS[1]}
    set -o pipefail
    [[ $result -eq 0 ]] || exit "$result"
else
    "$sdkmanager" "--sdk_root=$SDK_DIR" "${proxy_args[@]}" --licenses
fi
"$sdkmanager" "--sdk_root=$SDK_DIR" "${proxy_args[@]}" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
python3 - "$PROJECT_DIR/local.properties" "$SDK_DIR" <<'PY'
from pathlib import Path
import sys
sdk = sys.argv[2].replace('\\', '\\\\').replace(':', '\\:').replace(' ', '\\ ')
Path(sys.argv[1]).write_text('sdk.dir=' + sdk + '\n', encoding='utf-8')
PY
printf '\nSDK ready: %s\nBuild: cd %q && ' "$SDK_DIR" "$PROJECT_DIR"
if [[ -n "${JAVA_HOME:-}" ]]; then
    printf 'JAVA_HOME=%q ' "$JAVA_HOME"
fi
printf './gradlew :core:test :app:assembleDebug\n'
