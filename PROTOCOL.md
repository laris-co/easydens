# EasyDens (ED2) BLE Protocol

Reverse-engineered from Brew Meister 6.7.0 (`com.antonpaar.homebrew`, build 679)
via REA + jadx-headless-mcp 0.7.1, and verified byte-for-byte against a live
HCI snoop capture (rooted Galaxy A14, `persist.bluetooth.btsnooplogmode full`,
Samsung log path `/data/log/bt/btsnoop_hci.log`) on 2026-10-14.

## GATT

Service `41500000-d278-11eb-b8bc-0242ac130003` (EasyDens gen 2; gen 1 uses
`41500000-d3d9-11e4-b7a4-0002a5d5c51b` with per-characteristic suffixes to match):

| characteristic | properties | meaning |
|---|---|---|
| `41500001-…0003` | read, notify | density, **encrypted**, see format |
| `41500002-…0003` | read, notify | temperature, **encrypted** |
| `41500003-…0003` | write-no-response | RPC request |
| `41500004-…0003` | read, notify | RPC reply, encrypted |
| `41500005-…0003` | read, notify | battery/supply voltage, **plaintext f32 LE** |

The device advertises its **serial number as its BLE name** (e.g. `84182104`),
also readable from GAP `2a25`. Device name "Q", manufacturer "Anton Paar GmbH".

## Crypto

```
key   = ASCII(deviceName * 4)        # 8-char serial -> 32 bytes -> AES-256
iv    = 16 zero bytes
mode  = AES-CBC, PKCS7 padding
```
Derived from `du0.G()` / `xkc.m()` / `o83` in the APK: the key is literally the
device-name bytes concatenated four times. Everything (RPC both directions,
density and temperature notifications) is encrypted with this key.

## RPC framing

Both directions, on `41500003` (write) and `41500004` (notify):

```
0x80 | len(ciphertext) | AES-CBC-PKCS7(command bytes)
reply plaintext = [opcode, status(0=ok), payload...]
```

Value notifications (`41500001`/`41500002`) carry no `0x80` header: the
notification payload is a single AES block; plaintext = `f32 LE` + PKCS7.

## Commands (plaintext opcodes)

| bytes | command | reply payload |
|---|---|---|
| `01 00` | start measurement | status |
| `02` | stop measurement | status |
| `08 ‖ f32LE offset ‖ f32LE gain` | set zero adjustment | ED2ErrorCode |
| `09 ‖ u16LE seconds` | set stand-by time | status |
| `0A` | factory reset | ED2ErrorCode |
| `0F` | get LED intensity | intensity |
| `10` | get stand-by time | u16LE minutes (observed 60) |
| `11` | get zero adjustment | offset, gain |

Observed connect handshake from the official app: `{0F}`, `{10}`, `{01 00}` —
i.e. read LED + stand-by, then **start measurement immediately** (values stream
continuously while connected).

Observed live values (2026-10-14): temperature 30.33 °C, density 0.00244 g/cm³
(empty cell, air), battery 2.87 V.

## Tools

- `app/` — EasyDensCtrl, a macOS SwiftUI + CoreBluetooth app (scan, connect,
  handshake, live decrypted density/temperature/battery, all commands).
  Build: `swiftc -O -parse-as-library -o app/EasyDensCtrl ...` (see `app/build.sh`).
- `tools/easydens_ctrl.py` — cross-platform bleak controller (CLI, one command
  per invocation).

Note: every adhoc re-sign of the Mac app resets macOS Bluetooth (TCC)
authorization; sign with a stable identity or re-grant after rebuilding.
