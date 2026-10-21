# 使用指南：电视端、电脑端、以及交给 Agent

> 这份文档回答三个问题：**电视上要按什么**、**电脑上要敲什么**、**怎么让 AI Agent 替你做**。
> 品牌差异（开 ADB 的入口、装 APK 的通道、自启限制）单独放在 [品牌与机型](brands.md)，本文只讲通用流程。

---

## 0. 先选路径

| 你想做的事 | 用什么 | 需要什么 |
| --- | --- | --- |
| 开 ADB、看 IP、装一个 APK | **只用遥控器** | 电视 |
| 连上去、改设置、跑回归、回滚 | **电脑 + adb** | 同局域网、装好 adb |
| 批量做减法、写脚本、排查卡顿、改 HUD | **Agent** | 本仓库 + 电脑 + 电视在线 |

三条路径不冲突：第一次跑通通常要「电视点两下 + 电脑敲几条」，之后日常交给 Agent。

---

## 1. 第一次跑通（约 15–30 分钟）

### 1.1 电视开 ADB

| 品牌 | 入口 |
| --- | --- |
| TCL / 雷鸟 | 设置 → 关于 → 本机信息 → 遥控器 **上 下 左 右** → 「更多」→ ADB |
| 小米 / Redmi | 设置 → 关于 → 连按 **产品型号** → 开发者模式；设置 → 账号与安全 → ADB 调试 + 安装未知来源 |
| 索尼 | 设置 → 设备偏好设置 → 关于 → 连按 **内部版本号** 7 次 → 开发者选项 → USB 调试 |
| 其他 Android TV | 设置 → 设备偏好设置 → 关于 → 连按 **内部版本号** 7 次 → 开发者选项 → USB 调试 |
| 海信 / 创维 / OPPO | 入口差异大，见 [品牌与机型](brands.md) |

> ADB 开关**断电后可能复位**，每次冷启动都要检查。
> 找不到入口时，先在 设置 → 关于 里把「版本号 / 内部版本号 / 产品型号」都连按一遍，再看有没有开发者选项。

### 1.2 连接与授权

```powershell
adb connect <TV_IP>:5555
adb devices -l                       # 状态应为 device
```

电视会弹「允许 USB 调试」，勾 **一律允许** → 允许。

如果一直是 `unauthorized` 且电视不弹窗，说明厂商 adbd 只认传统公钥结构（TCL 等机型见过）：

```bash
python scripts/adb-auth-legacy.py --host <TV_IP> --port 5555
```

> 这个脚本不是所有品牌都需要；普通 Android TV 用标准 `adb` 授权即可。

### 1.3 认清设备（只读，30 秒）

```bash
adb shell getprop ro.product.brand
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

| 看什么 | 为什么 |
| --- | --- |
| 品牌 / 型号 | 决定下一页看 [品牌与机型](brands.md) 的哪一行 |
| SDK | 低于 26 时 HUD 的悬浮窗 API 不可用；低于 21 很多命令也没有 |
| `abilist` | 只有 `armeabi-v7a` 时，纯 arm64 的 APK 会 `NO_MATCHING_ABIS` |
| HOME 解析结果 | 当前桌面包名；回归脚本用它判断桌面是否正常，**不要写死 TCL** |

### 1.4 快照（改任何东西之前）

```powershell
$env:TV_IP = "<TV_IP>"
.\scripts\preflight.ps1
```

快照会保存：

- 全部包 / 被停用的包
- `settings global / secure / system` 三张表
- `getprop` 全量
- 当前动画缩放、屏保组件、HOME handler

回滚：

```powershell
.\scripts\restore-all.ps1
```

### 1.5 改一条，验证一条

以「停用一个厂商常驻服务」为例（**包名只是示例，换品牌要换成你自己的**）：

```powershell
# 1) 先只读确认它在跑
adb shell pidof <VENDOR_DAEMON>
adb shell dumpsys cpuinfo | Select-String <VENDOR_DAEMON>

# 2) 停用（一次一条）
adb shell pm disable-user --user 0 <VENDOR_DAEMON>

# 3) 立刻回读，确认是 disabled
adb shell cmd package list packages -d | Select-String <VENDOR_DAEMON>
```

**等 60 秒再重启**：`pm disable-user` 之后 PackageManager 还没落盘，立刻重启会把包恢复。这条在 TCL 上踩过，其他品牌同样建议遵守。

重启后跑回归：

```powershell
.\scripts\regress.ps1
```

---

## 2. 电视端操作（只用遥控器）

### 2.1 开 ADB

见 [品牌与机型 §1](brands.md#1-开-adb)。几个通用判断：

- 设置里搜「ADB」「调试」「开发者」；
- 关于页里连按「版本号 / 内部版本号 / 产品型号」；
- 部分国行固件只有 ADB 一项，没有 USB 调试和授权列表；
- 断电后可能复位。

### 2.2 装 APK

#### 先试通用路径

```bash
adb install -r app.apk
```

#### 失败时按报错选通道

| 报错 | 含义 | 处理 |
| --- | --- | --- |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | 厂商 PMS 拦截非交互安装 | 走 UI 安装（下面） |
| `INSTALL_FAILED_USER_RESTRICTED` | 未允许安装未知来源 / 安全限制 | 打开开关，或走 UI 安装 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | APK 与系统位数不匹配 | 换 `armeabi-v7a` / 通用包 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致 | 卸载旧版（丢数据）或换同签名包 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 空间不足 | 清理后重试 |

#### UI 安装通道（按品牌）

| 品牌 | 通道 |
| --- | --- |
| **TCL / 雷鸟** | `adb push app.apk /sdcard/Download/` → 电视打开 **当贝市场 → 安装包管理** → 选中 → 安装 → 「未认证」选继续 → 系统安装器选安装 |
| 小米 / Redmi | 先开「安装未知来源的应用」；不行用小米电视助手 / 电视文件管理器 |
| 索尼 | 通常不需要，`adb install` 直接可用 |
| 海信 / VIDDA | 先试 adb；不行用 U 盘 + 内置文件管理器 / 聚好看 |
| 创维 / 酷开 | 先试 adb；不行用酷开应用圈 / U 盘 |
| OPPO | 通常 adb 可用；桌面类 App 可能要先改包名/补桌面标记 |
| 其他 | 任意带安装界面的应用市场 / 文件管理器 |

> **升级代价**：走 UI 通道的固件（如 TCL）常是「先卸载再安装」，应用数据、`appops`、ADB 密钥都会重置。能不卸载就不卸载；必须卸载时，装完按 [hud-app.md](hud-app.md) 补授权。

装完按需授权：

```bash
adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow       # 悬浮窗
adb shell appops set <pkg> GET_USAGE_STATS allow           # 使用情况
adb shell cmd appops set <pkg> APP_AUTO_START allow        # 仅部分品牌有
adb shell settings put secure screensaver_components <pkg>/<dream-service>
```

`appops` 的 op 名随品牌/版本变化，先 `adb shell cmd appops get <pkg>` 看有没有。

### 2.3 撤销与恢复

| 目标 | 操作 |
| --- | --- |
| 恢复某个被停用的应用 | 电脑 `adb shell pm enable <包名>` |
| 恢复全部设置 | 电脑 `.\scripts\restore-all.ps1` |
| 恢复屏保 | `settings put secure screensaver_components <原组件>`（快照里有） |
| 彻底重来 | 设置 → 系统 → 恢复出厂（会清空所有改动与账号） |

---

## 3. 电脑端操作（命令行）

### 3.1 环境

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `adb` | 34+ | 连接与执行 |
| PowerShell | 7+ | 闸门 / 回归脚本（Windows 自带 5.1 也能跑大部分） |
| Python | 3.9+ | 授权脚本、订阅解析、APK 分析 |

macOS / Linux 可以只跑 `adb` 与 Python 脚本；PowerShell 脚本需要装 `pwsh`。

### 3.2 闭环

```
快照 preflight → 改动（一次一条）→ 等 60 s → 重启 → 回归 regress → 记录
```

任何一步 FAIL，**先回滚再排查**，不要叠加改动。

### 3.3 常用命令

| 目的 | 命令 |
| --- | --- |
| 看谁在吃 CPU | `adb shell dumpsys cpuinfo \| head -20` |
| 看谁在吃内存 | `adb shell ps -A -o PID,USER,RSS,NAME --sort=-rss \| head -20` |
| 看被停用的包 | `adb shell pm list packages -d` |
| 看当前桌面 | `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` |
| 看设置入口 | `adb shell cmd package resolve-activity --brief -a android.settings.SETTINGS` |
| 截屏 | `adb exec-out screencap -p > shot.png` |
| 看崩溃 | `adb shell logcat -d -b crash -t 100` |
| 恢复单个包 | `adb shell pm enable <包名>` |

---

## 4. 用 Agent 来做

Agent（Claude Code、Codex、或任意能执行命令的编码 agent）最适合做「**多步、要判断、要记录**」的活：体检、批量减法、排查卡顿、改 HUD。

### 4.1 它能做什么，不能做什么

| 能 | 不能 |
| --- | --- |
| 连 adb、读日志、跑脚本、对比前后数据 | 替你在电视上点「允许调试」 |
| 按仓库里的闸门流程执行并写记录 | 绕过厂商 PMS 安装应用 |
| 发现异常时停下并给回滚命令 | 保证改动一定无副作用（所以要快照） |

### 4.2 交给它之前，准备好三样

1. **仓库路径**（它要读 `docs/` 与 `scripts/`）
2. **设备地址**：`192.168.31.219:5555`
3. **目标与边界**：想解决什么问题、哪些不能碰

### 4.3 三条铁律（写进给它的话里）

```
1. 只读优先：先用只读命令体检，给出结论再动手。
2. 一次一条：每轮只改一个对象，改完立刻验证；不要批量执行后一起看结果。
3. 有据可回：每条改动都要给出回滚命令，并记录到变更日志。
```

加上两条设备常识：

```
pm disable-user 之后必须等 60 秒再重启，否则改动会被系统恢复。
先读 docs/brands.md，按我的品牌选安装通道；不要照搬 TCL 的包名。
```

### 4.4 Prompt 模板

**① 体检（不改任何东西）**

```
电视在 <TV_IP>:5555，仓库在 <path>。
先按 docs/brands.md §0 识别品牌、型号、SDK、ABI、当前桌面包。
只做只读体检：CPU/内存占用前 20 名、被停用的包、可疑的常驻服务。
输出一张表：进程 / 内存 / CPU / 它在干什么 / 能不能停 / 停了会怎样。
不要执行任何 pm disable 或 settings put。
```

**② 去广告与降负载**

```
基于刚才的体检，按风险分三级给我可停清单（A 低风险 / B 影响入口 / C 无感）。
先给 dry-run：打印将要执行的命令，不执行。
我确认后，你一条一条执行，每条之后回读 pm list packages -d 验证，失败即停。
全部完成后等 60 秒、重启、跑 regress.ps1，把结果贴给我。
如果我的品牌不在 docs/brands.md 的已验证列表里，先说明哪些结论是推断。
```

**③ 排查卡顿**

```
电视播放 4K 时卡。按 docs/handbook.md 第 8 节的顺序排查：
缓冲区、播放器重建、解码器、CPU、带宽。每一步只跑只读命令，给数据不给猜测。
最后给一个结论：瓶颈在带宽、解码还是内存。
```

**④ 改 HUD**

```
HUD 源码在 hud/，无 Gradle 构建：toolchain/build-apk.ps1。
要加一个 <指标>，要求：两行布局不变、宽度不超过 400px、不新增网络请求。
改完编译，给出 APK 路径和「升级会重置哪些状态」的清单（见 docs/hud-app.md）。
如果我的电视不是 TCL，安装通道按 docs/brands.md 选，不要默认当贝。
```

### 4.5 怎么验收它的工作

不看它说「已完成」，看四样东西：

| 验收项 | 合格标准 |
| --- | --- |
| 数据 | 改动前后有具体数字（CPU%、MB、°C），不是「应该会更快」 |
| 回滚 | 每条改动都附 `pm enable` 或 `settings put` 的原值 |
| 验证 | 重启后复查过，且贴了 `regress` 的 PASS/FAIL |
| 记录 | 变更写进设备记录或 changelog，含时间与证据路径 |

---

## 5. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `adb connect` 成功但 `unauthorized`，电视不弹窗 | 厂商 adbd 只认传统公钥结构 | `python scripts/adb-auth-legacy.py --host <IP>` |
| `adb install` 报 `INSTALL_FAILED_VERIFICATION_FAILURE` | 定制 PMS 拦截非交互安装 | 走 [品牌与机型 §2.2](brands.md#22-各品牌的-ui-安装通道) 的 UI 通道 |
| `INSTALL_FAILED_USER_RESTRICTED` | 未允许安装未知来源 | 打开开关；或 UI 安装 |
| 重启后改动静默失效 | 没等 60 秒就重启 | 重做一次，等够 60 秒 |
| 待机后 adb 掉线 | 固件关 WiFi | 唤醒电视，重新 `adb connect` |
| 应用重启后不自启 | 厂商默认禁止第三方自启 | TCL：`appops set <pkg> APP_AUTO_START allow`；其他品牌查自启管理 |
| 电视端看不到授权弹窗但电脑一直等 | 弹窗被其他窗口盖住 | 回桌面再试；或关开 ADB 开关 |
| 找不到 ADB 开关 | 入口因品牌/型号不同 | 见 [品牌与机型 §1](brands.md#1-开-adb) |
| 装完 HUD 没有天气 | 网络被代理/防火墙拦，或定位失败 | 设置页看天气状态；手动选城市验证 |

---

## 6. 安全边界

| 可以做 | 不要做 |
| --- | --- |
| `pm disable-user` / `pm enable` | 删除系统 APK |
| `settings put` 可逆项 | 改 `persist.*` 画质 / 音效参数 |
| 侧载自建或开源应用 | 停用桌面、设置、systemserver、遥控服务 |
| 屏幕截图与日志取证 | root、解锁 bootloader、刷机 |
| 按快照回滚 | 在没快照的情况下批量停包 |

**停止条件**：出现 `regress` FAIL、黑屏、遥控失灵、无法回到桌面 —— 立刻 `restore-all.ps1`，再排查。
