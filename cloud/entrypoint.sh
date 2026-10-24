#!/usr/bin/env bash
set -Eeuo pipefail

export HOME=/home/android
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_HOME=/opt/android-sdk
export ANDROID_AVD_HOME="$HOME/.android/avd"
export DISPLAY=:99
mkdir -p "$ANDROID_AVD_HOME"

# Userdata is preserved in the named Docker volume.
if [[ ! -f "$ANDROID_AVD_HOME/hiddenbox-cloud.ini" ]]; then
  echo "[hiddenbox] Creating Google Play Android 14 AVD..."
  printf 'no\n' | avdmanager create avd --force \
    --name hiddenbox-cloud \
    --package 'system-images;android-34;google_apis_playstore;x86_64'
  config="$ANDROID_AVD_HOME/hiddenbox-cloud.avd/config.ini"
  test -f "$config" || { echo "Android AVD creation failed"; exit 1; }
  cat >> "$config" <<'AVDCONFIG'
hw.keyboard=yes
hw.lcd.width=480
hw.lcd.height=854
hw.lcd.density=320
disk.dataPartition.size=8G
PlayStore.enabled=true
AVDCONFIG
fi

Xvfb :99 -screen 0 1280x960x24 -ac -nolisten tcp >/tmp/xvfb.log 2>&1 &
sleep 2
x11vnc -display :99 -rfbport 5900 -localhost \
  -nopw -forever -shared -quiet >/tmp/x11vnc.log 2>&1 &
websockify --web=/usr/share/novnc/ 6080 localhost:5900 \
  >/tmp/websockify.log 2>&1 &

echo "[hiddenbox] Starting Android emulator with KVM"
exec emulator -avd hiddenbox-cloud \
  -accel on -gpu swiftshader_indirect \
  -no-audio -no-boot-anim -no-snapshot -no-metrics -memory 3072
