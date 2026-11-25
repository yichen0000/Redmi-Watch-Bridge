#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Probes Mi Health (com.mi.health) on a rooted device so the ingest anchors can be written for a
# specific version. Read-only: it copies nothing out except the APK and prints a report.
#
# Usage:
#   ./probe-mihealth-adb.sh                 # report to stdout, APK to ./out/
#   OUT=./out ./probe-mihealth-adb.sh
# Device must be connected and `adb shell su -c id` must print uid=0.
set -u

PKG=com.mi.health
OUT=${OUT:-./out}
mkdir -p "$OUT"
SU() { adb shell su -c "$1" 2>/dev/null; }

echo "=== 0. prerequisites ==="
adb get-state || { echo "no device"; exit 1; }
SU id | head -1
mod=$(adb shell getprop ro.product.model | tr -d '\r')
rom=$(adb shell getprop ro.build.display.id | tr -d '\r')
echo "model=$mod rom=$rom"

echo
echo "=== 1. package identity ==="
adb shell dumpsys package $PKG | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime|codePath|dataDir" | sed 's/^ *//'
APK=$(adb shell pm path $PKG | sed 's/package://' | tr -d '\r' | head -1)
echo "apk=$APK"
adb pull "$APK" "$OUT/mi.health.apk" 2>&1 | tail -1

echo
echo "=== 2. databases (name / size / first 16 bytes) ==="
SU "ls -l /data/data/$PKG/databases/" | sed 's/^ *//'
echo "--- header probe: 'SQLite format 3' means PLAIN, anything else means ENCRYPTED ---"
SU "for f in /data/data/$PKG/databases/*; do [ -f \"\$f\" ] && printf '%s: ' \"\$(basename \$f)\" && head -c 16 \"\$f\" | od -An -c | tr -s ' '; done"

echo
echo "=== 3. key material location ==="
SU "ls -la /data/data/$PKG/files/config/ 2>/dev/null"
SU "ls -la /data/data/$PKG/files/ | head -40"
SU "ls -la /data/data/$PKG/shared_prefs/ | head -30"

echo
echo "=== 4. native crypto (sqlcipher?) ==="
SU "ls /data/app/*/$PKG*/lib/arm64/ 2>/dev/null | grep -iE 'sqlcipher|sqlite|crypto|conscrypt'"
adb shell "unzip -l $APK 2>/dev/null | grep -iE 'sqlcipher|libsqlite' | head" 2>/dev/null

echo
echo "=== 5. providers / services reachable from outside ==="
adb shell dumpsys package $PKG | sed -n '/Providers:/,/Services:/p' | grep -E "^\s+[a-zA-Z0-9_.]+:" | sed 's/^ *//' | head -20
echo "--- exported providers only ---"
adb shell dumpsys package $PKG | grep -E "authority|exported=true" | sed 's/^ *//' | head -30

echo
echo "=== 6. health connect registration ==="
adb shell dumpsys package $PKG | grep -iE "healthconnect|health.connect|permission.health" | sed 's/^ *//' | head -20
adb shell cmd healthconnect help >/dev/null 2>&1 && echo "healthconnect CLI present" || echo "healthconnect CLI absent"

echo
echo "=== 7. dex layout (is the data layer its own dex?) ==="
adb shell "unzip -l $APK 2>/dev/null | grep -E 'classes.*dex'"

echo
echo "=== 8. last sync-related logcat (needs a sync to have happened) ==="
adb logcat -d -v time 2>/dev/null | grep -iE "mi\.health.*(sync|file|upload|activity)" | tail -30

echo
echo "report done. attach $OUT/mi.health.apk and this output."
