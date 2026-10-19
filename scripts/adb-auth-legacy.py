#!/usr/bin/env python3
"""用「传统二进制格式」公钥向 Android 设备请求 ADB 授权。

某些厂商深度定制的 adbd（如 TCL 电视）只认老格式公钥，
而 adb 34+ 发送的是 base64 新格式，导致设备解析失败、直接拒绝、**永不弹窗**。
本脚本手工握手并用传统格式发送公钥，同时保持连接等待用户确认。

传统格式 payload:
    uint32 LE 模数长度 | 模数(大端) | uint32 LE 指数 | "user@host\\0"

用法:
    python adb-auth-legacy.py --host 192.168.1.100
    python adb-auth-legacy.py --host 192.168.1.100 --port 5555 --wait 180
"""
import argparse
import base64
import hashlib
import os
import socket
import struct
import sys
import time

A_CNXN = 0x4E584E43
A_AUTH = 0x48545541
A_STLS = 0x534C5453


def read_pem(path):
    lines = open(path, "r", encoding="utf-8").read().splitlines()
    b64 = "".join(l for l in lines if not l.startswith("-----") and l.strip())
    return base64.b64decode(b64)


def tlv(buf, off=0):
    tag = buf[off]
    ln = buf[off + 1]
    hdr = 2
    if ln & 0x80:
        n = ln & 0x7F
        ln = int.from_bytes(buf[off + 2:off + 2 + n], "big")
        hdr = 2 + n
    return tag, buf[off + hdr:off + hdr + ln], off + hdr + ln


def load_key(path):
    """解析 PKCS#8 私钥，取出 n / e / d。"""
    der = read_pem(path)
    _, body, _ = tlv(der, 0)            # PKCS#8 SEQUENCE
    p = 0
    _, _, p = tlv(body, p)              # version
    _, _, p = tlv(body, p)              # algorithm
    tag, pkcs1, p = tlv(body, p)        # OCTET STRING
    assert tag == 0x04, "unexpected PKCS#8 layout"
    _, seq, _ = tlv(pkcs1, 0)           # RSAPrivateKey SEQUENCE
    q = 0
    _, _, q = tlv(seq, q)               # version
    _, n_b, q = tlv(seq, q)
    _, e_b, q = tlv(seq, q)
    _, d_b, q = tlv(seq, q)
    n = int.from_bytes(n_b, "big")
    e = int.from_bytes(e_b, "big")
    d = int.from_bytes(d_b, "big")
    return n, e, d, (n.bit_length() + 7) // 8


def sign(token, n, d, k):
    """PKCS#1 v1.5 + SHA1 签名（adbd 用的是 SHA1）。"""
    digest = hashlib.sha1(token).digest()
    digest_info = bytes.fromhex("3021300906052b0e03021a05000414") + digest
    padding = b"\xff" * (k - len(digest_info) - 3)
    em = b"\x00\x01" + padding + b"\x00" + digest_info
    return pow(int.from_bytes(em, "big"), d, n).to_bytes(k, "big")


def pack(cmd, a0, a1, data=b""):
    checksum = sum(data) & 0xFFFFFFFF
    return struct.pack("<6I", cmd, a0, a1, len(data), checksum, cmd ^ 0xFFFFFFFF) + data


def read_pkt(sock):
    hdr = b""
    while len(hdr) < 24:
        chunk = sock.recv(24 - len(hdr))
        if not chunk:
            return None
        hdr += chunk
    cmd, a0, a1, ln, _cks, _magic = struct.unpack("<6I", hdr)
    data = b""
    while len(data) < ln:
        chunk = sock.recv(ln - len(data))
        if not chunk:
            break
        data += chunk
    return cmd, a0, a1, data


def main():
    ap = argparse.ArgumentParser(description="传统格式 ADB 公钥授权")
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=5555)
    ap.add_argument("--key", default=os.path.expanduser("~/.android/adbkey"),
                    help="adb 私钥路径（默认 ~/.android/adbkey）")
    ap.add_argument("--wait", type=int, default=180, help="等待用户确认的秒数")
    ap.add_argument("--name", default="host@host", help="公钥里附带的主机名")
    args = ap.parse_args()

    if not os.path.exists(args.key):
        sys.exit(f"找不到私钥: {args.key}\n先运行 `adb keygen {args.key}` 生成")

    n, e, d, k = load_key(args.key)
    fingerprint = hashlib.sha256(n.to_bytes(k, "big")).hexdigest()
    print(f"[key] {k*8}-bit  sha256={fingerprint[:32]}...")

    blob = (struct.pack("<I", k) + n.to_bytes(k, "big")
            + struct.pack("<I", e) + args.name.encode() + b"\x00")
    print(f"[pub] 传统格式公钥 {len(blob)} 字节")

    sock = socket.create_connection((args.host, args.port), timeout=10)
    sock.sendall(pack(A_CNXN, 0x01000000, 4096, b"host::\x00"))
    print(f"[cnxn] 已连接 {args.host}:{args.port}，等待授权流程…")

    signed = sent_pub = False
    deadline = time.time() + args.wait
    sock.settimeout(1.0)

    while time.time() < deadline:
        try:
            pkt = read_pkt(sock)
        except socket.timeout:
            continue
        except OSError as exc:
            print(f"[err] 连接中断: {exc}")
            return 2
        if pkt is None:
            print("[err] 设备关闭了连接")
            return 2

        cmd, a0, _a1, data = pkt
        if cmd == A_CNXN:
            print(f"[OK] 设备已授权！banner={data[:80]!r}")
            return 0
        if cmd == A_AUTH:
            if a0 == 1:                       # 设备发来挑战
                if not signed:
                    sock.sendall(pack(A_AUTH, 2, 0, sign(data, n, d, k)))
                    signed = True
                    print("[auth] 已发送签名（设备不认识，属预期）")
                elif not sent_pub:
                    sock.sendall(pack(A_AUTH, 3, 0, blob))
                    sent_pub = True
                    print("[auth] 已发送传统格式公钥 → 电视应弹出授权框")
                    print("[auth] 请在电视上勾选「一律允许」并确定，此处保持等待…")
            elif a0 == 3:                     # 设备要求公钥
                if not sent_pub:
                    sock.sendall(pack(A_AUTH, 3, 0, blob))
                    sent_pub = True
                    print("[auth] 已发送公钥，等待弹窗…")
        if cmd == A_STLS:
            print("[auth] 设备要求 STLS（新协议），老格式不适用")
    print("[timeout] 等待超时，未获得授权")
    return 3


if __name__ == "__main__":
    sys.exit(main())
