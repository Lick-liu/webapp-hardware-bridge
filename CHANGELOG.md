# Changelogs

## 1.3.1 - 2026-08-16

- Restrict browser HTTP and WebSocket access to exact configured origins instead of wildcard CORS.
- Require an expiring, one-time, shop-bound credential before persisting or correcting an activation cursor.
- Add an explicit cursor-correction route for authorized recovery from a bad first activation.

## 1.3.0 - 2026-08-15

- Persist one random local-print device ID per helper installation.
- Persist each shop's first activation task cursor with first-write-wins semantics.
- Expose fail-closed device-context APIs for the Web v3 delivery protocol.

## 1.2.0 - 2026-08-13

- Display the running application version in the Web UI and Windows installer metadata.
- Support multiple named printer mappings for independent receipt destinations.
- Make stable print job IDs replay-safe across concurrent browser tabs, retries, and helper restarts.
- Fail closed when print-job replay state cannot be persisted, so an uncertain job is never printed automatically again.

## From 0.x to 1.0.0

- 1.0 is a major rewrite, while maintain compatibility with existing WebApps
- Settings will lost after upgrade, please reconfigure via "Web UI" or "Web API"

### Feature changes
- Added per printer settings (Auto-rotate, DPI...)
- Added per serial port settings (Baud-rate, data bits, stop bit, parity bit, charset. binary mode, multi-bytes mode)
- Added "Web UI" for configuration, replacing "Configurator"
- Added "Web API", a HTTP API for WebApp to configure directly without using "Web UI" or "Configurator"
- Config file renamed from "setting.json" to "config.json", which is in different format

### Internal changes
- Removed "Configurator"
- Removed undocumented feature "Cloud Proxy"
- Removed usage of JavaFX
- Rewrite config code
- Implementation of WebSocket changed from "Java-WebSocket" to "Javalin"
- Internal dataflow optimization
- Simplified code by using "Lombok"
- Upgrade Java version from 8 to 21
- Many dependencies upgrades and security fixes
