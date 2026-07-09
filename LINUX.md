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
