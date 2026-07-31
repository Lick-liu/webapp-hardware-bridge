#!/usr/bin/env bash
set -euo pipefail

QUEUE_NAME="${QUEUE_NAME:-XP58}"
MODEL="${MODEL:-xp58}"
DEVICE_URI="${DEVICE_URI:-}"
PRINT_TEST="${PRINT_TEST:-0}"
SET_DEFAULT="${SET_DEFAULT:-1}"
ZJ58_REPO_URL="${ZJ58_REPO_URL:-https://github.com/klirichek/zj-58.git}"
BUILD_ROOT="${BUILD_ROOT:-$(mktemp -d)}"

PACMAN_DEPS=(cups cups-filters ghostscript git cmake base-devel)
FILTER_DST="/usr/lib/cups/filter/rastertozj"
PPD_DIR="/usr/share/cups/model/zjiang"
PPD_DST="$PPD_DIR/${MODEL}.ppd"
SUDO=(sudo)

if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  SUDO=()
fi

cleanup() {
  if [[ -n "${BUILD_ROOT:-}" && -d "$BUILD_ROOT" && "$BUILD_ROOT" == /tmp/* ]]; then
    rm -rf "$BUILD_ROOT"
  fi
}
trap cleanup EXIT

log() {
  printf '[xprinter-xp58] %s\n' "$*"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing command: $1" >&2
    exit 1
  fi
}

install_arch_deps() {
  if command -v pacman >/dev/null 2>&1; then
    log "Installing dependencies with pacman..."
    "${SUDO[@]}" pacman -S --needed "${PACMAN_DEPS[@]}"
    return
  fi

  echo "This installer can only auto-install dependencies on Arch/CachyOS." >&2
  echo "Please install these packages manually, then rerun:" >&2
  echo "  ${PACMAN_DEPS[*]}" >&2
}

ensure_cups_running() {
  if command -v systemctl >/dev/null 2>&1; then
    "${SUDO[@]}" systemctl enable --now cups.service
    "${SUDO[@]}" systemctl restart cups.service
  fi
}

patch_zj58_for_new_toolchains() {
  local source_file="$1"

  if grep -q 'void cancelJob()' "$source_file"; then
    log "Patching signal handler signature for newer GCC..."
    sed -i 's/void cancelJob()/void cancelJob(int signo)/' "$source_file"
    sed -i '/void cancelJob(int signo) {/a\  (void)signo;' "$source_file"
  fi
}

detect_xprinter_uri() {
  if [[ -n "$DEVICE_URI" ]]; then
    printf '%s\n' "$DEVICE_URI"
    return
  fi

  local uri
  uri="$(lpinfo -v | awk '/^direct usb:\/\/Xprinter\/USB%20Printer%20Port/ {print $2; exit}')"
  if [[ -z "$uri" ]]; then
    uri="$(lpinfo -v | awk '/^direct usb:\/\// && /Xprinter|XP-58|ZJ-58|Printer%20Port/ {print $2; exit}')"
  fi
  printf '%s\n' "$uri"
}

install_arch_deps
require_command git
require_command cmake
require_command lpinfo
require_command lpadmin
require_command gs

ensure_cups_running

SRC_DIR="$BUILD_ROOT/zj-58"
log "Cloning driver source: $ZJ58_REPO_URL"
git clone --depth 1 "$ZJ58_REPO_URL" "$SRC_DIR"
patch_zj58_for_new_toolchains "$SRC_DIR/rastertozj.c"

log "Building CUPS filter and PPD files..."
cmake -S "$SRC_DIR" -B "$SRC_DIR/build" -DCMAKE_POLICY_VERSION_MINIMUM=3.5
cmake --build "$SRC_DIR/build"

FILTER_SRC="$SRC_DIR/build/rastertozj"
PPD_SRC="$SRC_DIR/build/ppd/${MODEL}.ppd"

if [[ ! -x "$FILTER_SRC" ]]; then
  echo "Build failed: missing $FILTER_SRC" >&2
  exit 1
fi

if [[ ! -f "$PPD_SRC" ]]; then
  echo "Build failed: missing $PPD_SRC" >&2
  echo "Available PPD files:" >&2
  find "$SRC_DIR/build/ppd" -maxdepth 1 -type f -name '*.ppd' -print >&2 || true
  exit 1
fi

USB_URI="$(detect_xprinter_uri)"
if [[ -z "$USB_URI" ]]; then
  echo "Could not find XPrinter USB device." >&2
  echo "Check that the printer is powered on and connected, then inspect:" >&2
  echo "  lpinfo -v" >&2
  echo "You can also pass DEVICE_URI manually:" >&2
  echo "  DEVICE_URI='usb://Xprinter/USB%20Printer%20Port?serial=...' $0" >&2
  exit 1
fi

log "Installing rastertozj filter and ${MODEL}.ppd..."
"${SUDO[@]}" install -d -m 755 "$PPD_DIR"
"${SUDO[@]}" install -m 755 "$FILTER_SRC" "$FILTER_DST"
"${SUDO[@]}" install -m 644 "$PPD_SRC" "$PPD_DST"

ensure_cups_running

log "Creating CUPS queue: $QUEUE_NAME"
"${SUDO[@]}" lpadmin -p "$QUEUE_NAME" -E -v "$USB_URI" -P "$PPD_DST"
"${SUDO[@]}" cupsenable "$QUEUE_NAME"
"${SUDO[@]}" cupsaccept "$QUEUE_NAME"

if [[ "$SET_DEFAULT" == "1" ]]; then
  "${SUDO[@]}" lpadmin -d "$QUEUE_NAME"
fi

log "Installed queue: $QUEUE_NAME"
log "Device URI: $USB_URI"
log "Test command:"
printf "printf 'XP-58 test\\\\n\\\\n\\\\n' | lp -d '%s'\n" "$QUEUE_NAME"

if [[ "$PRINT_TEST" == "1" ]]; then
  printf 'XP-58 test\n\n\n' | lp -d "$QUEUE_NAME"
fi
