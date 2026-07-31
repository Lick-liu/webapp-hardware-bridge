#!/usr/bin/env sh
set -eu

APP_NAME=webapp-hardware-bridge
SERVICE_NAME=${SERVICE_NAME:-webapp-hardware-bridge.service}
INSTALL_DIR=${INSTALL_DIR:-"$HOME/.local/opt/$APP_NAME"}

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_HOME=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ ! -f "$APP_HOME/lib/$APP_NAME.jar" ]; then
  echo "Run this script from an extracted Linux distribution directory." >&2
  exit 1
fi

if ! command -v systemctl >/dev/null 2>&1; then
  echo "systemctl is required for service installation." >&2
  exit 1
fi

JAVA_BIN=${JAVA_BIN:-$(command -v java 2>/dev/null || true)}
if [ -z "$JAVA_BIN" ]; then
  echo "Java 17 or newer is required. On Arch Linux, run: sudo pacman -S jre17-openjdk" >&2
  exit 1
fi

JAVA_BIN_DIR=$(CDPATH= cd -- "$(dirname -- "$JAVA_BIN")" && pwd)
JAVA_HOME_DIR=${JAVA_HOME:-$(CDPATH= cd -- "$JAVA_BIN_DIR/.." && pwd)}

mkdir -p "$(dirname "$INSTALL_DIR")"
if [ "$APP_HOME" != "$INSTALL_DIR" ]; then
  mkdir -p "$INSTALL_DIR"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a "$APP_HOME/" "$INSTALL_DIR/"
  else
    cp -R "$APP_HOME"/. "$INSTALL_DIR"/
  fi
fi

chmod +x "$INSTALL_DIR/bin/$APP_NAME" "$INSTALL_DIR/bin/$APP_NAME-server"

SERVICE_DIR="$HOME/.config/systemd/user"
SERVICE_FILE="$SERVICE_DIR/$SERVICE_NAME"
mkdir -p "$SERVICE_DIR"

cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=WebApp Hardware Bridge
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
Environment=JAVA_HOME=$JAVA_HOME_DIR
Environment=PATH=$JAVA_BIN_DIR:/usr/local/bin:/usr/bin:/bin
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/bin/$APP_NAME-server
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable "$SERVICE_NAME"
systemctl --user restart "$SERVICE_NAME"

echo "Installed $SERVICE_NAME"
echo "Application directory: $INSTALL_DIR"
echo "Service file: $SERVICE_FILE"
echo "Configuration UI: http://127.0.0.1:12212/"
echo
echo "Useful commands:"
echo "  systemctl --user start $SERVICE_NAME"
echo "  systemctl --user stop $SERVICE_NAME"
echo "  systemctl --user restart $SERVICE_NAME"
echo "  systemctl --user status $SERVICE_NAME"
echo "  journalctl --user -u $SERVICE_NAME -f"
