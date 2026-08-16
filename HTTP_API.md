# HTTP APIs

Browser access is restricted to the exact origins configured in
`server.allowedOrigins`. The helper enforces the same trust boundary before
HTTP handlers and WebSocket upgrades; CORS is not treated as a response-only
security control. Opaque `null` and untrusted origins are rejected.

You can get or update the current configuration in your WebApp directly by using the `/config.json` endpoint.

## GET /config.json

Get content of `config.json` file.

## PUT /config.json

Update content of `config.json` file.

## GET /system/printers.json

Return list of available printers.

## GET /system/printer-mappings.json

Return the configured printer routing types with physical printer display names
and current availability. This read-only endpoint intentionally omits the full
configuration and printer rendering options.

## GET /system/local-print-device-context.json?shopId={shopId}

Return the helper's durable random device ID and the first activation task ID
recorded for the requested shop. A missing context file is created atomically.
An invalid or unwritable context fails closed and never falls back to a
browser-local identity. The response also contains a short-lived opaque
`activationToken`, bound to this shop and device identity. Concurrent reads
reuse the current unexpired token.

## POST /system/local-print-device-context/activation.json

Persist a shop's non-negative `activationTaskId`. The JSON body contains
`shopId`, `activationTaskId`, and the `activationToken` returned by the context
read. The credential is one-time and expires after five minutes; missing,
expired, cross-shop, and replayed values are rejected. The first successful
cursor still wins; later ordinary activation calls return the stored value
without overwriting it.

## PUT /system/local-print-device-context/activation.json

Explicitly correct a stored activation cursor. The body has the same shape as
POST and requires a fresh activation credential. This recovery route is kept
separate so ordinary first-activation calls cannot silently replace a durable
cursor; the normal Web polling flow does not invoke it.

## GET /system/serials.json

Return list of available serial ports.

## POST /system/restart.json

Restart WebSocket/Web server
