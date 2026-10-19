# TCL 安卓电视折腾指南

> 从「找到局域网里的电视」到「遥控器可控的代理 + YouTube」，全流程实测记录。
> 实测机型：**TCL T7K**（MT5879，Android 11，32 位用户态）

[![Android](https://img.shields.io/badge/Android-11-brightgreen)]()
[![ABI](https://img.shields.io/badge/ABI-armeabi--v7a-orange)]()
[![Chip](https://img.shields.io/badge/SoC-MediaTek%20MT5879-blue)]()

---

## 这份文档适合谁

- 手里是 **TCL / 雷鸟 国行安卓电视**，想装第三方 App、挂代理看 YouTube
- 被「国行没 Google 框架」「TCL 不给装 APK」「ADB 连不上」卡住过
- 想要一份**能照着做、每步都有原因解释**的记录，而不是零散的命令

不适用的场景：刷机改系统分区、root、破解会员。本项目不做这些。

---

## 0. 免责声明

- **只操作你自己的设备。**
- 停用系统组件、安装第三方应用存在风险，本仓库作者不对任何损坏负责。
- **本项目不提供任何代理节点、账号或订阅链接。** 请自行解决。
- 代理类工具的使用涉及当地法律法规，风险自负。
- 文中所有 IP、MAC、订阅地址均为占位示例。

---

## 1. 先搞清楚你的电视是什么

这一步决定了后面所有的技术选择。

```bash
adb shell getprop ro.product.cpu.abilist        # 支持的 ABI
adb shell getprop ro.product.cpu.abilist64      # 64 位 ABI（空 = 不支持）
adb shell getprop ro.zygote                     # zygote32 / zygote64_32
adb shell uname -m                              # armv8l = 内核就是 32 位
```

实测 T7K 的结果：

| 检查项 | 值 | 含义 |
| --- | --- | --- |
| `ro.product.cpu.abilist` | `armeabi-v7a,armeabi` | 只有 32 位 |
| `ro.product.cpu.abilist64` | *（空）* | 系统不承认 64 位 |
| `ro.zygote` | `zygote32` | 进程孵化器只有 32 位 |
| `uname -m` | `armv8l` | **内核本身就跑在 AArch32** |
| `/system/lib64` | 不存在 | 没有 64 位库 |

**结论：这是纯 32 位系统。** 只带 `arm64-v8a` 的 APK 永远装不上，会报
`INSTALL_FAILED_NO_MATCHING_ABIS`。下载 APK 时必须挑 `armeabi-v7a` 或通用包。

> 很多「明明下载了 APK 却装不上」的问题，根因就在这，而不是权限。

---

## 2. 在局域网里找到电视

### 2.1 别依赖 ping

电视/系统防火墙经常拦 ICMP，扫一圈全是 0 存活。改用 **TCP 触发 ARP**：

```powershell
1..254 | ForEach-Object -Parallel {
  $ip = "192.168.1.$_"
  $c = [System.Net.Sockets.TcpClient]::new()
  try { if ($c.ConnectAsync($ip, 5555).Wait(250) -and $c.Connected) { $ip } }
  catch {} finally { $c.Dispose() }
} -ThrottleLimit 128
arp -a | Select-String '192\.168\.1\.'
```

即使端口没开，TCP SYN 也会触发 ARP 解析，之后 `arp -a` 就能看到真实在线的主机。

### 2.2 用 SSDP 认设备型号

```powershell
$msg = "M-SEARCH * HTTP/1.1`r`nHOST: 239.255.255.250:1900`r`nMAN: `"ssdp:discover`"`r`nMX: 2`r`nST: ssdp:all`r`n`r`n"
$udp = [System.Net.Sockets.UdpClient]::new()
$ep  = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Parse('239.255.255.250'), 1900)
$b = [Text.Encoding]::ASCII.GetBytes($msg)
[void]$udp.Send($b, $b.Length, $ep)
# 然后 Receive 若干次，抓 LOCATION / SERVER 字段
```

TCL 电视通常同时跑好几个 UPnP 栈，从它们的 `description.xml` 里能直接读出型号：

```xml
<!-- 某个视频 App 暴露的 DLNA 描述 -->
<modelName>tcl_mt5879_cn</modelName>
<X_brandName>TCL</X_brandName>
<channelName>tcl</channelName>
```

再看 mDNS 有没有苹果投屏能力：

```powershell
# 查询 _airplay._tcp.local 的 TXT 记录
# 正常会看到：
#   model=AppleTV3,2
#   srcvers=366.0
#   features=0x527FFFF7,0x1E
```

`model=AppleTV3,2` 是电视冒充的 AirPlay 接收端身份，说明它**支持 AirPlay 2**，
可以拿 iPhone/Mac 直接投屏，不需要装任何 App。

---

## 3. 开启 ADB（TCL 特有路径）

通用 Android 教程让你「连点版本号 7 次」，**TCL 不吃这套**。

正确路径：

```
设置 → 关于本机 → 本机信息
    → 遥控器依次按：上、下、左、右
    → 页面刷出「ADB 开关」
```

实测这版固件的「开发者选项」里**只有 ADB 一个条目**，没有 USB 调试、没有撤销授权、
没有无线调试。也就是说 TCL 把标准授权链路裁掉了 —— 这一点后面很关键。

⚠️ **TCL 的 ADB 开关每次关机/断电都会自动关闭**，不是你没保存好，是它的设计。

验证：

```bash
adb connect 192.168.1.100:5555
adb devices -l
# 期望：192.168.1.100:5555   device  product:tcl_mt5879_cn_db model:tcl_mt5879_cn
```

---

## 4. 突破 ADB 授权（本项目核心）

### 4.1 症状

```
$ adb connect 192.168.1.100:5555
failed to authenticate to 192.168.1.100:5555

$ adb devices
192.168.1.100:5555    unauthorized
```

电视上**永远不弹「是否允许 USB 调试」**，无论怎么重连、换 adb 版本、重启电视。

### 4.2 定位

打开 adb 服务端追踪，能看到真实交互：

```
auth.cpp:150  loaded new key from 'C:\Users\you\.android\adbkey' with fingerprint C92E...
transport.cpp  from remote: [AUTH] arg0=1 (len=20)          ← 设备发来挑战
auth.cpp:279  adb_auth_sign len=256                          ← 我们签名
transport.cpp  to remote:   [AUTH] arg0=2 (len=256)          ← 发送签名
transport.cpp  from remote: [AUTH] arg0=1 (len=20)           ← 不认，再挑战
transport.cpp  to remote:   [AUTH] arg0=3 (len=712)          ← 发送公钥请求授权
```

关键在最后一行：**公钥 payload 是 712 字节的 base64 内容**（`QAAAAD0lDlXr...`）。

这是 **adb 34+ 引入的新公钥编码格式**。而 TCL 这版 adbd（Android 11 但厂商深度定制）
只认**传统的二进制 RSA 公钥结构**，解析失败 → 直接拒绝、**根本不会弹窗**。
所以电视不是「等你点同意」，是「看不懂你的公钥」。

### 4.3 解决

用 [scripts/adb-auth-legacy.py](scripts/adb-auth-legacy.py) 手工握手，发送老格式公钥：

```
uint32 LE 模数长度 | 模数(大端) | uint32 LE 指数 | "host@host\0"
```

并**保持 TCP 连接不关闭**，等用户在电视上点「允许」。

```bash
python scripts/adb-auth-legacy.py --host 192.168.1.100 --port 5555
# 电视此时应弹出「是否允许 USB 调试」
# 勾选「一律允许使用这台计算机进行调试」→ 确定
```

授权成功后，电视会把你的公钥写进 `adb_keys`，**之后普通 adb 就能直接连**，
重启也不需要重新授权（只需重新打开 ADB 开关）。

### 4.4 为什么 adb 自己不做这件事

adb 的 key 默认放在 `%USERPROFILE%\.android\adbkey`，且**必须**在那个位置。
如果你的环境（沙箱、受限账户）不能写这个目录，adb 会拿不到私钥、无法签名，
表现同样是瞬间 `failed to authenticate` —— 这是个很容易误判成「电视拒绝」的坑。

自检：`ls -l ~/.android/adbkey`，文件不存在就是这个问题。

---

## 5. 只读体检

连上之后先看，不动任何东西：

```bash
adb shell getprop ro.build.version.release     # Android 11
adb shell getprop ro.build.display.id          # 固件版本
adb shell dumpsys package | head -50           # 系统概况
adb shell pm list packages -3                  # 第三方应用
adb shell pm list packages -s                  # 系统应用
adb shell pm list packages -d                  # 已停用（空 = 没人动过）
adb shell "ps -A -o RSS,NAME | sort -rn | head -15"   # 内存大户
adb shell df -h /data
adb shell settings get global window_animation_scale
```

实测结论（T7K）：约 **50 个 TCL 常驻服务 + 18 个预装第三方 App**；
内存大户不是视频 App，而是 `com.tcl.walleve`（画报，225 MB）和
`com.tcl.guard`（电视卫士，172 MB）。

---

## 6. 安装第三方 APK（绕过 TCL 校验）

### 6.1 症状

```
adb install -r app.apk
Failure [INSTALL_FAILED_VERIFICATION_FAILURE]
```

改设置也没用：

```bash
adb shell settings put global verifier_verify_adb_installs 0
adb shell settings put global package_verifier_enable 0
# 依然失败
```

### 6.2 原因

logcat 里能看到校验器启动后就没了下文：

```
D OverseasAppConfig: Verifying begin
D OverseasAppConfig: pkgName = com.example.app
```

`OverseasAppConfig` 跑在 **system_server（PID 435）** 里 —— 是 TCL 直接改的
PackageManagerService。非交互式安装（adb）没有 UI 可供用户确认，它只能静默拒绝。

### 6.3 解决：让它走「有界面」的安装通道

```bash
adb push app.apk /sdcard/Download/app.apk
adb shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity
```

在电视上用当贝市场的「安装包管理」点安装 —— 这是前台应用发起的安装，
系统会弹风险确认框，用户点「继续安装」即可。

> 当贝市场持有 `android.permission.INSTALL_PACKAGES`，走的是特权安装通道。
> 如果你电视上没有当贝，装一个文件管理器/应用市场通常也行，关键是**有 UI**。

---

## 7. 代理客户端：FlClash

### 7.1 选型

| 客户端 | 内核 | TV 适配 | 结论 |
| --- | --- | --- | --- |
| v2rayNG | Xray | 手机 UI，悬浮按钮遥控器够不到 | 不推荐 |
| FlClash | mihomo (Clash Meta) | 平板式左右布局，遥控器可操作 | **推荐** |
| 服务商官方 App | sing-box | 多为 Flutter 手机 UI，无 LEANBACK | 备用 |

FlClash 下载（认准 `armeabi-v7a`）：

```
https://github.com/chen08209/FlClash/releases
→ FlClash-x.y.z-android-armeabi-v7a.apk
```

安装方式同第 6 节。

### 7.2 导入订阅：用深链，别用键盘

**坑**：电视上的搜狗输入法会把 `https://...` 当拼音猜成汉字
（实测把 URL 变成了「输入、林奇、PKOO6皮虚对面M啊V啊」），手动输入完全不可行。

**解法**：FlClash 注册了 `clash://`、`clashmeta://`、`flclash://` 三个 scheme，直接推：

```bash
URL="https://example.com/link/TOKEN?clash=1"
ENC=$(python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$URL")
adb shell "am start -a android.intent.action.VIEW -d 'clash://install-config?url=$ENC&name=my-sub'"
```

电视上会弹「是否通过该链接创建配置」，点确定即可。链接被**原样保存**，以后能自动更新。

> 不要用「文件导入」下载 YAML —— 那样订阅就不会自动更新了。

### 7.3 选节点

「代理」页 → 点策略组（如「国外流量」）→ 选节点。
默认可能指向某个**明文节点**（`security=none`），那种节点新内核会拒绝连接，记得换掉。

### 7.4 验证

仪表盘 → 服务状态，Google / GitHub / YouTube 全绿即通。

---

## 8. YouTube：为什么必须用 SmartTube

YouTube 官方 TV 客户端强依赖 **Google Play Services** 做账号与播放鉴权。
国行 TCL 固件里 `com.google.*` 一个都没有，装上也只会卡在「需要 Google Play 服务」。

**替代品：SmartTube**（开源、无 GMS、遥控器友好、支持登录/4K/去广告）

```
https://github.com/yuliskov/SmartTube/releases
→ SmartTube_stable_*.apk（选 armeabi-v7a 或 universal）
```

登录：电视上显示一个激活码 → 手机/电脑打开 `youtube.com/activate` 输入即可。

### 8.1 必做设置：锁 1080p

`设置 → 视频 → 首选视频质量 → 1080p`

不锁的话它会自动爬到 4K，而多数机场节点扛不住 4K 的码率（见第 9 节）。

### 8.2 音频格式怎么选

选项里会出现 `opus` / `mp4a` / `DRC` 这些词：

| 选项 | 含义 |
| --- | --- |
| `0.15Mbps opus` | Opus 编码，同码率音质优于 AAC，**默认选它** |
| `mp4a` | AAC，兼容性最广；接回音壁/功放出问题时可回退 |
| `DRC` | 动态范围压缩：小声放大、大声压小，夜里看片有用，代价是失去冲击力 |
| `禁用` | 让播放器自动挑，**可能挑到 DRC 版**，不推荐 |

---

## 9. 性能与带宽：卡顿到底怪谁

### 9.1 别急着怪 CPU

先看编解码器清单（这条命令比 `media_codecs.xml` 的静态内容更可信）：

```bash
adb shell "grep -o 'name=\"[^\"]*\"' /vendor/etc/media_codecs.xml"
```

MT5879 实测支持：H.264 / HEVC / **VP8 / VP9 / AV1** / VVC / Dolby Vision /
AVS2 / AVS3 / MPEG2 / VC1 / WMV3，全部硬解。

> 注意 MIME 陷阱：AV1 是 `video/av01`，VP9 是 `video/x-vnd.on2.vp9`。
> 用 `video/av1`、`video/vp9` 去 grep 会全部漏掉 —— 本文作者就踩过这个坑。

运行时确认解码器：

```bash
adb shell logcat -d | grep -E 'OMX\.MS\..*Decoder|output format changed'
# 例：I MediaCodec: [OMX.MS.AV1.Decoder], output format changed...
#     stride = 3840, slice-height = 2160   ← 4K
```

### 9.2 真正的瓶颈通常是带宽

实测 4K HDR AV1 播放时：

| 指标 | 实测 | 判断 |
| --- | --- | --- |
| 视频规格 | 3840×2160，color-transfer=3（HDR10） | 4K HDR |
| 隧道速率 | 4.29 Mbps | 4K 需要 20–40 Mbps |
| 缓冲区 | `buffered position == position` | 前方缓冲为 0 |
| 播放器行为 | 每 ~22 秒 `Release` → `Init` 一次 | 分片下载超时重试死循环 |
| CPU | 单核 89%，总算力 22% | 不饱和 |
| GPU 帧耗时 | p50 3 ms / p90 7 ms | 不是瓶颈 |

**结论：卡顿是带宽不足，不是性能不足。** 4K 分片太大，下载超时 → 丢弃重试 → 死循环。

### 9.3 单流 vs 多流：别被「聚合带宽」骗了

机场节点的「总带宽」和「单条连接能跑多少」是两件事。实测同一批节点：

| 节点 | 单流 | 4 流并发 | 倍数 |
| --- | --- | --- | --- |
| 香港03 | 2.29 Mbps | **11.14 Mbps** | 4.9× |
| 香港01 | 1.55 Mbps | 7.28 Mbps | 4.7× |
| 新加坡02 | 2.23 Mbps | 7.18 Mbps | 3.2× |

原因：**RTT 越大，单条 TCP 连接越难打满带宽**（BDP 限制）。
而 YouTube 播放一个视频通常只开 1 条视频连接 + 1 条音频连接 ——
所以你能用到的接近「单流」而不是「聚合」。

**推论**：4K 需要 20–40 Mbps，而单流只有 2–4 Mbps，**换哪个节点都救不了 4K**。
1080p（约 1 Mbps）才是这台电视的现实解。

测速脚本见 [scripts/bench-nodes.py](scripts/bench-nodes.py)。

---

## 10. 常见坑速查

| 现象 | 根因 | 解决 |
| --- | --- | --- |
| APK 装不上，`NO_MATCHING_ABIS` | 系统是 32 位 | 换 `armeabi-v7a` 包 |
| `adb install` 报 `VERIFICATION_FAILURE` | TCL 定制 PMS 静默拒绝 | 推到 Download，用当贝市场装 |
| `adb connect` 后一直 `unauthorized`，电视不弹窗 | 新公钥格式老 adbd 不认 | 用 `adb-auth-legacy.py` |
| adb 瞬间 `failed to authenticate` | 本机没有可读的 `~/.android/adbkey` | 生成密钥并放到标准路径 |
| 电视重启后 adb 连不上 | TCL 每次开机自动关 ADB | 重新走「关于本机 → 上下左右」 |
| 输入框里网址变成汉字 | 搜狗输入法 TV 版拼音联想 | 改用 `clash://` 深链，别用键盘 |
| 两个 VPN App 抢通道 | Android 同时只允许一个 VPN | 只留一个客户端 |
| 4K 视频反复卡死重启 | 带宽 < 码率需求 | 锁 1080p 或换更快节点 |
| `grep media_codecs.xml` 找不到 AV1/VP9 | MIME 写错（`video/av01`、`video/x-vnd.on2.vp9`） | 用正确的 MIME 前缀 |

---

## 11. 脚本

| 文件 | 用途 |
| --- | --- |
| [scripts/adb-auth-legacy.py](scripts/adb-auth-legacy.py) | 传统公钥格式握手，解决「电视不弹授权框」 |
| [scripts/parse-sub.py](scripts/parse-sub.py) | 解析订阅，列出每个节点的加密方式（识别明文节点） |
| [scripts/bench-nodes.py](scripts/bench-nodes.py) | 用 sing-box 逐节点测吞吐（单流 / 多流） |
| [scripts/analyze-apk.py](scripts/analyze-apk.py) | 静态分析 APK：ABI、内核、TV 适配、权限 |

环境要求：Python 3.9+，`adb`（platform-tools）。测速脚本另需 `sing-box`。

---

## 12. 最后

这套流程的价值不在于「装了什么」，而在于**每一步都先问「为什么不行」**：

- 装不上 → 不是权限问题，是 ABI
- 连不上 → 不是网络问题，是公钥格式
- 卡顿 → 不是性能问题，是码率
- 点不动 → 不是系统问题，是 UI 没有为遥控器设计

希望这份记录能帮你少走几个小时的弯路。

## License

MIT
