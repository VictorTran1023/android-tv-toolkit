<div align="center">

# TCL 电视调试与调优手册

不 root、不刷机，把一台国行 Android TV 从「连不上」调到「能看、能测、可回滚」

![Platform](https://img.shields.io/badge/Android%20TV-9%2B-3DDC84?logo=android&logoColor=white&style=flat-square)
![Scope](https://img.shields.io/badge/Scope-China--market%20firmware-F5A623?style=flat-square)
![Python](https://img.shields.io/badge/Python-3.9%2B-3776AB?logo=python&logoColor=white&style=flat-square)
![License](https://img.shields.io/badge/License-MIT-8E8E93?style=flat-square)
![CI](https://github.com/VictorTran1023/tcl-tv-toolkit/actions/workflows/ci.yml/badge.svg)

[中文](README.md) · [English](README.en.md) · [完整手册](docs/handbook.md) · [Discussions](https://github.com/VictorTran1023/tcl-tv-toolkit/discussions)

</div>

---

国行 Android TV 的四个典型困境——**装不上、连不上、播不动、关不掉**——大多不是硬件问题，而是固件在四个地方设了卡：ADB 公钥格式、定制 PMS、缺少 GMS、厂商预装常驻。

这个仓库记录了一套在 **TCL T7K（Android 11 / MT9952 / 3.83 GB）** 上验证过的做法：每一步都有命令、有数据、有回滚。全程不 root、不刷机、不改系统分区。

## 能拿到什么

| 困境 | 本仓库的做法 | 结果 |
| --- | --- | --- |
| `adb unauthorized` 且电视不弹窗 | 按厂商 adbd 认识的**传统公钥结构**重握手 | 一次授权，之后普通 adb 直连 |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | 走**带界面的安装通道**，由前台应用发起安装 | 可侧载任意 APK（含自建应用） |
| 无 GMS，YouTube 打不开 | SmartTube + FlClash 深链导入订阅 | 1080p 稳定播放 |
| 首页广告、待机发热、后台常驻 | 分级停用 + **闸门式执行**（快照→改动→重启→回归） | CPU 从 48% 降到 9%，温度降 10 °C |
| 想看不换桌面也能看系统状态 | 25 KB 原生 HUD：VPN / CPU / 内存 / **真实温度 °C** | 悬浮状态条 + 时钟屏保 |

## 实测数据

一台 T7K，改动前后各测一次，同一场景（桌面静止 / 播放中）：

| 指标 | 改动前 | 改动后 | 手段 |
| --- | --- | --- | --- |
| 远场语音进程 `com.tcl.walleve` | **53% 单核**，24 h 持有 `AudioIn` 唤醒锁 | 0（进程不存在） | `pm disable-user` |
| 整机 CPU | 48% | **9%** | 同上 |
| 播放时温度 | **87 °C** | 71–77 °C | 同上 |
| 可用内存 | 85 MB | 639 MB | 同上 |
| Swap 已用 | 508 MB | 133 MB | 同上 |
| 首页推广 / 开机广告 / OTA | 8 个包常驻 | 全部停用，**重启后仍保持** | `pm disable-user` |
| 状态条温度 | 只有「正常/偏热/降频」 | **真实 °C**（`dumpsys thermalservice`） | 内置 ADB 客户端 |

关键细节：`pm disable-user` 之后**至少等 60 秒再重启**，否则 PackageManager 还没落盘，重启会把包全部恢复。这条踩过，写进 [docs/t7k-optimization.md](docs/t7k-optimization.md)。

## 三个硬问题的根因

不是「命令没敲对」，是固件改了三处行为：

| 现象 | 根因 | 证据 |
| --- | --- | --- |
| 授权不弹窗 | adb 34+ 发 base64 新格式公钥，厂商 adbd 只认传统二进制结构 | `[AUTH] arg0=3 (len=712)` 后直接拒绝 |
| 安装必失败 | 校验在 system_server 里，由定制 PMS 注入；非交互安装没有 UI 可确认 | `OverseasAppConfig: Verifying begin` |
| 温度读不到 | `sysfs_thermal` 对 App 与 shell 都拒绝 | `cat /sys/class/thermal/*/temp` → `Permission denied` |

第三条的绕法值得单独看：[**在电视内部跑 ADB**](docs/adb-inside-tv.md)。应用自己连 `127.0.0.1:5555`、用自签密钥认证，就能以 shell 身份执行只读命令——`dumpsys thermalservice` 里的 `cpu = 46.0 °C` 就是这么拿到的，不需要 root。

## 状态 HUD（`hud/`）

![状态条](docs/images/hud-strip-1.5.png)

约 25 KB 的原生应用，两行布局，10 秒自动淡出，不抢遥控器焦点：

| 能力 | 来源 | 备注 |
| --- | --- | --- |
| 时间 / CPU / 内存 / 吞吐 | `/proc`、`TrafficStats` | 每秒采样，无网络轮询 |
| VPN 状态 | `ConnectivityManager` | 只读，不改代理配置 |
| **CPU 温度 °C** | 内置 ADB → `dumpsys thermalservice` | ≥60 °C 转黄、≥72 °C 转红 |
| 时钟屏保 | `DreamService` | 替换厂商画报，可一条命令切回 |

不可得：GPU 占用（`mali0/gpuinfo` 直接拒绝，无 GED/devfreq 节点）。

关于它的构建方式（无 Gradle）、TCL 上唯一可行的安装路径、以及升级会重置哪些状态，见 [docs/hud-app.md](docs/hud-app.md)。

## 设计研究：Blade UI

![Blade UI](docs/images/blade-home.png)

如果哪天决定换桌面，这里有一份完整的视觉方案：Xbox 360 blade 风格，斜切刃口、横向切页、按 blade 换色调，附交互原型与动效参数。**只是设计稿，未在电视上实施**——接管 HOME 需要切换默认桌面，风险与回滚见 [docs/ui-study.md](docs/ui-study.md)。

## 仓库结构

```
.
├── docs/                 手册与专题
│   ├── handbook.md       完整操作手册（连接 → 授权 → 侧载 → 代理 → 调优）
│   ├── t7k-optimization.md   T7K 改动全记录（数据、证据、回滚）
│   ├── adb-inside-tv.md  应用内 ADB：原理、边界、能拿到什么
│   ├── hud-app.md        HUD 构建、安装、升级代价
│   ├── hud-design.md     状态条 / 屏保设计规范
│   ├── regression.md     15 项回归检查
│   └── ui-study.md       Blade UI 设计研究
├── hud/                  原生 HUD 应用源码（Java，无第三方依赖）
├── scripts/              Python 工具与 PowerShell 闸门脚本
└── .github/workflows/    CI：构建 APK + 脚本自检
```

## 快速开始

```bash
git clone https://github.com/VictorTran1023/tcl-tv-toolkit.git
cd tcl-tv-toolkit

adb connect <TV_IP>:5555
python scripts/adb-auth-legacy.py --host <TV_IP> --port 5555   # 若 unauthorized
```

改动系统状态前，先做快照，改完跑回归：

```powershell
$env:TV_IP = "<TV_IP>"
.\scripts\preflight.ps1
# …改动…
.\scripts\regress.ps1
```

## 回滚

所有改动都是 `pm disable-user` / `settings put`，一条命令可逆：

```bash
adb shell pm enable <package>                  # 恢复单个包
powershell -File scripts/restore-all.ps1       # 按快照恢复全部设置
```

真砖需要写 bootloader 或系统镜像，本仓库全程不涉及。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [handbook.md](docs/handbook.md) | 完整手册：环境、发现、授权、侧载、代理、YouTube、调优、故障表 |
| [t7k-optimization.md](docs/t7k-optimization.md) | 优化全过程：基线、闸门、数据、坑 |
| [adb-inside-tv.md](docs/adb-inside-tv.md) | 应用内 ADB 的原理与安全边界 |
| [hud-app.md](docs/hud-app.md) | HUD 构建与安装（含升级代价） |
| [hud-design.md](docs/hud-design.md) | 状态条与屏保的设计规范 |
| [regression.md](docs/regression.md) | 回归检查项与判定标准 |
| [ui-study.md](docs/ui-study.md) | Blade UI 视觉方案与动效参数 |

## 声明

仅用于自有设备的调试与学习。侧载与代理工具的使用风险自负，并请遵守当地法律法规。
本仓库不提供任何节点、账号或订阅链接。

## License

[MIT](LICENSE)
