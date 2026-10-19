<div align="center">

# TCL Android TV Toolkit

LAN discovery · ADB authorization · Sideloading · Proxy chain · Playback tuning

![Platform](https://img.shields.io/badge/Android%20TV-9%2B-3DDC84?logo=android&logoColor=white&style=flat-square)
![Scope](https://img.shields.io/badge/Scope-China--market%20firmware-F5A623?style=flat-square)
![Python](https://img.shields.io/badge/Python-3.9%2B-3776AB?logo=python&logoColor=white&style=flat-square)
![License](https://img.shields.io/badge/License-MIT-8E8E93?style=flat-square)
![CI](https://github.com/VictorTran1023/tcl-tv-guide/actions/workflows/ci.yml/badge.svg)

[English](README.en.md) · [中文](README.md) · [Discussions](https://github.com/VictorTran1023/tcl-tv-guide/discussions)

</div>

---

## Who this is for

- Owners of a **China-market TCL / FFALCON Android TV** with a restricted system
- Anyone stuck at `adb unauthorized` where the TV **never shows the authorization dialog**
- People who want to sideload apps and configure a proxy **without root or flashing**
- Readers who need a **reproducible troubleshooting path**, not scattered commands

## Not covered

| Scope | Note |
| --- | --- |
| root / system partition modification / flashing | out of scope |
| Proxy nodes, accounts, subscriptions | bring your own |
| Non-Android TV platforms | webOS, Tizen, Roku are not applicable |

## Scope

| Item | Note |
| --- | --- |
| OS | Android TV 9 and above |
| Architecture | 32-bit (`armeabi-v7a`) or 64-bit — verify first |
| Devices | TCL / FFALCON used as reference; the method applies to most China-market Android TVs |
| Prerequisite | TV and PC on the same LAN |

Four problem classes and their causes:

| Symptom | Cause |
| --- | --- |
| Cannot connect | public-key encoding incompatible with the vendor adbd |
| Cannot install | vendor-patched PMS blocks non-interactive installs |
| Cannot navigate | app UI not designed for a remote |
| Cannot play | bitrate exceeds available bandwidth |

## Quick start

```bash
git clone https://github.com/VictorTran1023/tcl-tv-guide.git
cd tcl-tv-guide

adb connect <TV_IP>:5555
adb devices -l

python scripts/parse-sub.py "<SUBSCRIPTION_URL>"
```

## Contents

| # | Section | Scope |
| --- | --- | --- |
| 1 | [Environment](#1-environment) | ABI, kernel bitness, decoding capability |
| 2 | [LAN discovery](#2-lan-discovery) | ARP sweep, SSDP, mDNS |
| 3 | [Enabling ADB](#3-enabling-adb) | vendor entry paths |
| 4 | [Authorization](#4-authorization) | legacy public-key handshake |
| 5 | [Sideloading](#5-sideloading) | bypassing the install verifier |
| 6 | [Proxy client](#6-proxy-client) | client selection and subscription import |
| 7 | [YouTube](#7-youtube) | options without GMS |
| 8 | [Playback tuning](#8-playback-tuning) | codecs and stutter diagnosis |
| 9 | [Troubleshooting](#9-troubleshooting) | common failures |
| 10 | [Scripts](#10-scripts) | four CLI utilities |

---

## 1. Environment

```bash
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abilist64
adb shell getprop ro.zygote
adb shell uname -m
```

| Check | Interpretation |
| --- | --- |
| `abilist64` empty | pure 32-bit system; only `armeabi-v7a` APKs install |
| `zygote32` | 32-bit process model |
| `uname -m` reports `armv8l` | kernel runs in AArch32 |

APKs shipping only `arm64-v8a` fail with `INSTALL_FAILED_NO_MATCHING_ABIS`.

---

## 2. LAN discovery

ICMP is frequently dropped; force ARP resolution over TCP instead:

```powershell
1..254 | ForEach-Object -Parallel {
  $ip = "192.168.1.$_"
  $c = [System.Net.Sockets.TcpClient]::new()
  try { if ($c.ConnectAsync($ip, 5555).Wait(250) -and $c.Connected) { $ip } }
  catch {} finally { $c.Dispose() }
} -ThrottleLimit 128

arp -a | Select-String '192\.168\.1\.'
```

SSDP exposes the model directly:

```powershell
$msg = "M-SEARCH * HTTP/1.1`r`nHOST: 239.255.255.250:1900`r`nMAN: `"ssdp:discover`"`r`nMX: 2`r`nST: ssdp:all`r`n`r`n"
$udp = [System.Net.Sockets.UdpClient]::new()
$ep  = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Parse('239.255.255.250'), 1900)
$b   = [Text.Encoding]::ASCII.GetBytes($msg)
[void]$udp.Send($b, $b.Length, $ep)
```

Device identifiers in the UPnP description:

```xml
<modelName>...</modelName>
<X_brandName>TCL</X_brandName>
<channelName>tcl</channelName>
```

A TXT record `model=AppleTV3,2` on `_airplay._tcp.local` indicates AirPlay 2 support,
so an iPhone or Mac can cast directly.

---

## 3. Enabling ADB

Entry points vary by vendor; two common patterns:

| Pattern | Path |
| --- | --- |
| Hidden toggle | Settings → About → Device Info → remote: Up, Down, Left, Right |
| Developer mode | Settings → About → tap Build Number repeatedly |

China-market firmware may expose only the ADB toggle, with no USB debugging and no
authorization list.

> The toggle typically resets on every power cycle.

```bash
adb connect <TV_IP>:5555
adb devices -l
```

---

## 4. Authorization

**Symptom**

```
failed to authenticate to <TV_IP>:5555
<TV_IP>:5555    unauthorized
```

No authorization dialog appears on the TV.

**Diagnosis**

With adb server tracing enabled:

```
[AUTH] arg0=1 (len=20)      device challenge
[AUTH] arg0=2 (len=256)     signature
[AUTH] arg0=1 (len=20)      rejected
[AUTH] arg0=3 (len=712)     public key  ← base64 encoded
```

adb 34+ sends a base64 public key; some vendor adbd implementations only parse the legacy
binary structure, fail, and reject without prompting.

**Fix**

```bash
python scripts/adb-auth-legacy.py --host <TV_IP> --port 5555
```

The script sends the legacy structure and holds the connection open:

```
uint32 LE modulus length | modulus (big-endian) | uint32 LE exponent | "host@host\0"
```

Once approved, the key is stored in `adb_keys` and plain adb works thereafter.

> adb requires its private key at `~/.android/adbkey`. If that path is unwritable,
> adb cannot sign and reports the same error.

---

## 5. Sideloading

**Symptom**

```
adb install -r app.apk
Failure [INSTALL_FAILED_VERIFICATION_FAILURE]
```

Global verifier switches usually do not help:

```bash
adb shell settings put global verifier_verify_adb_installs 0
adb shell settings put global package_verifier_enable 0
```

**Diagnosis**

```
D OverseasAppConfig: Verifying begin
D OverseasAppConfig: pkgName = com.example.app
```

The verifier lives in system_server, injected through a vendor-patched
PackageManagerService. A non-interactive install has no UI for confirmation, so it
can only fail.

**Fix**

Use an install path that has a UI:

```bash
adb push app.apk /sdcard/Download/app.apk
adb shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity
```

Any app market or file manager with an install UI works — the requirement is a
foreground app initiating the install so the system can show its confirmation dialog.

---

## 6. Proxy client

| Client | Core | UI form | Remote support |
| --- | --- | --- | --- |
| v2rayNG | Xray | phone layout, floating button not focusable | needs mouse or mirroring |
| FlClash | mihomo | tablet-style sidebar with card lists | focusable with D-pad |
| Provider client | sing-box | usually Flutter phone layout | implementation dependent |

Pick the build matching the device ABI:

```
https://github.com/chen08209/FlClash/releases
```

| Dashboard | Proxies |
| --- | --- |
| ![Dashboard](docs/images/flclash-dashboard.png) | ![Proxies](docs/images/flclash-proxies.png) |
| Status · outbound mode · service health | Policy groups · node latency |

**Subscription import**

TV input methods tend to convert URLs into Chinese characters, so keyboard entry is
not viable. Push the deep link instead:

```bash
URL="https://example.com/link/TOKEN?clash=1"
ENC=$(python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$URL")
adb shell "am start -a android.intent.action.VIEW -d 'clash://install-config?url=$ENC&name=main'"
```

The URL is stored verbatim, preserving subscription auto-update; file-based imports
lose that ability.

**Node selection**

Proxies → policy group → node. Some subscriptions default to a `security=none`
plaintext node, which current cores refuse to use.

---

## 7. YouTube

The official client relies on Google Play Services for account and playback
authorization, so firmware without GMS cannot run it.

Options:

| Option | Note |
| --- | --- |
| [SmartTube](https://github.com/yuliskov/SmartTube/releases) | open-source client, no GMS, sign-in and remote support |
| Web version | best compatibility, limited controls and quality |

SmartTube sign-in: the TV displays an activation code; enter it at
`youtube.com/activate`.

**Video quality**

```
Settings → Video → Preferred video quality → 1080p
```

**Audio**

| Option | Note |
| --- | --- |
| `opus` | better quality per bit |
| `mp4a` | AAC, widest compatibility, fallback for external audio gear |
| `DRC` | dynamic range compression, useful at night, reduces dynamics |
| `Disabled` | delegates to the player, may select the DRC variant |

---

## 8. Playback tuning

**Enumerate decoders**

```bash
adb shell "grep -o 'name=\"[^\"]*\"' /vendor/etc/media_codecs.xml"
```

Common hardware formats: AVC, HEVC, VP8, VP9, AV1, VVC, Dolby Vision, AVS2/AVS3,
MPEG2, VC1.

> MIME mapping: AV1 is `video/av01`, VP9 is `video/x-vnd.on2.vp9`. Searching for
> `video/av1` or `video/vp9` misses both.

**Diagnosing stutter**

| Metric | How to observe | Interpretation |
| --- | --- | --- |
| Buffer | `buffered` vs `position` in `dumpsys media_session` | equal values mean no look-ahead — bandwidth starvation |
| Player recreation | `Release` / `Init` frequency in `logcat` | periodic recreation usually means segment load timeouts |
| Decoder | `OMX.*Decoder` and `output format changed` in `logcat` | confirms hardware decoding and actual resolution |
| CPU | `ps -A -o RSS,NAME`, `/proc/<pid>/stat` | only a saturated core indicates a decoding bottleneck |
| GPU | frame timings from `dumpsys gfxinfo <pkg>` | shows whether rendering is the limit |

**Bitrate and bandwidth**

| Resolution | Typical bitrate |
| --- | --- |
| 1080p | 1–6 Mbps |
| 1440p | 8–12 Mbps |
| 2160p HDR | 20–40 Mbps |

On high-latency paths a single TCP connection is window-limited, so real throughput
often falls below the node's nominal capacity (BDP limit). Players typically open one
video and one audio connection, so effective throughput approaches the single-stream
figure.

**Recommendations**

- Cap the preferred quality at 1080p to leave headroom
- Rank nodes by single-stream throughput and latency, not by parallel benchmarks
- If 4K is required, verify sustained single-stream throughput above 20 Mbps

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `NO_MATCHING_ABIS` | system bitness vs APK | use an `armeabi-v7a` build |
| `VERIFICATION_FAILURE` | patched PMS blocks non-interactive installs | install from an app market on the TV |
| `unauthorized`, no dialog | incompatible key encoding | `adb-auth-legacy.py` |
| instant `failed to authenticate` | private key missing or unreadable | check `~/.android/adbkey` |
| unreachable after reboot | ADB toggle reset | re-enable it |
| URL becomes Chinese text | TV input method prediction | import via deep link |
| two VPNs conflict | one active VPN allowed | keep a single client |
| 4K loops on buffering | bandwidth below bitrate | cap at 1080p |
| AV1/VP9 not found by grep | wrong MIME strings | `video/av01` / `video/x-vnd.on2.vp9` |

---

## 10. Scripts

| File | Purpose |
| --- | --- |
| [`adb-auth-legacy.py`](scripts/adb-auth-legacy.py) | legacy public-key handshake |
| [`parse-sub.py`](scripts/parse-sub.py) | subscription parser and plaintext-node detector |
| [`bench-nodes.py`](scripts/bench-nodes.py) | node throughput benchmark (single / parallel) |
| [`analyze-apk.py`](scripts/analyze-apk.py) | APK static analysis: ABI, core, TV support |

Requires Python 3.9+ and `adb`; benchmarking additionally requires `sing-box`.

---

## Notice

For debugging and study on devices you own. Sideloading and proxy usage are at your own
risk and subject to local law. No nodes, accounts or subscription links are provided.

## License

[MIT](LICENSE)
