#!/usr/bin/env bash
# Look inside Geococcyx on the connected watch (same adb connection as tools/install.sh).
#   tools/debug.sh            runs on the watch and where each has uploaded, then the last sync log lines
#   tools/debug.sh log [N]    last N lines of sync.log (default 50)
#   tools/debug.sh logcat     follow the app's live log (Ctrl-C to stop)
#   tools/debug.sh sync       open the app on the watch, which queues an upload of anything pending
#   tools/debug.sh pull [DIR] copy runs/ and sync.log off the watch (default ./watch-files)
set -euo pipefail
cd "$(dirname "$0")/.."
adb="$HOME/Android/Sdk/platform-tools/adb"
pkg=com.jameslongo.geococcyx
on_watch() { "$adb" shell "run-as $pkg sh -c '$1'"; }

if ! "$adb" devices | sed 1d | grep -qw device; then
  echo "No watch connected. Connect with: $adb connect <address from Wireless debugging>" >&2
  exit 1
fi

case "${1:-status}" in
  status)
    # Services this build uploads to, read from the config.json it was built and pushed with.
    services=$(python3 - <<'EOF' 2>/dev/null || echo "drive:drive"
import json
c = json.load(open("config.json"))
out = []
if c.get("google") and c.get("drive", True): out.append("drive:drive")
print(" ".join(out))
EOF
)
    echo "Runs on the watch (✓ uploaded, … pending):"
    files=$(on_watch "ls files/runs 2>/dev/null")
    runs=$(grep '\.tcx$' <<<"$files" | sort -r || true)
    [[ -z "$runs" ]] && echo "  (none)"
    for tcx in $runs; do
      base=${tcx%.tcx}
      line="  $base "
      for s in $services; do
        label=${s%%:*} ext=${s##*:}
        if grep -qx "$base.$ext" <<<"$files"; then line+=" $label ✓"; else line+=" $label …"; fi
      done
      echo "$line"
    done
    grep -qx current.journal <<<"$files" && echo "  (a run is in progress or was interrupted: current.journal)"
    echo
    echo "Last sync log lines:"
    on_watch "tail -n 10 files/sync.log 2>/dev/null" | sed 's/^/  /' || true
    ;;
  log) on_watch "tail -n ${2:-50} files/sync.log" ;;
  logcat)
    pid=$("$adb" shell pidof $pkg || true)
    if [[ -n "$pid" ]]; then "$adb" logcat --pid="$pid"; else "$adb" logcat -s Geococcyx; fi
    ;;
  sync)
    "$adb" shell am start -n $pkg/.MainActivity >/dev/null
    echo "Opened the app; the upload runs as soon as the watch has a connection. Check with: tools/debug.sh log"
    ;;
  pull)
    out=${2:-watch-files}
    mkdir -p "$out"
    "$adb" exec-out "run-as $pkg sh -c 'cd files && tar -cf - runs \$(ls sync.log 2>/dev/null)'" | tar -xf - -C "$out"
    echo "Copied to $out/"
    ;;
  *) sed -n 2,7p "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
