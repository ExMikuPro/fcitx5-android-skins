#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

DEVICE_SERIAL=${ADB_SERIAL:-}
RUN_NAME=""
REFERENCE_FILE=""
WAIT_SECONDS=2
TAP_POINT="500,170"
MODE="static"
RANDOM_SEED=3029
PYTHON_BIN=${BDS_PYTHON:-python3}
NO_BUILD=0
NO_INSTALL=0
NO_COMPARE=0
VERBOSE=0
FORCE=0
TEST_ACTIVITY="com.android.settings.intelligence/.search.SearchActivity"
TEST_IME="org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
BDS_CONFIG_RECEIVER="org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.input.keyboard.bds.BdsRenderConfigReceiver"
MAIN_CROP="0,1075,1080,845"
KEYBOARD_REGION="0,158,1080,687"
CANDIDATE_REGION="0,0,1080,158"

OUTPUT_DIR=""
REMOTE_SCREENSHOT=""
ORIGINAL_ACCELEROMETER=""
ORIGINAL_ROTATION=""
ORIGINAL_IME=""
STATE_CAPTURED=0
IME_CHANGED=0
IME_ENABLED_BY_SCRIPT=0

usage() {
    cat <<'EOF'
Usage: ./tools/bds-visual-test.sh [options]

Build, install, capture, and compare the BDS keyboard on a connected Android device.

Options:
  --serial SERIAL          Select an ADB device (or set ADB_SERIAL).
  --name NAME              Run directory name (default: timestamped name).
  --reference FILE         Full-screen Baidu reference screenshot.
  --no-build               Skip ./gradlew :app:assembleDebug.
  --no-install             Skip adb install -r.
  --no-compare             Capture only; --reference is not required.
  --wait SECONDS           Visual stabilization delay after IME appears (default: 2).
  --tap X,Y                Input field tap point (default: 500,170).
  --mode MODE              static or animation (default: static).
  --seed INTEGER           Debug animation random seed (default: 3029).
  --activity COMPONENT     Explicit test Activity component.
  --ime COMPONENT          IME selected for the test and restored on exit.
  --crop X,Y,W,H           Complete IME crop (default: 0,1075,1080,845).
  --keyboard-region X,Y,W,H
                           Keyboard region relative to --crop.
  --candidate-region X,Y,W,H
                           Candidate region relative to --crop.
  --force                  Allow writing into an existing run directory.
  --verbose                Print commands before execution.
  --help                   Show this help.

Environment:
  ADB_SERIAL               Same as --serial.
  BDS_PYTHON               Python interpreter containing Pillow.

Examples:
  ./tools/bds-visual-test.sh \
      --serial CB512F5084 \
      --name cand1-pass-01 \
      --reference local-testdata/visual-golden/reference-full.png

  ./tools/bds-visual-test.sh \
      --no-build --no-install \
      --name cand1-fast \
      --reference local-testdata/visual-golden/reference-full.png
EOF
}

die() {
    echo "error: $*" >&2
    exit 1
}

print_command() {
    if [[ $VERBOSE -eq 1 ]]; then
        printf '+' >&2
        printf ' %q' "$@" >&2
        printf '\n' >&2
    fi
}

run_cmd() {
    print_command "$@"
    "$@"
}

adb_cmd() {
    print_command adb -s "$DEVICE_SERIAL" "$@"
    adb -s "$DEVICE_SERIAL" "$@"
}

adb_host_cmd() {
    print_command adb "$@"
    adb "$@"
}

cleanup() {
    local status=$?
    trap - EXIT
    set +e
    if [[ -n "$REMOTE_SCREENSHOT" && -n "$DEVICE_SERIAL" ]]; then
        adb_cmd shell rm -f "$REMOTE_SCREENSHOT" >/dev/null 2>&1
    fi
    if [[ $STATE_CAPTURED -eq 1 ]]; then
        if [[ -n "$ORIGINAL_ACCELEROMETER" && "$ORIGINAL_ACCELEROMETER" != "null" ]]; then
            adb_cmd shell settings put system accelerometer_rotation "$ORIGINAL_ACCELEROMETER" \
                >/dev/null 2>&1
        fi
        if [[ -n "$ORIGINAL_ROTATION" && "$ORIGINAL_ROTATION" != "null" ]]; then
            adb_cmd shell settings put system user_rotation "$ORIGINAL_ROTATION" >/dev/null 2>&1
        fi
        if [[ $IME_CHANGED -eq 1 && -n "$ORIGINAL_IME" && "$ORIGINAL_IME" != "null" ]]; then
            adb_cmd shell ime set "$ORIGINAL_IME" >/dev/null 2>&1
        fi
        if [[ $IME_ENABLED_BY_SCRIPT -eq 1 ]]; then
            adb_cmd shell ime disable "$TEST_IME" >/dev/null 2>&1
        fi
    fi
    exit "$status"
}

wait_for_device() {
    local deadline=$((SECONDS + 30))
    while (( SECONDS < deadline )); do
        if [[ $(adb_cmd get-state 2>/dev/null || true) == "device" ]]; then
            return 0
        fi
        sleep 1
    done
    die "device $DEVICE_SERIAL did not become online within 30 seconds"
}

wait_for_activity() {
    local deadline=$((SECONDS + 20))
    local activity_class=${TEST_ACTIVITY#*/}
    activity_class=${activity_class#.}
    while (( SECONDS < deadline )); do
        if adb_cmd shell dumpsys activity activities 2>/dev/null \
            | grep -F 'mResumedActivity' \
            | grep -Fq "$activity_class"; then
            return 0
        fi
        sleep 1
    done
    die "Activity $TEST_ACTIVITY was not resumed within 20 seconds"
}

wait_for_ime() {
    local deadline=$((SECONDS + 20))
    while (( SECONDS < deadline )); do
        if adb_cmd shell dumpsys input_method 2>/dev/null | grep -Fq 'mInputShown=true'; then
            return 0
        fi
        sleep 1
    done
    die "IME window did not become visible within 20 seconds"
}

select_device() {
    local devices=()
    local serial
    if [[ -n "$DEVICE_SERIAL" ]]; then
        return
    fi
    while IFS= read -r serial; do
        [[ -n "$serial" ]] && devices+=("$serial")
    done < <(adb_host_cmd devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    case ${#devices[@]} in
        0) die "no online ADB device found" ;;
        1) DEVICE_SERIAL=${devices[0]} ;;
        *)
            adb_host_cmd devices -l >&2
            die "multiple devices are connected; use --serial or ADB_SERIAL"
            ;;
    esac
}

find_debug_apk() {
    local candidates=()
    local candidate
    while IFS= read -r candidate; do
        [[ -n "$candidate" ]] && candidates+=("$candidate")
    done < <(
        find "$PROJECT_ROOT/app/build/outputs/apk/debug" -maxdepth 1 -type f \
            -name '*arm64-v8a*debug.apk' -print 2>/dev/null | sort
    )
    if [[ ${#candidates[@]} -eq 0 ]]; then
        die "no arm64 debug APK found under app/build/outputs/apk/debug"
    fi
    if [[ ${#candidates[@]} -ne 1 ]]; then
        printf 'APK candidates:\n' >&2
        printf '  %s\n' "${candidates[@]}" >&2
        die "expected exactly one arm64 debug APK"
    fi
    printf '%s\n' "${candidates[0]}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) [[ $# -ge 2 ]] || die "--serial requires a value"; DEVICE_SERIAL=$2; shift 2 ;;
        --name) [[ $# -ge 2 ]] || die "--name requires a value"; RUN_NAME=$2; shift 2 ;;
        --reference) [[ $# -ge 2 ]] || die "--reference requires a value"; REFERENCE_FILE=$2; shift 2 ;;
        --no-build) NO_BUILD=1; shift ;;
        --no-install) NO_INSTALL=1; shift ;;
        --no-compare) NO_COMPARE=1; shift ;;
        --wait) [[ $# -ge 2 ]] || die "--wait requires a value"; WAIT_SECONDS=$2; shift 2 ;;
        --tap) [[ $# -ge 2 ]] || die "--tap requires a value"; TAP_POINT=$2; shift 2 ;;
        --mode) [[ $# -ge 2 ]] || die "--mode requires a value"; MODE=$2; shift 2 ;;
        --seed) [[ $# -ge 2 ]] || die "--seed requires a value"; RANDOM_SEED=$2; shift 2 ;;
        --activity) [[ $# -ge 2 ]] || die "--activity requires a value"; TEST_ACTIVITY=$2; shift 2 ;;
        --ime) [[ $# -ge 2 ]] || die "--ime requires a value"; TEST_IME=$2; shift 2 ;;
        --crop) [[ $# -ge 2 ]] || die "--crop requires a value"; MAIN_CROP=$2; shift 2 ;;
        --keyboard-region) [[ $# -ge 2 ]] || die "--keyboard-region requires a value"; KEYBOARD_REGION=$2; shift 2 ;;
        --candidate-region) [[ $# -ge 2 ]] || die "--candidate-region requires a value"; CANDIDATE_REGION=$2; shift 2 ;;
        --force) FORCE=1; shift ;;
        --verbose) VERBOSE=1; shift ;;
        --help|-h) usage; exit 0 ;;
        *) die "unknown option: $1 (use --help)" ;;
    esac
done

[[ "$MODE" == "static" || "$MODE" == "animation" ]] \
    || die "--mode must be static or animation"
[[ "$RANDOM_SEED" =~ ^-?[0-9]+$ ]] || die "--seed must be an integer"
[[ "$WAIT_SECONDS" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--wait must be a non-negative number"
[[ "$TAP_POINT" =~ ^[0-9]+,[0-9]+$ ]] || die "--tap must be X,Y"
[[ "$MAIN_CROP" =~ ^[0-9]+,[0-9]+,[0-9]+,[0-9]+$ ]] || die "--crop must be X,Y,W,H"
[[ "$KEYBOARD_REGION" =~ ^[0-9]+,[0-9]+,[0-9]+,[0-9]+$ ]] \
    || die "--keyboard-region must be X,Y,W,H"
[[ "$CANDIDATE_REGION" =~ ^[0-9]+,[0-9]+,[0-9]+,[0-9]+$ ]] \
    || die "--candidate-region must be X,Y,W,H"

command -v adb >/dev/null || die "adb was not found"
if [[ $NO_COMPARE -ne 1 ]]; then
    command -v "$PYTHON_BIN" >/dev/null || die "Python interpreter was not found: $PYTHON_BIN"
    if ! "$PYTHON_BIN" -c 'from PIL import Image' >/dev/null 2>&1; then
        CODEX_PYTHON="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
        if [[ "$PYTHON_BIN" == "python3" && -x "$CODEX_PYTHON" ]] \
            && "$CODEX_PYTHON" -c 'from PIL import Image' >/dev/null 2>&1; then
            PYTHON_BIN=$CODEX_PYTHON
        else
            die "Python Pillow is required (install Pillow or set BDS_PYTHON)"
        fi
    fi
fi

select_device
wait_for_device

if [[ -z "$RUN_NAME" ]]; then
    RUN_NAME="bds-$(date +%Y%m%d-%H%M%S)"
fi
[[ "$RUN_NAME" =~ ^[A-Za-z0-9._-]+$ ]] \
    || die "--name may contain only letters, digits, '.', '_' and '-'"
OUTPUT_DIR="$PROJECT_ROOT/local-testdata/visual-golden/$RUN_NAME"
if [[ -e "$OUTPUT_DIR" && $FORCE -ne 1 ]]; then
    die "output already exists: $OUTPUT_DIR (choose another --name or use --force)"
fi
mkdir -p "$OUTPUT_DIR"

if [[ $NO_COMPARE -ne 1 ]]; then
    [[ -n "$REFERENCE_FILE" ]] || die "--reference is required unless --no-compare is used"
    if [[ "$REFERENCE_FILE" != /* ]]; then
        REFERENCE_FILE="$PROJECT_ROOT/$REFERENCE_FILE"
    fi
    [[ -f "$REFERENCE_FILE" ]] || die "reference screenshot not found: $REFERENCE_FILE"
fi

cd "$PROJECT_ROOT"
START_TIME=$(date +%s)
if [[ $NO_BUILD -ne 1 ]]; then
    BUILD_ENV=(BUILD_ABI=arm64-v8a)
    if [[ -z ${CMAKE_VERSION:-} ]] && command -v cmake >/dev/null 2>&1; then
        DETECTED_CMAKE_VERSION=$(cmake --version | awk 'NR == 1 { print $3 }')
        [[ -n "$DETECTED_CMAKE_VERSION" ]] \
            && BUILD_ENV+=(CMAKE_VERSION="$DETECTED_CMAKE_VERSION")
    fi
    run_cmd env "${BUILD_ENV[@]}" ./gradlew :app:assembleDebug --console=plain
fi

APK_PATH=""
if [[ $NO_INSTALL -ne 1 ]]; then
    APK_PATH=$(find_debug_apk)
    adb_cmd install -r "$APK_PATH"
fi

adb_cmd logcat -c
ORIGINAL_ACCELEROMETER=$(adb_cmd shell settings get system accelerometer_rotation | tr -d '\r')
ORIGINAL_ROTATION=$(adb_cmd shell settings get system user_rotation | tr -d '\r')
ORIGINAL_IME=$(adb_cmd shell settings get secure default_input_method | tr -d '\r')
STATE_CAPTURED=1
trap cleanup EXIT

adb_cmd shell settings put system accelerometer_rotation 0
adb_cmd shell settings put system user_rotation 0
if ! adb_cmd shell ime list -s | tr -d '\r' | grep -Fxq "$TEST_IME"; then
    adb_cmd shell ime enable "$TEST_IME"
    IME_ENABLED_BY_SCRIPT=1
fi
adb_cmd shell am broadcast -n "$BDS_CONFIG_RECEIVER" \
    --es mode "$MODE" --ei seed "$RANDOM_SEED" >/dev/null
if [[ "$ORIGINAL_IME" != "$TEST_IME" ]]; then
    adb_cmd shell ime set "$TEST_IME"
    IME_CHANGED=1
fi

adb_cmd shell am start -n "$TEST_ACTIVITY"
wait_for_activity
IFS=, read -r TAP_X TAP_Y <<<"$TAP_POINT"
adb_cmd shell input tap "$TAP_X" "$TAP_Y"
wait_for_ime
sleep "$WAIT_SECONDS"

REMOTE_SCREENSHOT="/sdcard/bds-visual-$(date +%s)-$$.png"
adb_cmd shell screencap -p "$REMOTE_SCREENSHOT"
adb_cmd pull "$REMOTE_SCREENSHOT" "$OUTPUT_DIR/current-full.png"
adb_cmd shell rm -f "$REMOTE_SCREENSHOT"
REMOTE_SCREENSHOT=""

adb_cmd logcat -d -v threadtime \
    | awk '/org\.fcitx\.fcitx5\.android\.debug|BDS|AndroidRuntime|fcitx5/' \
    > "$OUTPUT_DIR/logcat.txt"

if [[ $NO_COMPARE -ne 1 ]]; then
    COMPARE_COMMAND=(
        "$PYTHON_BIN" "$SCRIPT_DIR/bds_visual_diff.py"
        "$REFERENCE_FILE"
        "$OUTPUT_DIR/current-full.png"
        "$OUTPUT_DIR"
        --crop "$MAIN_CROP"
        --region "keyboard=$KEYBOARD_REGION"
        --region "candidate=$CANDIDATE_REGION"
    )
    if [[ $VERBOSE -eq 1 ]]; then
        run_cmd "${COMPARE_COMMAND[@]}"
    else
        "${COMPARE_COMMAND[@]}" >/dev/null
    fi
fi

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))
echo
echo "BDS Visual Regression"
echo "---------------------"
echo "Device: $DEVICE_SERIAL"
echo "Mode: $MODE"
echo "APK: ${APK_PATH:-<not installed>}"
echo "Output: $OUTPUT_DIR"
echo "Elapsed: ${ELAPSED}s"
if [[ $NO_COMPARE -ne 1 ]]; then
    "$PYTHON_BIN" - "$OUTPUT_DIR/metrics.json" <<'PY'
import json
import sys

metrics = json.load(open(sys.argv[1], encoding="utf-8"))
regions = metrics["regions"]
print(f"Keyboard similarity: {regions['keyboard']['similarity'] * 100:.2f}%")
print(f"Candidate similarity: {regions['candidate']['similarity'] * 100:.2f}%")
print(f"Complete IME similarity: {metrics['similarity'] * 100:.2f}%")
print(f"MAE: {metrics['mae']:.2f}")
PY
fi
