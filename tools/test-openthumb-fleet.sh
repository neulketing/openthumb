#!/usr/bin/env bash
# Smoke test for openthumb-fleet's device enumeration, with a stub `adb` on PATH.
# No phones, no network — this exists to catch the one failure mode that is
# invisible in normal use: a command inside the `while read serial` loop eating
# stdin, which silently truncates the fleet to the devices seen so far. That bug
# shipped once (device_token had no `</dev/null`) and a fleet that reports 4 of
# 16 devices looks like an adb problem, not a script problem.
#
#   tools/test-openthumb-fleet.sh
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
FLEET="$HERE/openthumb-fleet"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

DEVICE_COUNT=8
fails=0

# Stub adb: 8 devices, all with the package, each yielding a token. `shell`
# deliberately reads stdin to EOF — a real `adb shell` does, and that is exactly
# what swallows the caller's serial list when the call is not fed </dev/null.
cat > "$TMP/adb" <<STUB
#!/usr/bin/env bash
if [ "\$1" = "devices" ]; then
  echo "List of devices attached"
  for i in \$(seq 1 $DEVICE_COUNT); do printf 'serial%02d\tdevice\n' "\$i"; done
  exit 0
fi
serial="\$2"; shift 2
case "\$1" in
  shell)
    cat >/dev/null            # drain stdin, like the real adb
    case "\$*" in
      *"pm list packages"*) echo "package:com.neulketing.openthumb" ;;
      *debug_server_token*)  echo "token-\$serial" ;;
    esac
    ;;
  forward) ;;
esac
exit 0
STUB
chmod +x "$TMP/adb"
export PATH="$TMP:$PATH"
export OPENTHUMB_FLEET_DIR="$TMP/fleet"

check() {
  local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then
    echo "ok   — $name"
  else
    echo "FAIL — $name: want [$want], got [$got]"
    fails=$((fails + 1))
  fi
}

# 1. Every attached device is listed. Truncation shows up here first.
got=$("$FLEET" devices 2>/dev/null | grep -c '^serial')
check "devices lists all $DEVICE_COUNT devices" "$DEVICE_COUNT" "$got"

# 2. A cold token cache must not shorten the list — the regression that shipped.
rm -rf "$OPENTHUMB_FLEET_DIR/tokens"
got=$("$FLEET" status 2>/dev/null | grep -c '^serial')
check "status covers all devices with a cold token cache" "$DEVICE_COUNT" "$got"

# 3. Tokens are actually read and cached per device, not shared.
check "token cached per device" "token-serial03" "$(cat "$OPENTHUMB_FLEET_DIR/tokens/serial03" 2>/dev/null)"

# 4. provision refuses to run without a key rather than creating keyless instances.
"$FLEET" provision >/dev/null 2>&1
check "provision without a key exits nonzero" "1" "$?"

[ "$fails" -eq 0 ] && echo "PASS" || echo "$fails check(s) failed"
exit "$fails"
