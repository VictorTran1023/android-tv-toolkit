# TCL Android TV Hacking Guide

> From "find the TV on your LAN" to "a proxy you can toggle with the remote + YouTube".
> **Tested on: TCL T7K** (MediaTek MT5879, Android 11, 32-bit userspace)

[![Android](https://img.shields.io/badge/Android-11-brightgreen)]()
[![ABI](https://img.shields.io/badge/ABI-armeabi--v7a-orange)]()
[![Chip](https://img.shields.io/badge/SoC-MediaTek%20MT5879-blue)]()

**English** | [中文](README.md)

---

## Who this is for

- You own a **China-market TCL / FFALCON Android TV** and want to sideload apps, run a proxy, and watch YouTube
- You got stuck on "no Google services", "TCL refuses to install APKs", or "ADB keeps saying unauthorized"
- You want a **step-by-step record that explains *why*, not just *what***

Out of scope: flashing firmware, modifying system partitions, root, cracking subscriptions.

---

## 0. Disclaimer

- **Only operate on devices you own.**
- Disabling system components and sideloading apps carries risk. The author is not liable for damage.
- **No proxy nodes, accounts or subscription links are provided here.** Bring your own.
- Using proxy tools may be regulated in your jurisdiction. You assume the risk.
- All IPs, MACs and subscription URLs in this repo are placeholders.

---

## 1. Identify your TV first

This decides every technical choice that follows.

```bash
adb shell getprop ro.product.cpu.abilist        # supported ABIs
adb shell getprop ro.product.cpu.abilist64      # 64-bit ABIs (empty = none)
adb shell getprop ro.zygote                     # zygote32 / zygote64_32
adb shell uname -m                              # armv8l = 32-bit kernel
```

Measured on the T7K:

| Check | Value | Meaning |
| --- | --- | --- |
| `ro.product.cpu.abilist` | `armeabi-v7a,armeabi` | 32-bit only |
| `ro.product.cpu.abilist64` | *(empty)* | no 64-bit support |
| `ro.zygote` | `zygote32` | 32-bit process spawner |
| `uname -m` | `armv8l` | **the kernel itself runs AArch32** |
| `/system/lib64` | missing | no 64-bit libraries |

**Conclusion: this is a pure 32-bit system.** An APK shipping only `arm64-v8a`
will never install — it fails with `INSTALL_FAILED_NO_MATCHING_ABIS`.

> Most "I downloaded an APK and it won't install" cases are this, not permissions.

---

## 2. Find the TV on your LAN

### 2.1 Don't trust ping

TVs and firewalls often drop ICMP, so a ping sweep reports zero hosts alive.
Force ARP resolution with TCP instead:

```powershell
1..254 | ForEach-Object -Parallel {
  $ip = "192.168.1.$_"
  $c = [System.Net.Sockets.TcpClient]::new()
  try { if ($c.ConnectAsync($ip, 5555).Wait(250) -and $c.Connected) { $ip } }
  catch {} finally { $c.Dispose() }
} -ThrottleLimit 128
arp -a | Select-String '192\.168\.1\.'
```

Even a closed port triggers an ARP lookup, so `arp -a` afterwards lists the real hosts.

### 2.2 Identify the model via SSDP

```powershell
$msg = "M-SEARCH * HTTP/1.1`r`nHOST: 239.255.255.250:1900`r`nMAN: `"ssdp:discover`"`r`nMX: 2`r`nST: ssdp:all`r`n`r`n"
$udp = [System.Net.Sockets.UdpClient]::new()
$ep  = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Parse('239.255.255.250'), 1900)
$b = [Text.Encoding]::ASCII.GetBytes($msg)
[void]$udp.Send($b, $b.Length, $ep)
# then Receive() several times and read LOCATION / SERVER
```

TCL TVs run several UPnP stacks at once. Their `description.xml` leaks the model directly:

```xml
<modelName>tcl_mt5879_cn</modelName>
<X_brandName>TCL</X_brandName>
<channelName>tcl</channelName>
```

Then check mDNS for Apple casting support:

```powershell
# Query TXT records of _airplay._tcp.local, expect:
#   model=AppleTV3,2
#   srcvers=366.0
#   features=0x527FFFF7,0x1E
```

`model=AppleTV3,2` is the identity a TV adopts when it implements AirPlay 2 —
meaning you can cast from an iPhone/Mac with **no app installed at all**.

---

## 3. Enabling ADB (TCL-specific)

Generic Android guides tell you to "tap Build Number 7 times". **TCL ignores that.**

The real path:

```
Settings → About → Device Info (本机信息)
    → press the remote: Up, Down, Left, Right
    → an "ADB" toggle appears
```

On this firmware the "Developer options" page contains **only the ADB toggle** —
no USB debugging, no "revoke authorizations", no wireless debugging.
TCL cut out the standard authorization UI, which matters later.

⚠️ **TCL turns the ADB toggle off on every power cycle.** That's by design.

Verify:

```bash
adb connect 192.168.1.100:5555
adb devices -l
# expect: 192.168.1.100:5555  device  product:tcl_mt5879_cn_db model:tcl_mt5879_cn
```

---

## 4. Breaking through ADB authorization (the core of this repo)

### 4.1 Symptom

```
$ adb connect 192.168.1.100:5555
failed to authenticate to 192.168.1.100:5555

$ adb devices
192.168.1.100:5555    unauthorized
```

The TV **never** shows "Allow USB debugging" — no matter how many times you reconnect,
which adb version you use, or whether you reboot the TV.

### 4.2 Diagnosis

Enable the adb server trace and watch the real exchange:

```
auth.cpp:150  loaded new key from 'C:\Users\you\.android\adbkey' with fingerprint C92E...
transport.cpp  from remote: [AUTH] arg0=1 (len=20)     ← device sends a challenge
auth.cpp:279  adb_auth_sign len=256                    ← we sign it
transport.cpp  to remote:   [AUTH] arg0=2 (len=256)    ← send the signature
transport.cpp  from remote: [AUTH] arg0=1 (len=20)     ← not recognized, challenge again
transport.cpp  to remote:   [AUTH] arg0=3 (len=712)    ← send public key, request authorization
```

The last line is the smoking gun: the public-key payload is **712 bytes of base64**
(`QAAAAD0lDlXr...`).

That is the **new key encoding introduced in adb 34+**. TCL's adbd (Android 11 but heavily
patched) only understands the **legacy binary RSA structure**, fails to parse it, and
rejects the request outright — so the dialog never appears. The TV isn't waiting for
you to tap "Allow"; it doesn't understand your key.

### 4.3 Fix

Use [scripts/adb-auth-legacy.py](scripts/adb-auth-legacy.py) to hand-shake manually
and send the legacy public key:

```
uint32 LE modulus length | modulus (big-endian) | uint32 LE exponent | "host@host\0"
```

…while **keeping the TCP connection open** so the user can tap Allow.

```bash
python scripts/adb-auth-legacy.py --host 192.168.1.100 --port 5555
# The TV should now show "Allow USB debugging?"
# Tick "Always allow from this computer" → OK
```

Once authorized, the TV stores your key in `adb_keys`, and **plain adb works from then on**.
After a reboot you only need to re-enable the ADB toggle — no re-authorization.

### 4.4 Why doesn't adb do this itself?

adb keeps its key at `%USERPROFILE%\.android\adbkey` and **must** read it from there.
In a restricted environment (sandbox, locked-down account) where that path isn't writable,
adb has no private key, cannot sign, and fails instantly with the same
`failed to authenticate` — a trap that is easily misread as "the TV rejected me".

Self-check: `ls -l ~/.android/adbkey`. If it's missing, that's your problem.

---

## 5. Read-only inspection

Look before you touch:

```bash
adb shell getprop ro.build.version.release     # Android 11
adb shell getprop ro.build.display.id          # firmware build
adb shell pm list packages -3                  # third-party apps
adb shell pm list packages -s                  # system apps
adb shell pm list packages -d                  # disabled (empty = untouched)
adb shell "ps -A -o RSS,NAME | sort -rn | head -15"
adb shell df -h /data
adb shell settings get global window_animation_scale
```

On the T7K: roughly **50 resident TCL services + 18 preinstalled third-party apps**.
The biggest memory consumers are *not* video apps — they are `com.tcl.walleve`
(screensaver gallery, 225 MB) and `com.tcl.guard` (security center, 172 MB).

---

## 6. Sideloading APKs (bypassing TCL's verifier)

### 6.1 Symptom

```
adb install -r app.apk
Failure [INSTALL_FAILED_VERIFICATION_FAILURE]
```

Turning verification off doesn't help:

```bash
adb shell settings put global verifier_verify_adb_installs 0
adb shell settings put global package_verifier_enable 0
# still fails
```

### 6.2 Cause

logcat shows the verifier starting and then going silent:

```
D OverseasAppConfig: Verifying begin
D OverseasAppConfig: pkgName = com.example.app
```

`OverseasAppConfig` runs inside **system_server (PID 435)** — TCL patched
`PackageManagerService`. A non-interactive install (adb) has no UI to ask the user,
so the only possible outcome is a silent reject.

### 6.3 Workaround: use a path that *has* a UI

```bash
adb push app.apk /sdcard/Download/app.apk
adb shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity
```

Install it from Dangbei Market's "APK manager" on the TV. A foreground app initiating
the install gets the risk-confirmation dialog, which the user can accept.

> Dangbei Market holds `android.permission.INSTALL_PACKAGES`, so it uses the privileged
> install path. Any app market or file manager with an install UI should work —
> the key is **having a UI**.

---

## 7. Proxy client: FlClash

### 7.1 Choosing a client

| Client | Core | TV friendliness | Verdict |
| --- | --- | --- | --- |
| v2rayNG | Xray | phone UI, FAB unreachable by D-pad | not recommended |
| FlClash | mihomo (Clash Meta) | tablet-style sidebar, remote works | **recommended** |
| Provider's own app | sing-box | usually Flutter phone UI, no LEANBACK | backup |

Grab the `armeabi-v7a` build:

```
https://github.com/chen08209/FlClash/releases
→ FlClash-x.y.z-android-armeabi-v7a.apk
```

| | |
| --- | --- |
| ![FlClash dashboard](docs/images/flclash-dashboard.png) | ![FlClash proxies](docs/images/flclash-proxies.png) |
| Dashboard: status, rule mode, service health | Proxies: policy groups and nodes |

### 7.2 Import the subscription: deep link, not the keyboard

**Pitfall:** the TV's Sogou IME treats `https://...` as pinyin and converts it into
Chinese characters (a real URL became "输入、林奇、PKOO6皮虚对面M啊V啊"). Typing it is hopeless.

**Fix:** FlClash registers `clash://`, `clashmeta://` and `flclash://`. Push the link:

```bash
URL="https://example.com/link/TOKEN?clash=1"
ENC=$(python -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$URL")
adb shell "am start -a android.intent.action.VIEW -d 'clash://install-config?url=$ENC&name=my-sub'"
```

The TV asks "Create a profile from this link?" — confirm, and the URL is stored **verbatim**,
so it can auto-update later.

> Don't use "import from file" with a downloaded YAML — you'd lose subscription updates.

### 7.3 Pick a node

Proxies → tap a policy group (e.g. "国外流量") → choose a node.
The default may point at a **plaintext node** (`security=none`); modern cores refuse
to connect to those, so switch away.

### 7.4 Verify

Dashboard → service status: Google / GitHub / YouTube all green means you're through.

---

## 8. YouTube: why SmartTube is mandatory

The official YouTube TV client depends heavily on **Google Play Services** for both
sign-in and playback authorization. China-market TCL firmware ships **no** `com.google.*`
packages, so the official app installs and then dies on "Google Play services required".

**Use SmartTube** (open source, no GMS, remote friendly, 4K, ad-free):

```
https://github.com/yuliskov/SmartTube/releases
→ SmartTube_stable_*.apk (armeabi-v7a or universal)
```

Sign in: the TV shows an activation code → open `youtube.com/activate` on your phone/PC.

### 8.1 Cap the quality at 1080p

`Settings → Video → Preferred video quality → 1080p`

Without the cap it ramps to 4K, which most proxy nodes cannot sustain (see §9).

### 8.2 Audio format

| Option | Meaning |
| --- | --- |
| `0.15Mbps opus` | Opus — better quality per bit at this rate. **Pick this.** |
| `mp4a` | AAC — maximum compatibility; fall back here if a soundbar/AVR misbehaves |
| `DRC` | Dynamic Range Compression — lifts quiet dialogue, squashes loud scenes. Useful at night; costs dynamics |
| `Disabled` | Lets the player choose, **possibly the DRC variant**. Not recommended |

---

## 9. Performance: who is actually to blame for stutter

### 9.1 Don't blame the CPU first

List the decoders the vendor actually registered:

```bash
adb shell "grep -o 'name=\"[^\"]*\"' /vendor/etc/media_codecs.xml"
```

MT5879 supports H.264 / HEVC / **VP8 / VP9 / AV1** / VVC / Dolby Vision /
AVS2 / AVS3 / MPEG2 / VC1 / WMV3 — all hardware decoded.

> MIME trap: AV1 is `video/av01`, VP9 is `video/x-vnd.on2.vp9`.
> Grepping for `video/av1` or `video/vp9` misses both — a mistake the author made.

Confirm at runtime:

```bash
adb shell logcat -d | grep -E 'OMX\.MS\..*Decoder|output format changed'
# e.g. I MediaCodec: [OMX.MS.AV1.Decoder], output format changed...
#      stride = 3840, slice-height = 2160   ← 4K
```

### 9.2 The bottleneck is usually bandwidth

Measured while playing 4K HDR AV1:

| Metric | Measured | Verdict |
| --- | --- | --- |
| Stream | 3840×2160, `color-transfer=3` (HDR10) | 4K HDR |
| Tunnel throughput | 4.29 Mbps | 4K needs 20–40 Mbps |
| Buffer | `buffered position == position` | zero look-ahead |
| Player behaviour | `Release` → `Init` every ~22 s | segment download timeout loop |
| CPU | 89% of one core, 22% of total | not saturated |
| GPU frame time | p50 3 ms / p90 7 ms | not the bottleneck |

**Verdict: stutter is bandwidth starvation, not weak hardware.** 4K segments are too
large, the load times out, the buffer is discarded and retried — forever.

### 9.3 Single-stream vs multi-stream: don't be fooled by "aggregate bandwidth"

A node's total capacity and what one TCP connection can pull are different numbers:

| Node | Single | 4 parallel | Ratio |
| --- | --- | --- | --- |
| HK-03 | 2.29 Mbps | **11.14 Mbps** | 4.9× |
| HK-01 | 1.55 Mbps | 7.28 Mbps | 4.7× |
| SG-02 | 2.23 Mbps | 7.18 Mbps | 3.2× |

Cause: **the larger the RTT, the harder it is for a single TCP connection to fill the
pipe** (BDP limit). And YouTube typically opens one video connection plus one audio
connection — so what you actually get is close to the *single-stream* number.

**Therefore:** 4K needs 20–40 Mbps while a single stream gives 2–4 Mbps. No node will
fix 4K here. 1080p (~1 Mbps) is the realistic target for this TV.

Benchmark script: [scripts/bench-nodes.py](scripts/bench-nodes.py).

---

## 10. Troubleshooting cheat sheet

| Symptom | Root cause | Fix |
| --- | --- | --- |
| APK won't install, `NO_MATCHING_ABIS` | 32-bit system | use an `armeabi-v7a` build |
| `adb install` → `VERIFICATION_FAILURE` | TCL-patched PMS silently rejects | push to Download, install via an app market |
| `adb connect` stuck at `unauthorized`, no dialog | new key encoding, legacy adbd | use `adb-auth-legacy.py` |
| instant `failed to authenticate` | no readable `~/.android/adbkey` | generate a key at the standard path |
| adb unreachable after reboot | TCL disables ADB on power cycle | re-enable via About → Device Info → U/D/L/R |
| URL turns into Chinese characters | Sogou TV IME pinyin prediction | use the `clash://` deep link |
| Two VPN apps fight | Android allows one active VPN | keep a single client |
| 4K video loops on buffering | bandwidth < bitrate | cap at 1080p or switch nodes |
| `grep` finds no AV1/VP9 | wrong MIME strings | use `video/av01` / `video/x-vnd.on2.vp9` |

---

## 11. Scripts

| File | Purpose |
| --- | --- |
| [scripts/adb-auth-legacy.py](scripts/adb-auth-legacy.py) | legacy public-key handshake — fixes "TV never shows the auth prompt" |
| [scripts/parse-sub.py](scripts/parse-sub.py) | parse a subscription, flag plaintext (`security=none`) nodes |
| [scripts/bench-nodes.py](scripts/bench-nodes.py) | per-node throughput benchmark via a temporary sing-box instance |
| [scripts/analyze-apk.py](scripts/analyze-apk.py) | static APK analysis: ABI, core, TV support, permissions |

Requirements: Python 3.9+, `adb` (platform-tools). Benchmarking also needs `sing-box`.

---

## 12. Final thought

The value here isn't "which apps to install" but **asking *why* each step fails**:

- won't install → not permissions, but ABI
- won't connect → not networking, but key encoding
- stutters → not performance, but bitrate
- can't click → not the OS, but a UI never designed for a remote

May this save you a few hours.

## License

MIT
