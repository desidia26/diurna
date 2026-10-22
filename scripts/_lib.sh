# Shared setup for scripts/. Source it; don't run it.
#
# Device choice: $ANDROID_SERIAL if set, else a plugged-in phone, else a running emulator.
# Gradle's installDebug/connectedAndroidTest honor ANDROID_SERIAL too, so everything hits the same device.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD="${AVD:-Pixel_3a_API_33_x86_64}"
PKG="app.daybook"            # the app id; kept from the app's first name, never changes

die() { echo "error: $*" >&2; exit 1; }

# Picks the target device and exports ANDROID_SERIAL.
#   pick_device            phone if plugged in, else emulator
#   pick_device emulator   emulator only, never the phone (for anything that wipes app data)
#   pick_device phone      the phone only, never an emulator (for releases)
pick_device() {
  local want="${1:-any}"
  local devices phone emu
  devices="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  phone="$(grep -v '^emulator-' <<<"$devices" | head -1 || true)"
  emu="$(grep '^emulator-' <<<"$devices" | head -1 || true)"
  if [[ "$want" == phone ]]; then
    [[ -z "${ANDROID_SERIAL:-}" || "$ANDROID_SERIAL" != emulator-* ]] || die "releases go to the phone, not $ANDROID_SERIAL"
    ANDROID_SERIAL="${ANDROID_SERIAL:-$phone}"
    [[ -n "$ANDROID_SERIAL" ]] || die "phone not connected. Turn on Wireless debugging (or plug it in)."
  elif [[ "$want" == emulator ]]; then
    [[ -z "${ANDROID_SERIAL:-}" || "$ANDROID_SERIAL" == emulator-* ]] \
      || die "refusing to use $ANDROID_SERIAL: this wipes Diurna's data, so it only runs on the emulator."
    ANDROID_SERIAL="${ANDROID_SERIAL:-$emu}"
    [[ -n "$ANDROID_SERIAL" ]] || die "no emulator running. Start one with: scripts/emulator --headless"
  else
    ANDROID_SERIAL="${ANDROID_SERIAL:-${phone:-$emu}}"
    [[ -n "$ANDROID_SERIAL" ]] || die "no device. Plug in the phone (USB debugging on) or run scripts/emulator."
  fi
  export ANDROID_SERIAL
  echo "device: $ANDROID_SERIAL ($("$ADB" shell getprop ro.product.model | tr -d '\r'))" >&2
}

adb() { "$ADB" "$@"; }

# The installed app's main screen, asked of the device: builds from before the code moved to the
# app.diurna package name it app.daybook.MainActivity. Falls back to the current name.
main_activity() {
  local found
  found="$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" | tail -1 | tr -d '\r')"
  [[ "$found" == "$PKG/"* ]] && echo "$found" || echo "$PKG/app.diurna.MainActivity"
}

gradle() { (cd "$ROOT" && ./gradlew --no-daemon -q "$@"); }
