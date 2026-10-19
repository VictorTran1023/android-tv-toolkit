#!/usr/bin/env python3
"""解析 v2ray 订阅，列出每个节点的协议与加密方式。

作用：快速识别订阅里混进来的「明文节点」（security=none）。
新版 Xray 核心会拒绝连接明文节点，但机场常把"剩余流量""到期时间"
伪装成节点塞进列表，容易误点。

只输出节点名 / 协议 / 加密 / 传输 / 端口，**不打印 UUID、公钥等凭据**。

用法:
    python parse-sub.py "https://example.com/link/TOKEN?sub=3"
    python parse-sub.py "https://..." --out report.txt
"""
import argparse
import base64
import json
import sys
import urllib.parse
import urllib.request


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "v2rayNG/2.3.11"})
    raw = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "ignore").strip()
    if "://" not in raw:                       # base64 编码的订阅
        raw = base64.b64decode(raw + "=" * (-len(raw) % 4)).decode("utf-8", "ignore")
    return raw


def parse(raw):
    rows = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("vmess://"):
            try:
                obj = json.loads(base64.b64decode(line[8:] + "=" * (-len(line[8:]) % 4)))
                rows.append({
                    "name": obj.get("ps") or "(未命名)",
                    "proto": "vmess",
                    "security": obj.get("tls") or "none",
                    "transport": obj.get("net") or "tcp",
                    "port": obj.get("port", "?"),
                })
            except Exception as exc:
                rows.append({"name": "(解析失败)", "proto": "vmess",
                             "security": f"ERR {exc}", "transport": "-", "port": "-"})
            continue

        scheme, _, rest = line.partition("://")
        if scheme not in ("vless", "trojan", "ss", "hysteria2", "hy2"):
            rows.append({"name": "(未知协议)", "proto": scheme,
                         "security": "?", "transport": "?", "port": "?"})
            continue
        body, _, query = rest.partition("?")
        q = urllib.parse.parse_qs(query.split("#")[0])
        name = urllib.parse.unquote(rest.split("#", 1)[1]) if "#" in rest else "(未命名)"
        hostport = body.split("@")[-1]
        port = hostport.rsplit(":", 1)[-1] if ":" in hostport else "?"
        rows.append({
            "name": name,
            "proto": scheme,
            "security": (q.get("security", ["none"])[0]) or "none",
            "transport": q.get("type", ["tcp"])[0],
            "port": port,
        })
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--out", default="sub-report.txt")
    args = ap.parse_args()

    rows = parse(fetch(args.url))
    lines = ["%-4s %-26s %-8s %-10s %-8s %s" % ("#", "节点名", "协议", "加密", "传输", "端口"),
             "-" * 78]
    plain = []
    for i, r in enumerate(rows, 1):
        is_plain = str(r["security"]).lower() in ("none", "")
        if is_plain:
            plain.append(r["name"])
        lines.append("%-4d %-26s %-8s %-10s %-8s %-6s%s" % (
            i, r["name"][:26], r["proto"], r["security"], r["transport"], r["port"],
            "   << 明文!" if is_plain else ""))
    lines += ["", "合计 %d 个节点，其中 %d 个为明文（security=none）" % (len(rows), len(plain))]
    if plain:
        lines.append("明文节点: " + " / ".join(plain))

    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    print("已写入 %s（%d 个节点，%d 个明文）" % (args.out, len(rows), len(plain)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
