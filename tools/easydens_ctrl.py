#!/usr/bin/env python3
"""EasyDens (ED1/ED2) BLE controller — protocol reverse-engineered from
Brew Meister 6.7.0 (com.antonpaar.homebrew) via REA/JADX.

GATT service  41500000-d278-11eb-b8bc-0242ac130003  (ED2)
RPC frame (both directions): 0x80 | len | AES-CBC-PKCS7(cmd, iv=0*16)
  key = ASCII(serialNumber * 4)   — serial read from char 2a25 (8 chars → AES-256)
  reply plaintext = [opcode, status(0=ok), payload...]
Commands:  {1,0} info · {2} measure · {8,f32 off,f32 gain} zero-adjust ·
  {9,…} · {10} factory reset · {15} query · {16} get standby-time (→u16 min)
  · {17} query
All verified live: capture 17:16, serial 84182104, handshake {15},{16}→60min.
Usage: easydens_ctrl.py <cmd> [args...]   e.g. easydens_ctrl.py info
Requires Bluetooth permission for the terminal (System Settings → Privacy →
Bluetooth) on macOS.
"""
import asyncio
import struct
import sys

from bleak import BleakClient, BleakScanner

ED2_SVC = "41500000-d278-11eb-b8bc-0242ac130003"
ED1_SVC = "41500000-d3d9-11e4-b7a4-0002a5d5c51b"
CH = {
    "density": "41500001-d278-11eb-b8bc-0242ac130003",
    "temperature": "41500002-d278-11eb-b8bc-0242ac130003",
    "rpcRequest": "41500003-d278-11eb-b8bc-0242ac130003",
    "rpcReply": "41500004-d278-11eb-b8bc-0242ac130003",
}
# AES via the stdlib-free route: use cryptography if present, else PyCryptodome
try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.primitives.padding import PKCS7

    def aes_cbc(key, iv, data, decrypt=False):
        c = Cipher(algorithms.AES(key), modes.CBC(iv))
        if decrypt:
            op, unp = c.decryptor(), PKCS7(128).unpadder()
            return unp.update(op.update(data) + op.finalize()) + unp.finalize()
        op, pad = c.encryptor(), PKCS7(128).padder()
        return op.update(pad.update(data) + pad.finalize()) + op.finalize()
except ImportError:
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad, unpad

    def aes_cbc(key, iv, data, decrypt=False):
        c = AES.new(key, AES.MODE_CBC, iv)
        return unpad(c.decrypt(data), 16) if decrypt else c.encrypt(pad(data, 16))


def rpc_frame(name: str, cmd: bytes) -> bytes:
    key = (name * 4).encode()
    ct = aes_cbc(key, b"\0" * 16, cmd)
    return bytes([0x80, len(ct)]) + ct


def rpc_unframe(name: str, frame: bytes) -> bytes:
    key = (name * 4).encode()
    return aes_cbc(key, b"\0" * 16, frame[2:], decrypt=True)


COMMANDS = {
    "info": lambda a: bytes([1, 0]),
    "measure": lambda a: bytes([2]),
    "factory-reset": lambda a: bytes([10]),
    "zero-adjust": lambda a: bytes([8]) + struct.pack("<f", float(a[0])) + struct.pack("<f", 1.0),
    "query15": lambda a: bytes([15]),
    "standby-time": lambda a: bytes([16]),
    "query17": lambda a: bytes([17]),
}


SERIAL_UUID = "00002a25-0000-1000-8000-00805f9b34fb"  # key material lives here


def hexs(b):
    return " ".join(f"{x:02x}" for x in b)


async def main():
    cmd_name = sys.argv[1] if len(sys.argv) > 1 else "info"
    cmd = COMMANDS[cmd_name](sys.argv[2:])

    print("scanning 15s for EasyDens…")
    dev = None
    async with BleakScanner() as scanner:
        await asyncio.sleep(15)
        for d in scanner.discovered_devices:
            if d.name and "dens" in d.name.lower() or (
                    d.name and d.name.startswith(("ED", "Easy"))):
                dev = d
                break
    if not dev:
        sys.exit("no EasyDens found — power it on (LED flashing) and retry")
    print(f"found: {dev.address}  name={dev.name!r}  rssi={dev.rssi}")

    async with BleakClient(dev) as cl:
        serial = (await cl.read_gatt_char(SERIAL_UUID)).decode().strip()
        print(f"serial (2a25): {serial!r} -> AES-{len(serial)*4*8} key")
        frame = rpc_frame(serial, cmd)
        print(f"{cmd_name}: plain={hexs(cmd)}  frame={hexs(frame)}")

        replies = asyncio.get_event_loop().create_future()

        def on_reply(sender, data):
            print(f"rpcReply notify: {hexs(data)}")
            try:
                plain = rpc_unframe(serial, data)
                status = plain[1] if len(plain) > 1 else -1
                print(f"  decrypted: {hexs(plain)}  (op={plain[0]}, status={status})")
            except Exception as e:
                print(f"  decrypt failed: {e}")
            if not replies.done():
                replies.set_result(data)

        def on_density(sender, data):
            if len(data) >= 4:
                print(f"density notify: {hexs(data)} -> f32={struct.unpack('<f', data[:4])[0]:.4f}")

        def on_temp(sender, data):
            if len(data) >= 4:
                print(f"temperature notify: {hexs(data)} -> f32={struct.unpack('<f', data[:4])[0]:.2f}")

        await cl.start_notify(CH["rpcReply"], on_reply)
        await cl.start_notify(CH["density"], on_density)
        await cl.start_notify(CH["temperature"], on_temp)

        # also read current values once
        for k in ("density", "temperature"):
            try:
                v = await cl.read_gatt_char(CH[k])
                print(f"read {k}: {hexs(v)}")
            except Exception as e:
                print(f"read {k}: {e}")

        await cl.write_gatt_char(CH["rpcRequest"], frame, response=False)
        try:
            await asyncio.wait_for(replies, 10)
        except asyncio.TimeoutError:
            print("(no rpcReply within 10s)")


if __name__ == "__main__":
    asyncio.run(main())
