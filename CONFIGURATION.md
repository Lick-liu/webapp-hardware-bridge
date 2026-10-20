# Configurations

## Web/WebSocket Server

### Bind

- (Default) `127.0.0.1` 
- `127.0.0.1` for normal usage
- `0.0.0.0` for open to internet access (Not recommended)
- Other interface address accepted

### Address

- (Default) `127.0.0.1` 
- `127.0.0.1` unless you need to allow internal/internet access
- IP address / Domain name accepted

### Port

- (Default) `12212`
- Range: `1024` - `65535`

### Allowed browser origins

`server.allowedOrigins` is an exact allowlist of browser origins. Each value must
contain only `http` or `https`, host, and optional port; wildcards, paths, query
strings, fragments, and opaque `null` origins are rejected. The helper's own
configured URI is added automatically.

Defaults:

- `https://dev.teahouses.cn`
- `https://test.teahouses.cn`
- `https://shop.teahouses.cn`
- `http://localhost:3333`
- `http://127.0.0.1:3333`

Browser HTTP and WebSocket requests from other origins are rejected. A local
non-browser client may perform origin-less safe reads; state-changing HTTP and
origin-less WebSocket access require the configured authentication token.

### Enable authentication

[See Authentication for more detail](ADVANCED.md#authentication)

- (Default) `false`

#### Token

- (Default) Blank
- Accept any text value

### Enable TLS

[See HTTPS/WSS Support for more detail](ADVANCED.md#httpswss-support)

- (Default) `false`

#### Self Signed

- (Default) `true`

#### Cert

- (Default) `tls/default-cert.pem`

#### Key

- (Default) `tls/default-cert.pem`

#### CA Bundle

- (Default) Empty

## Downloader

### Path

Directory to save downloaded files

- (Default) `download`

### Timeout

Seconds before download timeout

- (Default) `30`

### Ignore TLS certificate error

Ignore any TLS certificate error (self-signed, expired...) when downloading files

Not recommended for normal usage, useful in some corporate networks where firewall doing MITM

- (Default) `false`

## Printers

### Enabled

- (Default) `true`

### Auto add unknown type

Auto add type mapping to configuration when document received with unknown type

- (Default) `false`

### Fallback to default printer if none matched

Fallback to default printer if none of the printers matched in configuration

- (Default) `false`

#### Type

Mapping key between WebApp and physically printer name in operating system

#### Printer Name

Printer name in operating system

#### Auto Rotate

Auto rotate portrait / landscape

- (Default) `false`

#### Reset imageable area

Required by some printer to handle size correctly

- (Default) `true`

#### Force DPI

Required by some printer/operating system to handle DPI correctly

- (Default) `0` 
-  `0` - Auto detect
- Common values: `213`, `300`

Printer mapping types must be non-blank, at most 64 characters, and unique.
Keep a `RECEIPT` mapping for compatibility with older WebApps. Additional
business routes can use stable keys such as `FRONT` and `KITCHEN_A`:

```json
{
  "printer": {
    "enabled": true,
    "autoAddUnknownType": false,
    "fallbackToDefault": false,
    "mappings": [
      { "type": "RECEIPT", "name": "Front Printer" },
      { "type": "FRONT", "name": "Front Printer" },
      { "type": "KITCHEN_A", "name": "Kitchen Printer" }
    ]
  }
}
```

## Serials

### Enabled

- (Default) `true`

#### Type

Mapping key between WebApp and physically serial port name in operating system

#### Serial Port

Serial port name in operating system

#### Baud Rate

Auto-detect when leave blank

- (Default) Blank

#### Data Bits

Auto-detect when leave blank

- (Default) Blank

#### Stop Bits

Auto-detect when leave blank

- (Default) Blank

#### Parity

Auto-detect when leave blank

- (Default) Blank

#### Read Charset

Charset to decode data received from serial port

Changing this may break compatibility with WebApp integrated with pre-1.0 version

- (Default) `UTF-8`
- `UTF-8` - Data will be sent to WebSocket as UTF-8 `string`
- `US-ASCII` - Data will be sent to WebSocket as ASCII `string`
- `BINARY` - Data will be sent to WebSocket as `blob`

#### Read Multi-bytes

Read all available bytes in serial port and send them to WebSocket at once

Changing this may break compatibility with WebApp integrated with pre-1.0 version

- (Default) `false`
