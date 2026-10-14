# easydens

Unofficial BLE control of the Anton Paar **EasyDens** digital density meter,
from a full protocol reverse-engineering of the official Brew Meister app.

- `PROTOCOL.md` — the complete wire protocol: GATT map, AES key derivation,
  RPC framing, all 8 commands, notification formats. Verified byte-for-byte
  against live HCI captures.
- `app/` — EasyDensCtrl, a macOS app (SwiftUI + CoreBluetooth): live decrypted
  density / temperature / battery, start-stop measurement, zero adjustment,
  LED + stand-by queries.
- `tools/easydens_ctrl.py` — CLI controller on bleak (macOS/Linux).

Everything here was produced by static analysis (REA + jadx-headless-mcp on
Brew Meister 6.7.0) cross-checked with runtime ground truth from a rooted
Android phone's Bluetooth HCI snoop log. Private repo — keep private.
