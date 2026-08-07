#!/usr/bin/env bash
# Spustí instrumentované testy proti Firebase emulátoru.
#
# Firebase emulátor si skript nastartuje sám a po doběhnutí ho zase zabije.
# Android zařízení (emulátor nebo telefon) musí běžet předem — nastartovat AVD
# trvá minuty a nemá smysl to dělat při každém běhu testů.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/snap/android-studio/236/jbr}"
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$HOME/.local/bin:$JAVA_HOME/bin:$PATH"

if ! command -v firebase >/dev/null; then
    echo "Firebase CLI chybí — viz SETUP.md" >&2
    exit 1
fi

if ! "$ANDROID_HOME/platform-tools/adb" devices | grep -q 'device$'; then
    cat >&2 <<'EOF'
Není připojené žádné Android zařízení. Nastartuj AVD, například:

  $ANDROID_HOME/emulator/emulator -avd Pixel_7 -no-window -no-audio -no-boot-anim &

a počkej, než dobootuje:

  adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
EOF
    exit 1
fi

emulator_pid=""
cleanup() {
    [ -n "$emulator_pid" ] && kill "$emulator_pid" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> Startuji Firebase emulátor"
firebase emulators:start --only firestore,auth --project demo-deptor > /tmp/deptor-firebase-emulator.log 2>&1 &
emulator_pid=$!

for _ in $(seq 1 60); do
    if grep -q "All emulators ready" /tmp/deptor-firebase-emulator.log 2>/dev/null; then
        break
    fi
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo "Firebase emulátor spadl:" >&2
        cat /tmp/deptor-firebase-emulator.log >&2
        exit 1
    fi
    sleep 1
done

echo "==> Instrumentované testy"
./gradlew :data:connectedDebugAndroidTest "$@"
