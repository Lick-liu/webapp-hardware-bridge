# HTTP APIs

All endpoints have CORS configured to allow requests from any origin.

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
browser-local identity.

## POST /system/local-print-device-context/activation.json

Persist a shop's non-negative `activationTaskId`. The JSON body contains
`shopId` and `activationTaskId`. The first successful value wins; later calls
return the stored value without overwriting it.

## GET /system/serials.json

Return list of available serial ports.

## POST /system/restart.json

Restart WebSocket/Web server
