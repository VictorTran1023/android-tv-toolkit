#!/usr/bin/env python3
"""静态分析 APK：ABI、内核类型、TV 适配、关键权限。

用途：判断一个 APK 能不能装在 32 位电视上、是 Flutter/原生、用的是
Xray/sing-box/mihomo 哪个内核、有没有 LEANBACK（TV 桌面）入口。

不安装、不运行，纯解包读取。

用法:
    python analyze-apk.py app.apk
"""
import argparse
import re
import sys
import zipfile

CORE_MARKERS = {
    "libbox.so": "sing-box",
    "libflutter.so": "Flutter (Dart)",
    "libv2ray.so": "Xray/V2Ray",
    "libclash.so": "Clash",
    "libmihomo.so": "mihomo",
    "libgojni.so": "Go 原生库（多为代理内核）",
    "libreactnativejni.so": "React Native",
    "libhermes.so": "React Native (Hermes)",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    args = ap.parse_args()

    with zipfile.ZipFile(args.apk) as zf:
        names = zf.namelist()
        info = zf.infolist()
        manifest = zf.read("AndroidManifest.xml")

    print("== 体积 ==")
    print("  压缩后: %.1f MB   解压后: %.1f MB   条目: %d"
          % (sum(i.compress_size for i in info) / 1048576,
             sum(i.file_size for i in info) / 1048576, len(names)))

    abis = sorted({n.split("/")[1] for n in names
                   if n.startswith("lib/") and n.count("/") >= 2})
    print("\n== ABI ==")
    print("  " + (", ".join(abis) if abis else "无 native 库（纯 Java/Kotlin）"))
    if abis and "armeabi-v7a" not in abis:
        print("  !! 没有 armeabi-v7a：32 位设备（多数国产电视）装不上")

    libs = sorted({n.split("/")[-1] for n in names if n.startswith("lib/") and n.endswith(".so")})
    print("\n== 内核 / 框架 ==")
    for lib in libs:
        print("  %-26s %s" % (lib, CORE_MARKERS.get(lib, "")))
    if not any(l in CORE_MARKERS for l in libs):
        print("  （未见明显的代理内核库）")

    # 从二进制 manifest 里提取字符串
    strings = set()
    for m in re.finditer(rb"[\x20-\x7e]{4,}", manifest):
        strings.add(m.group().decode("ascii", "ignore"))
    for m in re.finditer(rb"(?:[\x20-\x7e]\x00){4,}", manifest):
        strings.add(m.group().decode("utf-16-le", "ignore"))

    pkg = sorted({s for s in strings
                  if re.match(r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+){2,}$", s)})
    print("\n== 疑似包名 ==")
    for p in pkg[:6]:
        print("  " + p)

    print("\n== 关键声明 ==")
    checks = {
        "TV 桌面入口 (LEANBACK)": "LEANBACK",
        "VPN 服务": "BIND_VPN_SERVICE",
        "前台服务": "FOREGROUND_SERVICE",
        "开机自启": "BOOT_COMPLETED",
        "安装应用": "REQUEST_INSTALL_PACKAGES",
        "悬浮窗": "SYSTEM_ALERT_WINDOW",
    }
    for label, key in checks.items():
        hit = any(key.lower() in s.lower() for s in strings)
        print("  %-24s %s" % (label, "有" if hit else "-"))

    tv_res = [n for n in names if re.search(r"leanback|banner|tv_", n, re.I)]
    print("\n== TV 适配痕迹 ==")
    print("  " + (", ".join(tv_res[:8]) if tv_res else "无 leanback/banner 资源"))

    rules = [n for n in names if re.search(r"\.(srs|dat)$", n) and
             re.search(r"geo|rule|site|ip", n, re.I)]
    if rules:
        print("\n== 内置规则集 ==")
        for r in rules[:8]:
            print("  " + r)

    return 0


if __name__ == "__main__":
    sys.exit(main())
