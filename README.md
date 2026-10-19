<div align="center">

# TCL 电视 ADB 解锁与代理配置

ADB 授权 · 侧载应用 · 代理链路 · YouTube 播放

![Platform](https://img.shields.io/badge/Android%20TV-9%2B-3DDC84?logo=android&logoColor=white&style=flat-square)
![Scope](https://img.shields.io/badge/Scope-China--market%20firmware-F5A623?style=flat-square)
![Python](https://img.shields.io/badge/Python-3.9%2B-3776AB?logo=python&logoColor=white&style=flat-square)
![License](https://img.shields.io/badge/License-MIT-8E8E93?style=flat-square)
![CI](https://github.com/VictorTran1023/tcl-tv-adb-proxy/actions/workflows/ci.yml/badge.svg)

[中文](README.md) · [English](README.en.md) · [Discussions](https://github.com/VictorTran1023/tcl-tv-adb-proxy/discussions)

</div>

---

## 适合什么人

- 使用 **国行 TCL / 雷鸟 等安卓电视**，系统受限、无法安装第三方应用
- 卡在 `adb unauthorized`，且电视端**始终不弹授权框**
- 希望在不 root、不刷机的前提下侧载应用、配置代理、稳定播放
- 需要一套**可复现的排查路径**，而不是零散的命令片段

## 不适用

| 场景 | 说明 |
| --- | --- |
| root / 改系统分区 / 刷机 | 本仓库不涉及 |
| 现成的节点、账号、订阅 | 全部需要自备 |
| 非 Android TV 系统 | webOS、Tizen、Roku 等不适用 |

## 适用范围

| 项目 | 说明 |
| --- | --- |
| 系统 | Android TV 9 及以上 |
| 架构 | 32 位（`armeabi-v7a`）或 64 位均可，需先确认 |
| 设备 | 以国行 TCL / 雷鸟为例，方法适用于多数国行 Android TV |
| 前提 | 设备与电脑处于同一局域网 |

四类典型问题与成因：

| 现象 | 成因 |
| --- | --- |
| 连不上 | 公钥编码与厂商 adbd 不兼容 |
| 装不上 | 厂商定制 PMS 拦截非交互安装 |
| 点不动 | 应用 UI 未适配遥控器 |
| 播不动 | 码率超出可用带宽 |

## 快速开始

```bash
git clone https://github.com/VictorTran1023/tcl-tv-adb-proxy.git
cd tcl-tv-adb-proxy

adb connect <TV_IP>:5555
adb devices -l

python scripts/parse-sub.py "<SUBSCRIPTION_URL>"
```

## 目录

| # | 章节 | 内容 |
| --- | --- | --- |
| 1 | [环境侦测](#1-环境侦测) | ABI、内核位数、解码能力 |
| 2 | [局域网发现](#2-局域网发现) | ARP 扫描、SSDP、mDNS |
| 3 | [开启 ADB](#3-开启-adb) | 厂商入口路径 |
| 4 | [授权突破](#4-授权突破) | 传统公钥握手 |
| 5 | [侧载安装](#5-侧载安装) | 绕过安装校验 |
| 6 | [代理客户端](#6-代理客户端) | FlClash 与订阅导入 |
| 7 | [YouTube](#7-youtube) | 无 GMS 环境下的方案 |
| 8 | [播放调优](#8-播放调优) | 解码能力与卡顿定位 |
| 9 | [故障速查](#9-故障速查) | 典型故障对照 |
| 10 | [脚本](#10-脚本) | 四个 CLI 工具 |

---

## 1. 环境侦测

```bash
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abilist64
adb shell getprop ro.zygote
adb shell uname -m
```

| 检查项 | 判定依据 |
| --- | --- |
| `abilist64` 为空 | 纯 32 位系统，仅能安装 `armeabi-v7a` 包 |
| `zygote32` | 32 位进程模型 |
| `uname -m` 为 `armv8l` | 内核运行于 AArch32 |

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

SSDP 可直接取到设备型号：

```powershell
$msg = "M-SEARCH * HTTP/1.1`r`nHOST: 239.255.255.250:1900`r`nMAN: `"ssdp:discover`"`r`nMX: 2`r`nST: ssdp:all`r`n`r`n"
$udp = [System.Net.Sockets.UdpClient]::new()
$ep  = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Parse('239.255.255.250'), 1900)
$b   = [Text.Encoding]::ASCII.GetBytes($msg)
[void]$udp.Send($b, $b.Length, $ep)
```

电视端 UPnP 描述中的标识：

```xml
<modelName>...</modelName>
<X_brandName>TCL</X_brandName>
<channelName>tcl</channelName>
```

`_airplay._tcp.local` 的 TXT 记录含 `model=AppleTV3,2` 表示支持 AirPlay 2，可从 iPhone / Mac 直接投屏。

---

## 3. 开启 ADB

各厂商入口不同，常见两类：

| 方式 | 路径 |
| --- | --- |
| 隐藏开关 | 设置 → 关于本机 → 本机信息 → 遥控器按 上 下 左 右 |
| 开发者模式 | 设置 → 关于 → 连续点击版本号 |

国行固件的开发者选项可能只有 ADB 一项，无 USB 调试、无授权列表。

> ADB 开关通常在断电后复位，需要重新开启。

```bash
adb connect <TV_IP>:5555
adb devices -l
```

---

## 4. 授权突破

**现象**

```
failed to authenticate to <TV_IP>:5555
<TV_IP>:5555    unauthorized
```

电视端不显示授权弹窗。

**定位**

开启 adb 服务端追踪后可见：

```
[AUTH] arg0=1 (len=20)      device challenge
[AUTH] arg0=2 (len=256)     signature
[AUTH] arg0=1 (len=20)      rejected
[AUTH] arg0=3 (len=712)     public key  ← base64 编码
```

adb 34+ 发送 base64 编码的新格式公钥；部分厂商 adbd 仅解析传统二进制结构，解析失败即拒绝，
不进入弹窗流程。

**解法**

```bash
python scripts/adb-auth-legacy.py --host <TV_IP> --port 5555
```

脚本发送以下结构并保持连接，等待设备端确认：

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

调整全局校验开关通常无效：

```bash
adb shell settings put global verifier_verify_adb_installs 0
adb shell settings put global package_verifier_enable 0
```

**定位**

```
D OverseasAppConfig: Verifying begin
D OverseasAppConfig: pkgName = com.example.app
```

校验逻辑位于 system_server，由厂商定制 PackageManagerService 注入。非交互安装没有 UI
可供用户确认，只能返回失败。

**解法**

选择一条带界面的安装通道：

```bash
adb push app.apk /sdcard/Download/app.apk
adb shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity
```

也可使用任意具备安装界面的应用市场或文件管理器，由前台应用发起安装，系统弹出确认框。

---

## 6. 代理客户端

[FlClash](https://github.com/chen08209/FlClash) 是基于 mihomo 内核的开源客户端，
支持 VLESS / VMess / Trojan / Hysteria2 等协议，在 Android TV 上可用遥控器直接操作。

| 项目 | 说明 |
| --- | --- |
| 项目地址 | https://github.com/chen08209/FlClash |
| 内核 | mihomo（Clash Meta） |
| 界面 | 平板式侧栏 + 卡片列表，D-pad 可聚焦 |
| 订阅 | 支持链接导入与自动更新 |
| 授权 | 开源 |

**下载**

前往 [Releases](https://github.com/chen08209/FlClash/releases) 选择与设备 ABI 对应的构建：

| ABI | 文件 |
| --- | --- |
| 32 位 | `FlClash-*-android-armeabi-v7a.apk` |
| 64 位 | `FlClash-*-android-arm64-v8a.apk` |

| 仪表盘 | 代理页 |
| --- | --- |
| ![仪表盘](docs/images/flclash-dashboard.png) | ![代理页](docs/images/flclash-proxies.png) |
| 运行状态 · 出站模式 · 服务健康度 | 策略组 · 节点延迟 |

**订阅导入**

电视输入法常将 URL 联想为中文，键盘输入不可行。使用深链直接推送：

```bash
URL="https://example.com/link/TOKEN?clash=1"
ENC=$(python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$URL")
adb shell "am start -a android.intent.action.VIEW -d 'clash://install-config?url=$ENC&name=main'"
```

链接按原样存储，可保留订阅自动更新能力；文件导入则会失去更新。

**节点选择**

代理 → 策略组 → 选择节点。部分订阅默认指向 `security=none` 的明文节点，当前内核会拒绝连接。

---

## 7. YouTube

官方客户端依赖 Google Play Services 完成账号与播放鉴权，不含 GMS 的固件无法运行。

可选方案：

| 方案 | 说明 |
| --- | --- |
| [SmartTube](https://github.com/yuliskov/SmartTube/releases) | 开源客户端，免 GMS，支持登录与遥控器操作 |
| 网页版 | 兼容性最好，但操作与画质受限 |

SmartTube 登录方式：电视显示激活码，在 `youtube.com/activate` 输入。

**画质**

```
设置 → 视频 → 首选视频质量 → 1080p
```

**音频**

| 选项 | 说明 |
| --- | --- |
| `opus` | 同码率音质较优 |
| `mp4a` | AAC，兼容性最好，外接音响异常时回退 |
| `DRC` | 动态范围压缩，夜间观看可用，损失动态范围 |
| `禁用` | 交由播放器选择，可能选中 DRC 版本 |

---

## 8. 播放调优

**枚举解码能力**

```bash
adb shell "grep -o 'name=\"[^\"]*\"' /vendor/etc/media_codecs.xml"
```

常见硬解格式：AVC、HEVC、VP8、VP9、AV1、VVC、Dolby Vision、AVS2/AVS3、MPEG2、VC1。

> MIME 对应关系：AV1 为 `video/av01`，VP9 为 `video/x-vnd.on2.vp9`。
> 使用 `video/av1`、`video/vp9` 检索会全部漏掉。

**定位卡顿**

| 指标 | 观察方式 | 判读 |
| --- | --- | --- |
| 缓冲区 | `dumpsys media_session` 的 `buffered` 与 `position` | 两者相等表示无前瞻，属带宽饥饿 |
| 播放器重建 | `logcat` 中 `Release` / `Init` 频率 | 周期性重建多为分片下载超时重试 |
| 解码器 | `logcat` 中 `OMX.*Decoder` 与 `output format changed` | 确认是否硬解、实际分辨率 |
| CPU | `ps -A -o RSS,NAME` 与 `/proc/<pid>/stat` | 单核接近满载才可能是解码瓶颈 |
| GPU | `dumpsys gfxinfo <pkg>` 帧耗时 | 帧耗时可判断渲染是否受限 |

**码率与带宽**

| 分辨率 | 典型码率 |
| --- | --- |
| 1080p | 1–6 Mbps |
| 1440p | 8–12 Mbps |
| 2160p HDR | 20–40 Mbps |

高延迟线路下单条 TCP 连接受窗口限制，实际吞吐常低于节点标称带宽（BDP 限制）。
播放器通常只建立一条视频与一条音频连接，因此可用带宽接近单流值。

**建议**

- 将首选画质限制为 1080p，给带宽留出余量
- 节点选择以单流吞吐与延迟为准，而非并发测速结果
- 若必须 4K，需确认节点可稳定提供 20 Mbps 以上的单流吞吐

---

## 9. 故障速查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `NO_MATCHING_ABIS` | 系统位数与 APK 不匹配 | 改用 `armeabi-v7a` 包 |
| `VERIFICATION_FAILURE` | 定制 PMS 拦截非交互安装 | 由应用市场在电视端安装 |
| `unauthorized` 且无弹窗 | 公钥编码不兼容 | `adb-auth-legacy.py` |
| 立即 `failed to authenticate` | 私钥缺失或不可读 | 检查 `~/.android/adbkey` |
| 重启后无法连接 | ADB 开关复位 | 重新开启 |
| URL 被转为中文 | 电视输入法联想 | 使用深链导入 |
| 双 VPN 冲突 | 系统仅允许一个活动 VPN | 保留单一客户端 |
| 4K 反复缓冲 | 带宽低于码率 | 限制 1080p |
| 检索不到 AV1/VP9 | MIME 字符串错误 | `video/av01` / `video/x-vnd.on2.vp9` |

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
