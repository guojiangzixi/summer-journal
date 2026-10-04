#!/usr/bin/env bash
# ============================================================
#  夏日手札 - Build APK (macOS / Linux)
#  Usage:
#      ./build-apk.sh            # debug build
#      ./build-apk.sh release    # release build (needs signing config)
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-debug}"

echo
echo "[1/3] Building ${MODE} APK ..."
echo

if [[ "${MODE}" == "release" ]]; then
    ./gradlew assembleRelease
    SRC="app/build/outputs/apk/release/app-release.apk"
    DST="../APK/SummerJournal-release.apk"
else
    ./gradlew assembleDebug
    SRC="app/build/outputs/apk/debug/app-debug.apk"
    DST="../APK/SummerJournal-debug.apk"
fi

echo
echo "[2/3] Copying APK to ../APK/ ..."
mkdir -p ../APK
cp -f "${SRC}" "${DST}"

echo
echo "[3/3] Done."
echo
ls -lh "${DST}" | awk '{print "  APK:  " $9 "  (" $5 ")"}'
echo
echo "  Next: send THIS ONE FILE to your phone, then tap it to install."
echo "  (Source code and docs are NOT needed on the phone.)"
echo
