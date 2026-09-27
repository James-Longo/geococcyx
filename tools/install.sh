#!/usr/bin/env bash
# Build TrueRun, install it on the watch over Wi-Fi debugging, and push config.json.
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
  "$adb" shell "run-as com.jameslongo.truerun sh -c 'mkdir -p files && cat > files/config.json'" < config.json
  echo "config.json pushed"
fi
