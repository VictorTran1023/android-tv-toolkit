# 品牌与机型：开 ADB、装 APK、放过自启

> 这份表解决「同一个工具箱，换一台电视怎么用」。
> 国行固件版本差异很大，**同品牌不同型号也可能不一样**；表里标「已验证」的只有本仓库实测过的 TCL T7K，其余是社区参考或通用 Android TV 路径，第一次用请先按 §0 探测，再按 §1–§3 逐条试。

---

## 0. 先探测，不要猜

```bash
adb connect <TV_IP>:5555
adb shell getprop ro.product.brand
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package resolve-activity --brief -a android.settings.SETTINGS
```

| 输出 | 用途 |
| --- | --- |
| `ro.product.brand` / `model` | 判断品牌与机型，决定下面看哪一行 |
| `ro.build.version.sdk` | 低于 26 时 `TYPE_APPLICATION_OVERLAY` 不可用，HUD 需要换实现 |
| `ro.product.cpu.abilist` | 只有 `armeabi-v7a` 时，别下纯 arm64 的 APK |
| HOME 解析结果 | 当前桌面包名；脚本用它判断「桌面是否正常」，不要写死 `com.tcl.cyberui` |
| SETTINGS 解析结果 | 当前设置包名；不同品牌是 `com.tcl.settings` / `com.android.tv.settings` 等 |

把这几条输出贴进 Issue，比说「我家是 XX 电视」有用得多。

---

## 1. 开 ADB

| 品牌 | 入口 | 状态 |
| --- | --- | --- |
| **TCL / 雷鸟** | 设置 → 关于 → **本机信息** → 遥控器按 **上 下 左 右** → 底部「更多」→ 打开 **ADB** | **本仓库已验证**（T7K，Android 11） |
| **小米 / Redmi** | 设置 → 关于 → 连续按 **产品型号** 进入开发者模式；再到 设置 → 账号与安全 → 打开 **ADB 调试** 和 **安装未知来源的应用** | 参考 [aerial-tv](https://github.com/pengchujin/aerial-tv) 社区，未在本仓库验证 |
| **索尼 BRAVIA** | 设置 → 设备偏好设置 → 关于 → 连续按 **内部版本号** 7 次 → 开发者选项 → **USB 调试** | 通用 Android TV 路径，未验证 |
| **OPPO** | 设置 → 系统 → 关于本机 → **设备型号** → 按 **上 上 右 右** → 确认 | 用户反馈，未验证 |
| **海信 / VIDDA** | 先在 设置 → 关于 里连续按版本号；部分机型把 ADB 放在工厂/开发者菜单，型号差异大 | 未验证，需按型号查 |
| **创维 / 酷开** | 先在 设置 → 关于 里连续按版本号；部分机型在工厂菜单 | 未验证，需按型号查 |
| **长虹 / 康佳 / PPTV / 极米 / 当贝** | 设置 → 关于 → 连续按版本号 7 次 → 开发者选项 | ADB 入口适用；Android 5/6 缺 `cmd`，脚本能力有限 |
| **其他 Android TV / Google TV** | 设置 → 设备偏好设置 → 关于 → 连续按 **内部版本号**（Build，不是「Android 版本」）7 次 → 开发者选项 → **USB 调试** | 通用路径 |
| **华为 / 荣耀智慧屏** | — | **不支持**：鸿蒙不是 Android TV |
| **LG webOS / 三星 Tizen** | — | **不支持**：不是 Android |
| **Amazon Fire TV** | 设置 → 我的 Fire TV → 关于 → 连按版本号 → 开发者选项 → ADB 调试 | 部分可用，Fire OS 与 Android TV 有差异 |

补充：

- 部分国行固件的「开发者选项」只有 **ADB** 一项，没有 USB 调试、没有授权列表，属正常。
- ADB 开关**断电后可能复位**，冷启动后先检查。
- 电视 IP 在 设置 → 网络 里；电脑与电视要在同一网段。
- 第一次 `adb connect` 时电视会弹「允许 USB 调试」，勾 **一律允许**。

---

## 2. 装 APK：先试 adb，失败再走 UI

### 2.1 通用顺序

```bash
# 1) 最优先：直接装
adb install -r app.apk

# 2) 被拦截时：推到电视，用带界面的安装器
adb push app.apk /sdcard/Download/app.apk
# 然后在电视上打开任意「安装包管理 / 文件管理 / 应用市场」选中它
```

判断失败原因：

| 报错 | 含义 | 下一步 |
| --- | --- | --- |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | 厂商 PMS 拦截非交互安装 | 走 §2.2 的 UI 安装 |
| `INSTALL_FAILED_USER_RESTRICTED` | 未允许「安装未知来源」/ 安全限制 | 打开对应开关，或走 UI 安装 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | APK 只有 arm64，电视是 32 位 | 换 `armeabi-v7a` 或通用包 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致 | 先卸载旧版（会丢数据），或用同签名包 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 空间不足 | 清理后重试 |

### 2.2 各品牌的 UI 安装通道

| 品牌 | 通道 | 备注 |
| --- | --- | --- |
| **TCL / 雷鸟** | **当贝市场 → 安装包管理**（`com.dangbeimarket/.ui.apkmanager.ApkManagerActivity`）；或 TCL「多屏互动」推送 | **本仓库已验证**；TCL 会拦 `adb install` |
| **小米 / Redmi** | 通常 `adb install` 可用；不行时用「小米电视助手」或电视上的文件管理器 | 先开「安装未知来源的应用」 |
| **索尼** | 通常 `adb install` 可用 | 无需额外通道 |
| **海信 / VIDDA** | 先试 `adb install`；不行用 U 盘 + 内置文件管理器 / 聚好看安装 | 型号差异大 |
| **创维 / 酷开** | 先试 `adb install`；不行用酷开应用圈 / U 盘安装 | 型号差异大 |
| **OPPO** | 通常 `adb install` 可用；桌面类 App 可能要先改包名/补桌面标记 | 见 aerial-tv 的 OPPO 笔记 |
| **通用盒子 / 投影** | `adb install` 或自带文件管理器 | 老机型注意 ABI |

> TCL 的 UI 安装通道是「先卸载再安装」，应用数据、`appops`、ADB 密钥都会重置。升级 HUD 时尤其要注意，见 [hud-app.md](hud-app.md)。

### 2.3 安装后的授权（按需）

| 需求 | 命令 | 哪些品牌适用 |
| --- | --- | --- |
| 悬浮窗 | `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow` | Android 6+ 通用 |
| 使用情况统计 | `adb shell appops set <pkg> GET_USAGE_STATS allow` | Android 5+ 通用 |
| 后台自启 | `adb shell cmd appops set <pkg> APP_AUTO_START allow` | **TCL 实测**；其他品牌不一定有这个 op |
| 无障碍服务 | `adb shell settings put secure enabled_accessibility_services <pkg>/<service>` | 通用，但各品牌对无障碍的限制不同 |
| 通知监听 | `adb shell cmd notification allow_listener <pkg>/<listener>` | Android 8+ 通用 |

`appops` 的 op 名是厂商/版本相关的：先 `adb shell cmd appops get <pkg>` 看有没有这一项，不要盲目照抄。

---

## 3. 自启与后台：各品牌的坑

| 品牌 | 默认行为 | 建议 |
| --- | --- | --- |
| **TCL** | 第三方 App 默认禁止自启（`APP_AUTO_START=ignore`） | `appops set <pkg> APP_AUTO_START allow`；HUD 已验证重启 9 秒内起来 |
| **小米 / Redmi** | 待机可能强制停止第三方 App；HyperOS 更激进 | 电视的「自启动管理」里放行；HUD 这类前台服务要允许后台运行 |
| **索尼** | 接近原生 Android TV，限制较少 | 一般不需要额外设置 |
| **海信 / 创维** | 厂商「自启动管理 / 后台管理」 | 在电视设置里放行；`appops` 不一定存在 |
| **通用** | 因固件而异 | 装完重启一次，确认服务/悬浮窗还在；不在就查厂商自启管理 |

> 不要为了「保活」去停用系统桌面、设置、systemui 或遥控服务。停这些的代价远大于收益。

---

## 4. 已验证机型

| 机型 | 系统 | 状态 | 说明 |
| --- | --- | --- | --- |
| **TCL T7K（`tcl_mt5879_cn`）** | Android 11 / MT9952 / 3.83 GB | **本仓库实测** | 安装、ADB 授权、停用、HUD、屏保、回归全部验证；见 [t7k-optimization.md](t7k-optimization.md) |

以下机型来自 [aerial-tv 的已验证列表](https://github.com/pengchujin/aerial-tv#已验证的电视)，**不是本仓库的验证结果**，仅作为「ADB / 安装 / 自启大致可行」的参考：

| 机型 | 系统 | 备注 |
| --- | --- | --- |
| 小米 MiTV-MFTR0 / MFFU0 / MFTP0 | Android 9–14 / HyperOS TV | 安装、自启、待机唤醒；部分型号 HOME 会先闪原桌面 |
| 索尼 XRJ-77A80J / BRAVIA VH1 | Android 10 / 12 | 安装、自启、屏保；亮度接口因型号而异 |
| 创维酷开 K3 Pro 90P3D Max | Android 11 | 开机自启；新版不再需要避免设为默认桌面 |
| 康佳 2861 | Android 5.1.1 | 安装成功 |
| PPTV K55U27 | Android 6 | 可用 |
| 腾讯极光盒子 7S | — | 安装成功；待机后回原桌面待查 |
| 极米投影（m7642） | Android 9 | 开机自启；HOME 偶发卡住 |
| 雷鸟 55S595C Ultra（MT9655） | Android 11 | 能装，但无线调试授权拿不到，接管不了 HOME |
| OPPO K9 65"（ColorOS TV） | Android 9 / 32 位 | 要先改包名、补桌面标记才能装桌面类 App |

**能装但接管桌面失败的**：Vidda、海信、OPPO K9、雷鸟鹤 6 2025 Ultra（这些是「换桌面」场景；本项目不换桌面，不受影响）。

欢迎提交 Issue 补充你的机型，附上 §0 的输出和安装报错原文。

---

## 5. 不支持的设备

| 设备 | 原因 |
| --- | --- |
| 华为 / 荣耀智慧屏（鸿蒙） | 不是 Android TV，ADB 与包管理都不适用 |
| LG webOS | 不是 Android |
| 三星 Tizen | 不是 Android |
| Android 4.4 及更老 | 缺 `cmd`、`appops` 等现代命令，脚本无法保证 |
| 已 root / 解锁 / 刷机的设备 | 不在本仓库的支持范围；命令仍可能可用，但风险自负 |

---

## 6. 一句话原则

**先探测，再快照，一次只改一条；装不上就换 UI 通道，停错了就 `pm enable`。**
品牌表只是起点，最终以你这台电视的 `getprop`、`resolve-activity` 和安装报错为准。
