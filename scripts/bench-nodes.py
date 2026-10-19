#!/usr/bin/env python3
"""逐节点测吞吐：起一个临时 sing-box 实例（本地 mixed 代理），跑真实下载测速。

支持单流 / 多流并发，用来区分「节点总带宽」和「单条连接能跑多少」。
RTT 大的线路单流会被 TCP 窗口限制，多流才能压出真实带宽。

依赖: sing-box 可执行文件（https://github.com/SagerNet/sing-box/releases）
用法:
    python bench-nodes.py "https://example.com/link/TOKEN?sub=3" --singbox ./sing-box
    python bench-nodes.py "https://..." --singbox ./sing-box --streams 4
"""
import argparse
import base64
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

TEST_URL = "https://speed.cloudflare.com/__down?bytes=50000000"


def fetch_nodes(url):
    req = urllib.request.Request(url, headers={"User-Agent": "v2rayNG/2.3.11"})
    raw = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "ignore").strip()
    if "://" not in raw:
        raw = base64.b64decode(raw + "=" * (-len(raw) % 4)).decode("utf-8", "ignore")

    nodes = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("vless://"):
            continue
        body, _, query = line.partition("?")
        q = urllib.parse.parse_qs(query.split("#")[0])
        if q.get("security", ["none"])[0] != "reality":
            continue
        name = urllib.parse.unquote(line.split("#", 1)[1]) if "#" in line else "(未命名)"
        creds, _, hostport = body[8:].rpartition("@")
        host, _, port = hostport.rpartition(":")
        nodes.append({
            "name": name, "server": host, "port": int(port),
            "uuid": urllib.parse.unquote(creds),
            "flow": q.get("flow", [""])[0],
            "sni": q.get("sni", [""])[0],
            "pbk": q.get("pbk", [""])[0],
            "sid": q.get("sid", [""])[0],
            "fp": q.get("fp", ["chrome"])[0],
        })
    return nodes


def build_config(node, port):
    outbound = {
        "type": "vless", "tag": "proxy",
        "server": node["server"], "server_port": node["port"], "uuid": node["uuid"],
        "tls": {
            "enabled": True, "server_name": node["sni"],
            "utls": {"enabled": True, "fingerprint": node["fp"]},
            "reality": {"enabled": True, "public_key": node["pbk"], "short_id": node["sid"]},
        },
    }
    if node["flow"]:
        outbound["flow"] = node["flow"]
    return {
        "log": {"level": "error"},
        "inbounds": [{"type": "mixed", "listen": "127.0.0.1", "listen_port": port}],
        "outbounds": [outbound],
    }


def wait_port(port, timeout=6):
    end = time.time() + timeout
    while time.time() < end:
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.4).close()
            return True
        except OSError:
            time.sleep(0.15)
    return False


def measure(node, singbox, streams, seconds, port):
    cfg = os.path.join(tempfile.gettempdir(), "sb-bench.json")
    with open(cfg, "w", encoding="utf-8") as fh:
        json.dump(build_config(node, port), fh)

    proc = subprocess.Popen([singbox, "run", "-c", cfg],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        if not wait_port(port):
            return None, "sing-box 启动失败"

        opener = urllib.request.build_opener(urllib.request.ProxyHandler({
            "http": f"http://127.0.0.1:{port}",
            "https": f"http://127.0.0.1:{port}",
        }))
        total = [0]
        lock = threading.Lock()

        def worker():
            got, t0 = 0, time.time()
            try:
                req = urllib.request.Request(TEST_URL, headers={"User-Agent": "curl/8"})
                with opener.open(req, timeout=8) as resp:
                    while time.time() - t0 < seconds:
                        chunk = resp.read(131072)
                        if not chunk:
                            break
                        got += len(chunk)
            except Exception:
                pass
            with lock:
                total[0] += got

        t0 = time.time()
        threads = [threading.Thread(target=worker) for _ in range(streams)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        elapsed = max(time.time() - t0, 0.1)
        mbps = total[0] * 8 / elapsed / 1_000_000
        return mbps, f"{total[0]/1048576:.1f} MB / {elapsed:.1f}s"
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--singbox", required=True, help="sing-box 可执行文件路径")
    ap.add_argument("--streams", type=int, default=1, help="并发连接数（默认 1）")
    ap.add_argument("--seconds", type=int, default=10, help="每个节点测试时长")
    ap.add_argument("--port", type=int, default=10898, help="本地代理端口")
    args = ap.parse_args()

    nodes = fetch_nodes(args.url)
    print(f"节点数: {len(nodes)}   并发: {args.streams}   每节点: {args.seconds}s\n")

    results = []
    for i, node in enumerate(nodes, 1):
        mbps, note = measure(node, args.singbox, args.streams, args.seconds, args.port)
        tag = f"{mbps:6.2f} Mbps" if mbps else "   失败  "
        print(f"[{i:2d}/{len(nodes)}] {node['name'][:22]:<22} {tag}   {note}", flush=True)
        results.append((node["name"], mbps))

    ok = [r for r in results if r[1]]
    if ok:
        print("\n===== 排名 =====")
        for name, mbps in sorted(ok, key=lambda x: -x[1]):
            print(f"{mbps:6.2f} Mbps  {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
