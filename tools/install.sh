#!/usr/bin/env bash
# Build Geococcyx, install it on the watch over Wi-Fi debugging, and push config.json.
#   tools/install.sh 192.168.1.23:41234      (address from Settings > Developer options > Wireless debugging)
# Re-run with no address to just rebuild, reinstall and re-push config to the connected watch.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="$HOME/Android/jdk" ANDROID_HOME="$HOME/Android/Sdk"
adb="$ANDROID_HOME/platform-tools/adb"

[[ $# -ge 1 ]] && "$adb" connect "$1"
./gradlew -q assembleDebug
"$adb" install -r app/build/outputs/apk/debug/app-debug.apk
if [[ -f config.json ]]; then
  # Write to a temp file and move it into place, so a dropped connection never leaves an empty config.
  "$adb" shell "run-as com.jameslongo.geococcyx sh -c 'mkdir -p files && cat > files/config.json.tmp && [ -s files/config.json.tmp ] && mv files/config.json.tmp files/config.json'" < config.json
  echo "config.json pushed"
fi
