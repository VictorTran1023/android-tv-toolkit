# TV HUD：状态条 + 时钟屏保

`hud/` 是一个约 25 KB 的原生 Android 应用（`com.victor.tvhud`），给电视加两样东西：

| 组件 | 说明 |
| --- | --- |
| **状态条** | 顶部 64 dp 悬浮窗，两行：`时间 · VPN · CPU` / `RAM · TEMP · 流量`；展示 10 秒后自动淡出 |
| **时钟屏保** | `DreamService`，替换厂商的广告画报 |
| **设置页 / 预览页** | 开关状态条、悬浮窗授权入口、屏保预览 |

![状态条](images/hud-strip-1.5.png)
![时钟屏保](images/hud-clock.png)

---

## 读数来源

| 读数 | 来源 | 说明 |
| --- | --- | --- |
| 时间 | `SimpleDateFormat` | 分钟级刷新 |
| CPU | `/proc/stat` 增量 | 1 Hz |
| 内存 | `/proc/meminfo` 的 `MemTotal - MemAvailable` | 显示已用 GB + 进度条 |
| 吞吐 | `TrafficStats` | 1 Hz |
| VPN | `ConnectivityManager` 的 `TRANSPORT_VPN` | 只读 |
| **温度 °C** | 内置 ADB → `dumpsys thermalservice` | 见 [adb-inside-tv.md](adb-inside-tv.md) |

温度拿不到时（未授权 / ADB 关闭），自动退回 `PowerManager` 的三档文字：正常 / 偏热 / 降频。

## 设计取舍

- **两行而不是一行**：单行加到 6 段会宽到 ~890 px，压住电视首页中上方的「应用 / +」按钮；两行后约 390 px，完全避开。
- **左对齐、低高度**：只占顶部 64 dp，不覆盖内容区。
- **不拦截按键**：`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`，不抢遥控器焦点。
- **不伪造数据**：拿不到的指标（GPU 占用）宁可不显示，也不给估算值。
- **不轮询网络**：所有读数本地可得；温度探针 20 秒缓存，失败退避 3 分钟，避免反复弹授权。

## 构建

不需要 Gradle。`toolchain/build-apk.ps1` 用 aapt2 + javac + d8 + zipalign + apksigner 直接出包：

```powershell
# 一次性准备（国内镜像）
#   JDK 17      mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/
#   platform-31 mirrors.cloud.tencent.com/AndroidSDK/platform-31_r01.zip
#   build-tools mirrors.cloud.tencent.com/AndroidSDK/build-tools_r34-windows.zip
.\toolchain\build-apk.ps1
# -> toolchain/out/tvhud.apk
```

仓库也带了 GitHub Actions（`.github/workflows/android.yml`），有网时 `gradle -p hud assembleDebug` 即可。

---

## 安装（TCL 国行固件）

厂商 PMS 会拦截所有非交互安装：

```
adb install            -> INSTALL_FAILED_VERIFICATION_FAILURE
pm install-create/commit -> 同样失败
伪装安装器 -i com.android.vending -> 同样失败
调整 verifier_verify_adb_installs / package_verifier_enable -> 无效（本来就是 0）
```

只能走带界面的安装通道：

1. `adb push tvhud.apk /sdcard/Download/`
2. 电视上打开 **当贝市场 → 安装包管理**（`com.dangbeimarket/.ui.apkmanager.ApkManagerActivity`）
3. 选中 TV HUD → 安装 → 在「未认证」提示里选 **继续** → 系统安装器里选 **安装**

### 升级的代价（重要）

这条通道是**先卸载再安装**，以下状态全部被重置，装完必须补：

| 状态 | 补回命令 |
| --- | --- |
| 后台自启 | `adb shell cmd appops set com.victor.tvhud APP_AUTO_START allow` |
| 悬浮窗 | `adb shell appops set com.victor.tvhud SYSTEM_ALERT_WINDOW allow` |
| 使用情况统计 | `adb shell appops set com.victor.tvhud GET_USAGE_STATS allow` |
| 时钟屏保 | `adb shell settings put secure screensaver_components com.victor.tvhud/.ClockDream` |
| ADB 密钥 | 重新弹一次「允许 USB 调试」，勾 **一律允许** |

ADB 密钥存在应用私有目录，卸载即失效，所以每次升级都要重新授权一次温度读取。

### 启动

```bash
adb shell am start-foreground-service -n com.victor.tvhud/.HudService
# 手动唤出状态条（10 秒）
adb shell am start-foreground-service -a com.victor.tvhud.PEEK -n com.victor.tvhud/.HudService
```

---

## 已知限制

| 限制 | 原因 |
| --- | --- |
| GPU 占用不可得 | `mali0/gpuinfo` 权限拒绝，且无 GED / devfreq 节点 |
| 语音键呼出不可行 | 该键在输入层是 `KEY_UNKNOWN`，到不了无障碍层（详见 [adb-inside-tv.md](adb-inside-tv.md)） |
| 设置面板上不显示 | TCL 设置是系统级浮层，层级高于 `TYPE_APPLICATION_OVERLAY` |
| 待机断 WiFi | 电视待机后 adbd 掉线，属固件行为；测试屏保不要用 `KEYCODE_SLEEP` 远程验证 |

## 踩过的坑

| 现象 | 原因 | 修法 |
| --- | --- | --- |
| 状态条不出现，logcat 报 `did not then call Service.startForeground()` | `startForeground()` 只写在 `onCreate`，重复 `start-foreground-service` 时不再调用 | `onStartCommand` 里也调一次 |
| 温度一直是「正常」 | 清单里没有 `INTERNET` 权限，连本地 socket 被拒 | 加 `INTERNET`（仅连 `127.0.0.1`） |
| 首版宽度压住首页按钮 | 单行 6 段 | 改两行 |

---

## 卸载

```bash
adb shell am start-foreground-service -n com.victor.tvhud/.HudService --es action stop
adb uninstall com.victor.tvhud
adb shell settings put secure screensaver_components com.tcl.appreciate.art/.service.screensaver.ScreenSaverBootService
```
