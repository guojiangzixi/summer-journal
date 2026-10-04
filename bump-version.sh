#!/usr/bin/env bash
# ============================================================
#  夏日手札 · 升版本号
#  用法： ./bump-version.sh 1.1.0
#  只改 version.properties 两行，不碰 build.gradle.kts。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

FILE="version.properties"
NEWNAME="${1:-}"

if [[ -z "$NEWNAME" ]]; then
    cat <<'USAGE'

  Usage: ./bump-version.sh <new versionName>
     e.g. ./bump-version.sh 1.1.0

  This will:
    - read  the current versionCode and add 1
    - set   versionName to the value you passed
    - write both back to version.properties
USAGE
    exit 1
fi

[[ -f "$FILE" ]] || { echo "[X] $FILE not found. Run from the project root (code/)."; exit 1; }

OLDCODE=$(grep -E '^versionCode=' "$FILE" | head -1 | cut -d= -f2 | tr -d ' \r')
OLDNAME=$(grep -E '^versionName=' "$FILE" | head -1 | cut -d= -f2 | tr -d ' \r')

[[ -n "$OLDCODE" ]] || { echo "[X] Could not read versionCode from $FILE"; exit 1; }

NEWCODE=$((OLDCODE + 1))

echo
echo "  versionCode : $OLDCODE  ->  $NEWCODE"
echo "  versionName : $OLDNAME  ->  $NEWNAME"
echo

cat > "$FILE" <<EOF
# Summer Journal - the single source of truth for the app version.
# versionCode must strictly increase. versionName is for humans.
versionCode=$NEWCODE
versionName=$NEWNAME
EOF

echo "  Wrote $FILE."
echo
echo "  NEXT:"
echo "    1. Changed the DB schema? -> add a Room migration and bump"
echo "       @Database(version) too. See 版本更新指南.md section 6."
echo "    2. Build:   ./build-apk.sh release"
echo "    3. Install over the old one: adb install -r APK/SummerJournal-release.apk"
echo "    4. Verify the old data is still there."
echo
