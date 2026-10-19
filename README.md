<div align="center">

# TCL Android TV Toolkit

局域网发现 · ADB 授权 · 侧载安装 · 代理链路 · 1080p 稳定播放

![Platform](https://img.shields.io/badge/Android-11-3DDC84?logo=android&logoColor=white&style=flat-square)
![ABI](https://img.shields.io/badge/ABI-armeabi--v7a-F5A623?style=flat-square)
![SoC](https://img.shields.io/badge/SoC-MediaTek%20MT5879-0A84FF?style=flat-square)
![Python](https://img.shields.io/badge/Python-3.9%2B-3776AB?logo=python&logoColor=white&style=flat-square)
![License](https://img.shields.io/badge/License-MIT-8E8E93?style=flat-square)

[中文](README.md) · [English](README.en.md) · [Discussions](https://github.com/VictorTran1023/tcl-tv-guide/discussions)

</div>

---

## 概览

| 项目 | 值 |
| --- | --- |
| 机型 | TCL T7K |
| SoC | MediaTek MT5879（4 核 ARMv8） |
| 系统 | Android 11 / SDK 30 |
| 架构 | 64-bit SoC，**32-bit userspace** |
| 面板 | 3840×2160 / HDR10 |
| 硬解 | AVC · HEVC · VP9 · AV1 · VVC · Dolby Vision |
| 实测结论 | ADB、侧载、代理、YouTube 全链路可用 |

本仓库解决四个问题：

| 问题 | 真正原因 | 本文解法 |
| --- | --- | --- |
| 连不上 | 公钥格式不兼容 | 传统格式握手 |
| 装不上 | TCL 定制 PMS 静默拒绝 | 走有 UI 的安装通道 |
| 点不动 | UI 未适配遥控器 | 选择 TV 友好客户端 |
| 播不动 | 码率超出带宽 | 质量上限 + 节点选型 |

## 快速开始

```bash
git clone https://github.com/VictorTran1023/tcl-tv-guide.git
cd tcl-tv-guide

adb connect <TV_IP>:5555
adb devices -l

python scripts/parse-sub.py "<SUBSCRIPTION_URL>"
python scripts/bench-nodes.py "<SUBSCRIPTION_URL>" --singbox /path/to/sing-box
```

## 目录

| # | 章节 | 内容 |
| --- | --- | --- |
| 1 | [环境侦测](#1-环境侦测) | ABI、SoC、内核位数 |
| 2 | [局域网发现](#2-局域网发现) | ARP 扫描、SSDP、mDNS |
| 3 | [开启 ADB](#3-开启-adb) | TCL 专有路径 |
| 4 | [授权突破](#4-授权突破) | 传统公钥握手 |
| 5 | [侧载安装](#5-侧载安装) | 绕过安装校验 |
| 6 | [代理客户端](#6-代理客户端) | FlClash 与订阅导入 |
| 7 | [YouTube](#7-youtube) | SmartTube 与画质设置 |
| 8 | [性能与带宽](#8-性能与带宽) | 解码能力与实测数据 |
| 9 | [故障速查](#9-故障速查) | 九类典型故障 |
| 10 | [脚本](#10-脚本) | 四个 CLI 工具 |

---

## 1. 环境侦测

```bash
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abilist64
adb shell getprop ro.zygote
adb shell uname -m
```

T7K 实测：

| 检查项 | 值 | 判定 |
| --- | --- | --- |
| `abilist` | `armeabi-v7a,armeabi` | 仅 32 位 |
| `abilist64` | *空* | 无 64 位支持 |
| `zygote` | `zygote32` | 32 位进程模型 |
| `uname -m` | `armv8l` | 内核运行于 AArch32 |

只含 `arm64-v8a` 的 APK 会报 `INSTALL_FAILED_NO_MATCHING_ABIS`。

---

## 2. 局域网发现

ICMP 常被防火墙丢弃，改用 TCP 触发 ARP：

```powershell
1..254 | ForEach-Object -Parallel {
  $ip = "192.168.1.$_"
  $c = [System.Net.Sockets.TcpClient]::new()
  try { if ($c.ConnectAsync($ip, 5555).Wait(250) -and $c.Connected) { $ip } }
  catch {} finally { $c.Dispose() }
} -ThrottleLimit 128

arp -a | Select-String '192\.168\.1\.'
```

SSDP 查询可直接拿到型号：

```powershell
$msg = "M-SEARCH * HTTP/1.1`r`nHOST: 239.255.255.250:1900`r`nMAN: `"ssdp:discover`"`r`nMX: 2`r`nST: ssdp:all`r`n`r`n"
$udp = [System.Net.Sockets.UdpClient]::new()
$ep  = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Parse('239.255.255.250'), 1900)
$b   = [Text.Encoding]::ASCII.GetBytes($msg)
[void]$udp.Send($b, $b.Length, $ep)
```

`description.xml` 中的设备标识：

```xml
<modelName>tcl_mt5879_cn</modelName>
<X_brandName>TCL</X_brandName>
<channelName>tcl</channelName>
```

mDNS 的 `_airplay._tcp.local` TXT 记录 `model=AppleTV3,2` 表示支持 AirPlay 2，可直接从 iPhone / Mac 投屏。

---

## 3. 开启 ADB

```
设置 → 关于本机 → 本机信息 → 遥控器依次按：上 下 左 右
```

页面刷出 `ADB` 开关，打开即可。该固件的开发者选项仅有此一项。

> ADB 开关在每次断电后复位，属固件设计。

```bash
adb connect <TV_IP>:5555
adb devices -l
# <TV_IP>:5555  device  product:tcl_mt5879_cn_db  model:tcl_mt5879_cn
```

---

## 4. 授权突破

**现象**

```
failed to authenticate to <TV_IP>:5555
<TV_IP>:5555    unauthorized
```

电视不显示授权弹窗。

**定位**

```
[AUTH] arg0=1 (len=20)      device challenge
[AUTH] arg0=2 (len=256)     signature
[AUTH] arg0=1 (len=20)      rejected
[AUTH] arg0=3 (len=712)     public key  ← base64, new encoding
```

adb 34+ 使用 712 字节 base64 公钥；该固件的 adbd 仅识别传统二进制结构，解析失败即拒绝，
不进入弹窗流程。

**解法**

```bash
python scripts/adb-auth-legacy.py --host <TV_IP> --port 5555
```

脚本发送以下结构并保持连接，等待电视端确认：

```
uint32 LE 模数长度 | 模数（大端） | uint32 LE 指数 | "host@host\0"
```

授权后公钥写入 `adb_keys`，后续普通 adb 可直接连接。

> adb 私钥必须位于 `~/.android/adbkey`。该路径不可写时 adb 无法签名，同样报此错误。

---

## 5. 侧载安装

**现象**

```
adb install -r app.apk
Failure [INSTALL_FAILED_VERIFICATION_FAILURE]
```

**定位**

```
D OverseasAppConfig: Verifying begin
D OverseasAppConfig: pkgName = com.example.app
```

校验器位于 system_server（TCL 定制 PackageManagerService）。非交互安装无 UI 可确认，
只能拒绝。

**解法**

```bash
adb push app.apk /sdcard/Download/app.apk
adb shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity
```

在电视端由应用市场发起安装，系统弹出风险确认框，确认后完成安装。

---

## 6. 代理客户端

| 客户端 | 内核 | TV 适配 | 结论 |
| --- | --- | --- | --- |
| v2rayNG | Xray | 手机布局，悬浮按钮无法聚焦 | 不推荐 |
| FlClash | mihomo | 平板式侧栏，遥控器可操作 | **推荐** |
| 服务商客户端 | sing-box | Flutter 手机布局，无 LEANBACK | 备选 |

选择 `armeabi-v7a` 构建：

```
https://github.com/chen08209/FlClash/releases
```

| 仪表盘 | 代理页 |
| --- | --- |
| ![仪表盘](docs/images/flclash-dashboard.png) | ![代理页](docs/images/flclash-proxies.png) |
| 运行状态 · 出站模式 · 服务健康度 | 策略组 · 节点延迟 |

**订阅导入**

电视端输入法会将 URL 转为中文，键盘输入不可行。使用深链：

```bash
URL="https://example.com/link/TOKEN?clash=1"
ENC=$(python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$URL")
adb shell "am start -a android.intent.action.VIEW -d 'clash://install-config?url=$ENC&name=main'"
```

链接原样存储，支持后续自动更新。文件导入会失去更新能力。

**节点选择**

代理 → 策略组 → 选择节点。默认值可能是 `security=none` 的明文节点，新内核会拒绝连接。

---

## 7. YouTube

官方客户端依赖 Google Play Services，国行固件无 `com.google.*`，安装后无法运行。

使用 [SmartTube](https://github.com/yuliskov/SmartTube/releases)（开源、免 GMS、遥控器友好）。

登录：电视显示激活码，访问 `youtube.com/activate` 输入。

**画质**

```
设置 → 视频 → 首选视频质量 → 1080p
```

**音频**

| 选项 | 说明 |
| --- | --- |
| `0.15Mbps opus` | 首选，同码率音质最优 |
| `mp4a` | AAC，兼容性最好，外接音响异常时回退 |
| `DRC` | 动态范围压缩，夜间观看可用，损失动态 |
| `禁用` | 交由播放器选择，可能选中 DRC 版本 |

---

## 8. 性能与带宽

**解码能力**

```bash
adb shell "grep -o 'name=\"[^\"]*\"' /vendor/etc/media_codecs.xml"
```

MT5879 支持 AVC / HEVC / VP8 / VP9 / AV1 / VVC / Dolby Vision / AVS2 / AVS3 / MPEG2 / VC1 / WMV3，全部硬解。

> MIME 对应关系：AV1 为 `video/av01`，VP9 为 `video/x-vnd.on2.vp9`。

**播放实测**（4K HDR AV1）

| 指标 | 值 |
| --- | --- |
| 流规格 | 3840×2160，`color-transfer=3`（HDR10） |
| 隧道吞吐 | 4.29 Mbps |
| 缓冲 | `buffered == position`，无前瞻 |
| 播放器 | 每 ~22s `Release` → `Init` |
| CPU | 单核 89%，总算力 22% |
| GPU | p50 3 ms / p90 7 ms |

瓶颈为带宽。4K 需 20–40 Mbps，实测单流仅 2–4 Mbps。

**单流与并发**

| 节点 | 单流 | 4 并发 | 倍率 |
| --- | --- | --- | --- |
| HK-03 | 2.29 Mbps | 11.14 Mbps | 4.9× |
| HK-01 | 1.55 Mbps | 7.28 Mbps | 4.7× |
| SG-02 | 2.23 Mbps | 7.18 Mbps | 3.2× |

RTT 越大，单连接越难占满链路（BDP 限制）。播放器通常只开 1 视频 + 1 音频连接，
实际可用带宽接近单流值。**1080p 为本机型的合理上限。**

---

## 9. 故障速查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `NO_MATCHING_ABIS` | 系统为 32 位 | 改用 `armeabi-v7a` 包 |
| `VERIFICATION_FAILURE` | 定制 PMS 静默拒绝 | 推送后由应用市场安装 |
| `unauthorized` 且无弹窗 | 公钥编码不兼容 | `adb-auth-legacy.py` |
| 立即 `failed to authenticate` | 无可用私钥 | 检查 `~/.android/adbkey` |
| 重启后无法连接 | ADB 开关复位 | 重新开启 |
| URL 变为中文 | 电视输入法联想 | 使用深链 |
| 双 VPN 冲突 | 系统仅允许一个 VPN | 保留单一客户端 |
| 4K 反复缓冲 | 带宽低于码率 | 限制 1080p |
| 无法 grep 到 AV1/VP9 | MIME 字符串错误 | `video/av01` / `video/x-vnd.on2.vp9` |

---

## 10. 脚本

| 文件 | 功能 |
| --- | --- |
| [`adb-auth-legacy.py`](scripts/adb-auth-legacy.py) | 传统公钥格式握手 |
| [`parse-sub.py`](scripts/parse-sub.py) | 订阅解析与明文节点检测 |
| [`bench-nodes.py`](scripts/bench-nodes.py) | 节点吞吐测试（单流 / 多流） |
| [`analyze-apk.py`](scripts/analyze-apk.py) | APK 静态分析：ABI、内核、TV 适配 |

依赖 Python 3.9+ 与 `adb`；测速另需 `sing-box`。

---

## 声明

仅用于自有设备的调试与学习。侧载与代理工具的使用风险自负，并请遵守当地法律法规。
本仓库不提供任何节点、账号或订阅链接。

## License

[MIT](LICENSE)
