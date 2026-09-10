#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v gradle >/dev/null 2>&1; then
    printf '%s\n' 'Gradle 8.9 is required.' >&2
    printf '%s\n' 'Use the included GitHub Actions workflow.' >&2
    exit 1
fi
if [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    printf '%s\n' 'Android SDK environment is not configured.' >&2
    exit 1
fi
bash tools/test-core.sh
gradle --no-daemon :app:assembleDebug
printf '%s\n' \
    'app/build/outputs/apk/debug/app-debug.apk'
