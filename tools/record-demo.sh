#!/bin/bash
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$REPO_DIR/../android-sdk}"
ADB="$SDK_DIR/platform-tools/adb"
OUTPUT_DIR="${DEMO_OUTPUT_DIR:-$REPO_DIR/../../outputs/시연영상}"
SCENARIO="${1:-S13}"
SECONDS_TO_RECORD="${2:-45}"
case "$SCENARIO" in S13|S06|S03) ;; *) echo '지원 시나리오: S13 시내 추돌 / S06 고속도로 고장 / S03 중대 사고'; exit 1;; esac
case "$SECONDS_TO_RECORD" in ''|*[!0-9]*) echo '녹화 시간은 초 단위 정수입니다.'; exit 1;; esac
if (( SECONDS_TO_RECORD < 5 || SECONDS_TO_RECORD > 180 )); then echo '녹화 시간: 5~180초'; exit 1; fi
if [[ ! -x "$ADB" ]]; then echo 'Android 개발 도구 경로를 찾지 못했습니다.'; exit 1; fi
DEVICE_COUNT=$($ADB devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')
if [[ "$DEVICE_COUNT" != 1 ]]; then echo '에뮬레이터 한 대만 실행해 주세요.'; exit 1; fi
if ! "$ADB" shell pm path com.alertbridge.app | grep -q '^package:'; then echo '먼저 앱을 설치해 주세요.'; exit 1; fi
mkdir -p "$OUTPUT_DIR"
STAMP=$(date '+%Y%m%d-%H%M%S')
VIDEO_NAME="${SCENARIO}_${STAMP}.mp4"
REMOTE_FILE="/sdcard/${VIDEO_NAME}"
# Start recording before replaying the scenario; capture the actual emulator display.
"$ADB" shell screenrecord --bit-rate 10000000 --time-limit "$SECONDS_TO_RECORD" "$REMOTE_FILE" &
RECORDER_PID=$!
trap 'kill "$RECORDER_PID" 2>/dev/null || true' INT TERM
sleep 1
"$ADB" shell am broadcast -a com.alertbridge.app.DEBUG_SCENARIO -p com.alertbridge.app --es scenario "$SCENARIO" --ez autoAnswers true
printf '녹화 중입니다. %s초 동안 에뮬레이터의 앱 화면을 유지해 주세요.\n' "$SECONDS_TO_RECORD"
wait "$RECORDER_PID"
trap - INT TERM
"$ADB" pull "$REMOTE_FILE" "$OUTPUT_DIR/$VIDEO_NAME"
printf '영상 저장 완료: %s\n' "$OUTPUT_DIR/$VIDEO_NAME"
