#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${TMPDIR:-/tmp}/signal-hunter-core-$$"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT"
find app/src/main/java/com/cory/signalhunter/core \
    -name '*.java' -print > "$OUT/sources"
printf '%s\n' \
    app/src/test/java/com/cory/signalhunter/core/CoreTest.java \
    >> "$OUT/sources"
javac --release 17 -d "$OUT" @"$OUT/sources"
java -cp "$OUT" com.cory.signalhunter.core.CoreTest
