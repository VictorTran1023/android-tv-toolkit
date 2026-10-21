# 回归测试：改完之后，怎么证明电视还是好的

改动系统设置、停用预装、换屏保之后，最大的风险不是「变砖」，而是某个入口悄悄失效，几天后才发现。这套回归测试把「日常必须能用」的路径固化成 15 项检查，每次改动后跑一遍，用结果说话。

```powershell
$env:TV_IP = "<TV_IP>"
.\scripts\regress.ps1
```

- 全程约 40 秒；会唤醒电视、开一次设置和一次视频 App，结束后回到桌面
- 不修改任何设置、不启停任何包
- 证据保存在 `audit/regress/<时间戳>/`，含每一步截图与 `00-summary.txt`
- **桌面和设置包名自动探测**（`cmd package resolve-activity`），不再写死 TCL

---

## 检查项

| 组 | # | 检查 | 判定 |
| --- | --- | --- | --- |
| 系统 | 1 | 开机完成 | `sys.boot_completed=1` |
| 系统 | 2 | 桌面进程 | `pidof <自动探测到的桌面包>` 非空 |
| 输入 | 3 | Home 键 | 焦点回到桌面包 |
| 输入 | 4 | 遥控焦点移动 | 按 ↓ 前后截图差分 > 0.2%，且明显高于静止基线 |
| 输入 | 5 | 画面非黑屏 | 平均亮度 > 12 |
| 应用 | 6 | 设置可打开 | `am start -a android.settings.SETTINGS` 后焦点进入设置 |
| 应用 | 7 | 设置可关闭 | 返回后焦点不再停留在设置 |
| 应用 | 8 | 视频 App 可启动 | 焦点进入 `-AppPackage`（默认 SmartTube） |
| 应用 | 9 | 可退出回桌面 | 按 Home 后焦点回到桌面包 |
| 网络 | 10 | Wi-Fi 已连接 | `cmd wifi status` 含 `connected to` |
| 网络 | 11 | 外网连通 | `ping 223.5.5.5` 有回包（失败仅 WARN） |
| 完整性 | 12 | 关键系统包仍在 | 桌面 + 设置 + `-CriticalPackages` 全部 enabled |
| 完整性 | 13 | 预期停用项 | `-ExpectDisabled` 里的包仍为 disabled |
| 稳定性 | 14 | 崩溃日志 | `logcat -b crash` 为空 |
| 稳定性 | 15 | ANR | `/data/anr` 无文件 |

### 参数

| 参数 | 默认 | 用途 |
| --- | --- | --- |
| `-AppPackage` | `org.smarttube.stable` | 用来测「应用能启动」的目标；没装会记 WARN，不阻塞 |
| `-LauncherPackage` | 自动探测 | 某些固件 `resolve-activity` 不准时手动指定 |
| `-SettingsPackage` | 自动探测 | 同上 |
| `-CriticalPackages` | 空 | 厂商关键包，例如 TCL 的 `com.tcl.systemserver,com.tcl.autopair` |
| `-ExpectDisabled` | 空 | 你本次停用、且希望重启后仍保持的包 |

示例：

```powershell
# TCL：停用 walleve 后，确认它重启后仍 disabled
.\scripts\regress.ps1 -ExpectDisabled com.tcl.walleve `
    -CriticalPackages com.tcl.systemserver,com.tcl.autopair,com.tcl.tcl_bt_rcu_service

# 其他品牌：只跑通用检查
.\scripts\regress.ps1

# 换一个视频 App
.\scripts\regress.ps1 -AppPackage com.example.player
```

### 为什么用截图差分测遥控

部分厂商桌面（TCL 是其中之一）不向无障碍服务暴露焦点节点：`uiautomator dump` 里只有一个 `FrameLayout`。所以改为：

1. 连拍两张（间隔 1.2 s）得到静止基线；
2. 注入 `KEYCODE_DPAD_DOWN`；
3. 再拍一张，比较变化像素比例。

TCL 实测：静止基线 0.00–0.05%，按键后 70%+（焦点框移动 + 页面滚动），区分度足够。其他品牌如果桌面暴露焦点节点，也可以改用 `uiautomator`，但截图差分更通用。

---

## 判定与处置

| 结果 | 含义 | 处置 |
| --- | --- | --- |
| `0 FAIL` | 通过 | 记录证据 |
| 出现 FAIL | 有硬性回归 | 先 `.\scripts\restore-all.ps1`，再复测 |
| 仍 FAIL | 不是本次改动引起 | 检查 ADB、网络、厂商 OTA 是否变更；保留 `00-summary.txt` 与截图 |
| 仅 WARN | 非关键项（ICMP 被拦、焦点未变、目标 App 未装） | 人工看详情，不阻塞 |

退出码：`0` 通过，`1` 有 FAIL，可直接接 CI 或定时任务。

> 「设置」这一项用平台 intent `android.settings.SETTINGS`，不保证直接落到厂商设置首页；有的品牌会先弹选择器。看到 `Settings opens` 为 WARN/FAIL 时，先确认电视上是不是弹了选择框，再决定要不要用 `-SettingsPackage`。

---

## 与其他脚本的关系

| 脚本 | 时机 |
| --- | --- |
| `preflight.ps1` | 改动前，生成快照 |
| `restore-all.ps1` | 出问题 / 想回到改动前 |
| `regress.ps1` | 改动后、重启后、系统升级后 |

建议顺序：**快照 → 改动 → 回归 →（异常则回滚 → 再回归）**。
