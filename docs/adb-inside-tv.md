# 在电视内部跑 ADB（以 TCL 为例）

> 一个普通应用如何以 shell 身份读取系统信息——不 root、不 Shizuku、不额外装东西。
> 原理适用于 Android 5+；能否拿到温度取决于品牌的 SELinux 策略和 `thermalservice` 实现，换品牌先用 `adb shell dumpsys thermalservice` 验证。

## 问题

状态条想显示 CPU 温度。常规做法是读 `/sys/class/thermal/thermal_zone*/temp`，但在 TCL T7K（Android 11）上：

```
$ adb shell cat /sys/class/thermal/thermal_zone0/temp
cat: /sys/class/thermal/thermal_zone0/temp: Permission denied
```

**连 shell 都被 SELinux 拒绝**，普通应用更不可能。而系统自己知道温度——`dumpsys thermalservice` 里有：

```
Cached temperatures:
  Temperature{mValue=46.0, mType=0, mName=cpu, mStatus=0}
```

问题是 `dumpsys` 需要 `DUMP` 权限，应用同样拿不到。

## 思路

电视的 adbd 在 `5555` 端口监听（`persist.adb.tcp.port=5555`，实测重启后仍在）。既然 shell 身份能读，那就让应用**自己成为 adb 客户端**，连 `127.0.0.1:5555`。

这和「用电脑 adb」没有区别，只是把电脑换成了电视里的一个 socket。

```
App ──TCP──> 127.0.0.1:5555 (adbd) ──> shell 用户 ──> dumpsys thermalservice
```

## 协议要点

ADB 的线协议很小，实现一个「只跑命令」的客户端约 200 行：

| 消息 | 作用 |
| --- | --- |
| `CNXN` | 版本与最大包长握手 |
| `AUTH` `arg0=1` | 设备下发 20 字节 challenge |
| `AUTH` `arg0=2` | 客户端回 RSA 签名（PKCS#1 v1.5，SHA-1 前缀 + challenge） |
| `AUTH` `arg0=3` | 密钥未知时上报公钥（`RSAPublicKey` 结构 + base64 + `name\0`） |
| `OPEN` `shell:<cmd>` | 打开一个 shell 流 |
| `OKAY` / `WRTE` / `CLSE` | 流控与输出 |

签名细节：adbd 把 challenge 当作 SHA-1 digest 直接验，所以要手工拼 PKCS#1 块（`0x00 0x01 0xFF… 0x00 || SHA1_PREFIX || token`）再用 `RSA/ECB/NoPadding` 加密。

## 授权

第一次连接时，电视会弹「允许 USB 调试吗？」，带 RSA 指纹。勾选 **一律允许** 后，公钥写入 `/data/misc/adb/adb_keys`，之后长期有效。

> **注意**：密钥存在应用私有目录（`filesDir/adbkey`），**卸载重装会重新生成**，于是再问一次。
> 所以升级 HUD 时尽量覆盖安装；TCL 只允许「卸载再装」时，每次都要重新授权——这是该方案最大的使用成本。

## 安全边界

这段代码给应用开了一个 shell，必须写死边界：

| 约束 | 做法 |
| --- | --- |
| 只跑固定命令 | `shell()` 是包私有方法，命令全部由调用方（`ThermalProbe`）硬编码，不接受任何外部字符串 |
| 不出网 | 只连 `127.0.0.1`，不解析域名、不连外网；`INTERNET` 权限仅用于本地 socket |
| 密钥不外泄 | 存在私有目录，不写日志、不上传 |
| 失败即降级 | 连不上 / 未授权时静默返回 0，界面退回三档文字，不重试轰炸 |

## 拿到什么，拿不到什么

| 目标 | 结果 | 说明 |
| --- | --- | --- |
| CPU 温度 °C | ✅ | `dumpsys thermalservice` 解析 `mName=cpu` |
| 电池/皮肤/NPU 温度 | ✅（多为 0） | 同一条 dump 里的其他 `Temperature{}` |
| 更细的温区 | ❌ | `sysfs` 对 shell 也拒绝 |
| GPU 占用 | ❌ | `mali0/gpuinfo` 拒绝，无 GED/devfreq 节点；型号可由 OpenGL/AIDA64 得到（Mali-G52） |
| 遥控器语音键 | ❌ | 该键在输入层就是 `KEY_UNKNOWN`，到不了应用层（见下） |

## 一个反例：为什么按键拦截失败

原本想用「语音键呼出状态条」。无障碍服务加 `canRequestFilterKeyEvents` 后，`capabilities=8`（可过滤按键）也拿到了，但按下语音键时 `onKeyEvent` **一次都没被调用**。

`getevent` 给出原因：

```
/dev/input/event3: EV_KEY  KEY_UNKNOWN  DOWN
```

TCL 蓝牙遥控（`BT_RC802A_C1`）的语音键发的是未映射的 HID 码，Android 在到达无障碍层之前就丢弃。要改只能动 `/system/usr/keylayout`（需 root），所以这个功能放弃了。

## 成本与收益

| 维度 | 值 |
| --- | --- |
| 代码量 | ~200 行（`hud/.../Adb.java`） |
| 依赖 | 无（JDK 自带 `javax.crypto`） |
| 额外授权 | 一次性「允许 USB 调试」 |
| 换来的能力 | 真实 °C，且未来可扩展到任何「shell 能读、应用读不到」的只读信息 |
| 替代方案 | Shizuku（要另外装服务端）、root（本机不可行） |

## 参考

协议与实现思路参考了开源项目 [free-my-tv](https://github.com/simbasjungles/free-my-tv)（MIT）的 `Adb.java`，本项目按其许可改编并裁剪为只读用途。
