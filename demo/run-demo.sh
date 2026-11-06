#!/usr/bin/env bash
# Records the alarm math challenge on a running Android emulator (see
# .github/workflows/demo-screenshots.yml). Output: demo-output/*.png, demo.mp4, demo.gif.
set -euo pipefail

OUT="${OUT:-demo-output}"
export OUT
mkdir -p "$OUT"
OUT_ABS="$(cd "$OUT" && pwd)"
P=com.best.deskclock.debug
UI="python3 demo/ui.py"

adb root >/dev/null
sleep 3
adb wait-for-device

# Keep the screen from timing out on its own during the demo.
adb shell settings put system screen_off_timeout 600000
# Don't show Android's one-time "Viewing full screen" hint over the alarm screen.
adb shell settings put secure immersive_mode_confirmations confirmed

echo "== Install"
APK="$(ls app/build/outputs/apk/debug/*.apk | head -n1)"
adb install -r -g "$APK"
adb shell appops set "$P" SCHEDULE_EXACT_ALARM allow
adb shell appops set "$P" USE_FULL_SCREEN_INTENT allow
# Clock's setup asks the user to exempt it from battery optimization (an "essential" permission);
# do the same here. Without it, Android 17 refuses to start the alarm service in the foreground.
adb shell dumpsys deviceidle whitelist +"$P"

echo "== Settings: skip first-launch setup, math challenge on 'hard' (3 problems)"
adb shell am start -W -n "$P/com.best.deskclock.DeskClock" >/dev/null
sleep 5
adb shell am force-stop "$P"
PREFS_DIR="/data/user_de/0/$P/shared_prefs"
PREFS="$PREFS_DIR/${P}_preferences.xml"
adb shell "mkdir -p $PREFS_DIR"
TMP_PREFS="$(mktemp)"
adb shell "cat $PREFS 2>/dev/null" > "$TMP_PREFS" || true
python3 - "$TMP_PREFS" <<'PY'
import sys, xml.etree.ElementTree as ET
path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except Exception:
    root = ET.Element("map")
def put(tag, name, value):
    for e in list(root):
        if e.get("name") == name:
            root.remove(e)
    e = ET.SubElement(root, tag, name=name)
    if tag == "string":
        e.text = value
    else:
        e.set("value", value)
put("boolean", "key_is_first_launch", "false")
put("string", "key_alarm_math_hardness_level", "hard")
ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)
PY
adb push "$TMP_PREFS" "$PREFS" >/dev/null
OWNER="$(adb shell stat -c %u:%g "/data/user_de/0/$P" | tr -d '\r')"
adb shell "chown $OWNER $PREFS && chmod 660 $PREFS && restorecon $PREFS"

echo "== Set an alarm about a minute ahead"
# One argument only: adb shell re-joins and re-splits the command on the device.
IFS=: read -r H M S <<< "$(adb shell date +%H:%M:%S | tr -d '\r')"
H=$((10#$H)); M=$((10#$M)); S=$((10#$S))
ADD=1
[ "$S" -ge 40 ] && ADD=2
M=$((M + ADD))
if [ "$M" -ge 60 ]; then M=$((M - 60)); H=$(((H + 1) % 24)); fi
echo "Alarm at $(printf '%02d:%02d' "$H" "$M")"
RESULT="$(adb shell am start -a android.intent.action.SET_ALARM \
    --ei android.intent.extra.alarm.HOUR "$H" \
    --ei android.intent.extra.alarm.MINUTES "$M" \
    --es android.intent.extra.alarm.MESSAGE Wake-up \
    -p "$P" 2>&1 || true)"
echo "$RESULT"
if echo "$RESULT" | grep -qiE "error|exception"; then
    echo "Could not create the alarm" >&2
    exit 1
fi
sleep 6
$UI shot 01-alarm-list

echo "== Screen off and wait for the alarm"
adb shell input keyevent KEYCODE_SLEEP
adb emu screenrecord start --time-limit 180 "$OUT_ABS/demo.webm"
for _ in $(seq 1 240); do
    if adb shell dumpsys activity activities | grep -q "mResumedActivity.*AlarmActivity\|topResumedActivity.*AlarmActivity"; then
        break
    fi
    sleep 1
done
if adb logcat -d | grep -q "system_server: runtime.cc.*Runtime aborting"; then
    echo "WARNING: system_server crashed during the demo (emulator graphics); results below are not reliable"
    adb logcat -d | grep -E "Assertion failed|Fatal signal.*system_server" | head -n 5 || true
fi
# Did the alarm service start in the foreground (i.e. is the alarm really ringing)?
if adb logcat -d | grep -q "ForegroundServiceStartNotAllowedException.*deskclock\|Unable to start service com.best.deskclock.alarms.AlarmService"; then
    echo "RESULT: the alarm fired but AlarmService could NOT start in the foreground (alarm not ringing)"
    adb logcat -d | grep -B2 -A8 "Unable to start service com.best.deskclock.alarms.AlarmService" | head -n 40 || true
    exit 1
fi
echo "RESULT: alarm fired and AlarmService runs in the foreground"
adb shell dumpsys activity services "$P" | grep -E "ServiceRecord|isForeground" | head -n 5 || true
$UI wait-id alarm_button 60
sleep 4
$UI shot 02-alarm-ringing-on-lock-screen

echo "== Notification: no Snooze/Dismiss buttons while a math challenge is required"
adb shell cmd statusbar expand-notifications
sleep 3
$UI shot 03-notification-without-dismiss
adb shell cmd statusbar collapse
sleep 2

echo "== Swipe to dismiss -> math challenge"
$UI swipe-dismiss
$UI wait-id math_challenge_text 30
$UI solve-all 04-challenge-
sleep 3
$UI shot 05-dismissed

sleep 2
adb emu screenrecord stop || true
sleep 5

echo "== Video"
if [ -f "$OUT_ABS/demo.webm" ]; then
    ffmpeg -loglevel error -y -i "$OUT_ABS/demo.webm" -vf "scale=540:-2" -c:v libx264 -pix_fmt yuv420p -movflags +faststart "$OUT_ABS/demo.mp4"
    ffmpeg -loglevel error -y -i "$OUT_ABS/demo.webm" -vf "fps=8,scale=360:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" "$OUT_ABS/demo.gif"
    rm -f "$OUT_ABS/demo.webm"   # large raw recording; mp4 + gif are kept
fi
ls -la "$OUT_ABS"
