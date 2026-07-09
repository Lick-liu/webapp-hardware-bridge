# Linux / Arch Linux

This archive is a portable Linux distribution for WebApp Hardware Bridge.

## Requirements

On Arch Linux:

```sh
sudo pacman -S jre17-openjdk cups
sudo systemctl enable --now cups.service
```

Configure printers in CUPS first. The bridge discovers printers through the
JVM print service, which uses the Linux printing stack.

## Run

```sh
tar -xzf webapp-hardware-bridge-*-linux-x86_64.tar.gz
cd webapp-hardware-bridge
./bin/webapp-hardware-bridge
```

For server-only mode:

```sh
./bin/webapp-hardware-bridge-server
```

Open the configuration UI at `http://127.0.0.1:12212/`.

Keep the extracted directory writable if you want the app to create or update
`config.json`, `downloads/`, `log/`, and generated TLS files.

## User service mode

For machines where the bridge should be easy to start and stop without keeping
a terminal open, install it as a user-level systemd service:

```sh
tar -xzf webapp-hardware-bridge-*-linux-x86_64.tar.gz
cd webapp-hardware-bridge
./scripts/install-user-service.sh
```

The helper installs the extracted application into
`~/.local/opt/webapp-hardware-bridge`, writes
`~/.config/systemd/user/webapp-hardware-bridge.service`, enables it, and starts
the server-only launcher. It records the current Java binary directory in the
service `PATH`, which helps when Java comes from user-level tools such as mise,
asdf, or SDKMAN rather than from `/usr/bin`.

Useful service commands:

```sh
systemctl --user start webapp-hardware-bridge.service
systemctl --user stop webapp-hardware-bridge.service
systemctl --user restart webapp-hardware-bridge.service
systemctl --user status webapp-hardware-bridge.service
journalctl --user -u webapp-hardware-bridge.service -f
```

If you want the user service to keep running without an active graphical login,
enable linger for that user:

```sh
sudo loginctl enable-linger "$USER"
```

Serial devices may require group access. On Arch Linux, add the user to `uucp`
when serial ports are not visible or cannot be opened:

```sh
sudo usermod -aG uucp "$USER"
```

Log out and back in after changing groups.
