<#
.SYNOPSIS
    Brand-agnostic regression suite for an Android TV after debloating or tuning.

.DESCRIPTION
    Wakes the TV, walks through the paths a user actually needs (HOME, D-pad,
    Settings, one streaming app) and checks the system is still healthy.
    It changes no settings and disables nothing; the only side effects are
    key injection, opening two apps, and leaving the TV on HOME.

    The launcher and Settings packages are auto-detected with
    `cmd package resolve-activity`; nothing is hard-coded to TCL.

.PARAMETER ExpectDisabled
    Packages that should still be disabled after your change, e.g.
    -ExpectDisabled com.tcl.walleve. Empty by default so the suite is safe
    to run on any brand.

.PARAMETER CriticalPackages
    Extra packages that must remain enabled (vendor settings, remote service,
    systemui, ...). The launcher and Settings packages are always checked.

.EXAMPLE
    .\scripts\regress.ps1

.EXAMPLE
    .\scripts\regress.ps1 -TvIp 192.168.1.50 -ExpectDisabled com.tcl.walleve

.EXAMPLE
    .\scripts\regress.ps1 -CriticalPackages com.tcl.systemserver,com.tcl.autopair
#>
param(
    [string]$TvIp = $env:TV_IP,
    [string]$AppPackage = "org.smarttube.stable",
    [string]$OutDir = "",
    [string]$LauncherPackage = "",
    [string]$SettingsPackage = "",
    [string[]]$CriticalPackages = @(),
    [string[]]$ExpectDisabled = @()
)

$ErrorActionPreference = "Continue"
if (-not $TvIp) { throw "Pass -TvIp <address> or set the TV_IP environment variable." }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "adb was not found in PATH." }

$root = Split-Path $PSScriptRoot -Parent
$caller = (Get-Location).Path
Set-Location $root
[Environment]::CurrentDirectory = (Get-Location).Path
if (-not $OutDir) {
    $OutDir = Join-Path "audit\regress" (Get-Date -Format "yyyyMMdd-HHmmss")
} elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $caller $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Sh([string]$cmd) { (& adb shell $cmd 2>&1) -join "`n" }
function Save([string]$name, [string]$text) { Set-Content -Path (Join-Path $OutDir $name) -Value $text -Encoding UTF8 }
function Shot([string]$name) {
    & adb shell "screencap -p /sdcard/regress.png" 2>&1 | Out-Null
    & adb pull /sdcard/regress.png (Join-Path $OutDir $name) 2>&1 | Out-Null
}
function Focus { (Sh "dumpsys window | grep mCurrentFocus").Trim() }

function Resolve-Pkg([string]$action) {
    $out = Sh "cmd package resolve-activity --brief -a $action | tail -1"
    $m = [regex]::Match($out, '([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/')
    if ($m.Success) { return $m.Groups[1].Value }
    return ""
}

function Installed([string]$pkg) {
    if (-not $pkg) { return $false }
    $out = Sh ("pm list packages -e " + $pkg)
    $lines = $out -split "`n" | ForEach-Object { $_.Trim() }
    return ($lines -contains ("package:" + $pkg))
}

function Resolve-Settings {
    $resolved = Resolve-Pkg "android.settings.SETTINGS"
    if ($resolved -and (Installed $resolved)) { return $resolved }

    foreach ($p in @("com.android.tv.settings", "com.android.settings", "com.tcl.settings")) {
        if (Installed $p) { return $p }
    }

    # Last resort: a launcher activity whose package name contains "settings".
    $out = Sh "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
    foreach ($line in ($out -split "`n")) {
        $m = [regex]::Match($line, '([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/')
        if (-not $m.Success) { continue }
        $p = $m.Groups[1].Value
        if ($p -match "settings" -and $p -notmatch "provider" -and (Installed $p)) { return $p }
    }
    return ""
}

$script:rows = @()
function Add([string]$name, [string]$status, [string]$detail) {
    $script:rows += [pscustomobject]@{ Check = $name; Status = $status; Detail = $detail }
    Write-Output ("[{0}] {1} - {2}" -f $status, $name, $detail)
}

Add-Type -AssemblyName System.Drawing
function DiffRatio([string]$a, [string]$b, [int]$step = 12) {
    $ia = [System.Drawing.Bitmap]::FromFile($a); $ib = [System.Drawing.Bitmap]::FromFile($b)
    try {
        $w = [Math]::Min($ia.Width, $ib.Width); $h = [Math]::Min($ia.Height, $ib.Height)
        $diff = 0; $total = 0
        for ($y = 0; $y -lt $h; $y += $step) {
            for ($x = 0; $x -lt $w; $x += $step) {
                $pa = $ia.GetPixel($x, $y); $pb = $ib.GetPixel($x, $y)
                if (([Math]::Abs($pa.R - $pb.R) + [Math]::Abs($pa.G - $pb.G) + [Math]::Abs($pa.B - $pb.B)) -gt 30) { $diff++ }
                $total++
            }
        }
        if ($total -eq 0) { return 0 }
        return [Math]::Round($diff / $total, 4)
    }
    finally { $ia.Dispose(); $ib.Dispose() }
}
function Luma([string]$path, [int]$step = 24) {
    $im = [System.Drawing.Bitmap]::FromFile($path)
    try {
        $sum = 0; $n = 0
        for ($y = 0; $y -lt $im.Height; $y += $step) {
            for ($x = 0; $x -lt $im.Width; $x += $step) {
                $p = $im.GetPixel($x, $y); $sum += 0.299 * $p.R + 0.587 * $p.G + 0.114 * $p.B; $n++
            }
        }
        if ($n -eq 0) { return 0 }
        return [Math]::Round($sum / $n, 1)
    }
    finally { $im.Dispose() }
}

& adb start-server 2>&1 | Out-Null
& adb connect $TvIp 2>&1 | Out-Null
& adb wait-for-device

# ---------------------------------------------------------------- detection
if (-not $LauncherPackage) { $LauncherPackage = Resolve-Pkg "android.intent.action.MAIN -c android.intent.category.HOME" }
if (-not $SettingsPackage) { $SettingsPackage = Resolve-Settings }
Write-Output ("[INFO] launcher=" + $LauncherPackage + " settings=" + $SettingsPackage)

$boot = (Sh "getprop sys.boot_completed").Trim()
if ($boot -eq "1") { Add "boot completed" "PASS" (Sh "uptime").Trim() } else { Add "boot completed" "FAIL" ("boot_completed=" + $boot) }

$launcherPid = (Sh ("pidof " + $LauncherPackage)).Trim()
if ($launcherPid) { Add "launcher process" "PASS" ("pid=" + $launcherPid) } else { Add "launcher process" "FAIL" ($LauncherPackage + " not running") }

& adb shell "input keyevent KEYCODE_HOME" | Out-Null
& adb shell "input keyevent KEYCODE_WAKEUP" | Out-Null
Start-Sleep -Seconds 3
$f = Focus
if ($LauncherPackage -and $f -match [regex]::Escape($LauncherPackage)) { Add "HOME key" "PASS" $f } else { Add "HOME key" "FAIL" $f }
Shot "01-home.png"

Shot "02-focus-a.png"; Start-Sleep -Milliseconds 1200; Shot "02-focus-b.png"
$base = DiffRatio (Join-Path $OutDir "02-focus-a.png") (Join-Path $OutDir "02-focus-b.png")
& adb shell "input keyevent KEYCODE_DPAD_DOWN" | Out-Null
Start-Sleep -Milliseconds 1200
Shot "02-focus-c.png"
$move = DiffRatio (Join-Path $OutDir "02-focus-b.png") (Join-Path $OutDir "02-focus-c.png")
if ($move -gt 0.002 -and $move -gt ($base * 1.5)) { Add "D-pad reaction" "PASS" ("screen changed {0:P2} (idle {1:P2})" -f $move, $base) }
else { Add "D-pad reaction" "WARN" ("screen changed {0:P2} (idle {1:P2})" -f $move, $base) }
$luma = Luma (Join-Path $OutDir "02-focus-c.png")
if ($luma -gt 12) { Add "screen not black" "PASS" ("luma " + $luma) } else { Add "screen not black" "FAIL" ("luma " + $luma) }

# Settings: platform intent first; fall back to the detected package's launcher activity.
if ($SettingsPackage) {
    & adb shell "am start -a android.settings.SETTINGS" 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    $f = Focus
    if ($f -notmatch "settings") {
        & adb shell "monkey -p $SettingsPackage -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
        Start-Sleep -Seconds 4
        $f = Focus
    }
} else {
    $f = "(no settings package detected)"
}
if ($SettingsPackage -and (($f -match [regex]::Escape($SettingsPackage)) -or ($f -match "settings"))) { Add "Settings opens" "PASS" $f } else { Add "Settings opens" "FAIL" $f }
Shot "03-settings.png"
& adb shell "input keyevent KEYCODE_BACK" | Out-Null
Start-Sleep -Seconds 2
$f = Focus
if ($f -match "settings") {
    & adb shell "input keyevent KEYCODE_BACK" | Out-Null
    Start-Sleep -Seconds 2
    $f = Focus
}
if ($f -notmatch "settings") { Add "Settings closes" "PASS" $f } else { Add "Settings closes" "FAIL" $f }

$appInstalled = (Sh ("pm list packages -e " + $AppPackage)).Trim()
$appLines = $appInstalled -split "`n" | ForEach-Object { $_.Trim() }
if ($appLines -contains ("package:" + $AppPackage)) {
    & adb shell "monkey -p $AppPackage -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null
    Start-Sleep -Seconds 7
    $f = Focus
    if ($f -match ([regex]::Escape($AppPackage))) { Add "app launches" "PASS" $f } else { Add "app launches" "WARN" $f }
    Shot "04-app.png"
    & adb shell "input keyevent KEYCODE_HOME" | Out-Null
    Start-Sleep -Seconds 2
    $f = Focus
    if ($f -match [regex]::Escape($LauncherPackage)) { Add "back to HOME" "PASS" $f } else { Add "back to HOME" "WARN" $f }
} else {
    Add "app launches" "WARN" ($AppPackage + " is not installed (pass -AppPackage)")
}

$wifi = Sh "cmd wifi status"
if ($wifi -match "connected to") { Add "Wi-Fi connected" "PASS" (($wifi -split "`n" | Select-String "connected to").Line.Trim()) }
else { Add "Wi-Fi connected" "WARN" (($wifi -split "`n" | Select-Object -First 2) -join " ") }
$ping = Sh "ping -c 2 -W 2 223.5.5.5"
if ($ping -match "2 (packets )?received|2 received") { Add "outbound network" "PASS" (($ping -split "`n" | Select-Object -Last 2) -join " ") }
else { Add "outbound network" "WARN" "no ICMP reply (router may block it)" }

# Critical packages: launcher + Settings + any vendor packages the caller names.
$critical = @($LauncherPackage, $SettingsPackage) + $CriticalPackages |
    Where-Object { $_ } | Select-Object -Unique
$enabled = Sh "pm list packages -e"
$enabledLines = $enabled -split "`n" | ForEach-Object { $_.Trim() }
$missing = @($critical | Where-Object { $enabledLines -notcontains ("package:" + $_) })
if ($critical.Count -eq 0) {
    Add "critical packages" "INFO" "none to check"
} elseif (-not $missing) {
    Add "critical packages" "PASS" ("all " + $critical.Count + " enabled")
} else {
    Add "critical packages" "FAIL" ("missing: " + ($missing -join ", "))
}

# Expected disabled packages are opt-in; empty on a generic device.
if ($ExpectDisabled.Count -eq 0) {
    Add "expected disabled" "INFO" "none specified (pass -ExpectDisabled <pkg>)"
} else {
    $disabled = Sh "cmd package list packages -d"
    $disabledLines = $disabled -split "`n" | ForEach-Object { $_.Trim() }
    $notDisabled = @($ExpectDisabled | Where-Object { $disabledLines -notcontains ("package:" + $_) })
    if (-not $notDisabled) { Add "expected disabled" "PASS" (($ExpectDisabled -join ", ") + " still disabled") }
    else { Add "expected disabled" "FAIL" ("re-enabled: " + ($notDisabled -join ", ")) }
}

$crash = Sh "logcat -d -b crash -t 40"
if (-not $crash.Trim()) { Add "crash buffer" "PASS" "empty" } else { Add "crash buffer" "WARN" (($crash -split "`n" | Select-Object -Last 3) -join " / ") }
$anr = (Sh "ls -A /data/anr 2>/dev/null | wc -l").Trim()
if ($anr -eq "0") { Add "ANR files" "PASS" "none" } else { Add "ANR files" "WARN" $anr }

$mem = Sh "free -m | head -3"
$temp = Sh "dumpsys thermalservice | grep -E 'mName=cpu' | head -2"
Save "05-resources.txt" ("== mem ==`n" + $mem + "`n== temp ==`n" + $temp + "`n== top ==`n" + (Sh "top -b -n 1 -m 6 | tail -7"))
Write-Output ("[INFO] " + (($mem -split "`n")[1..2] -join " | "))

$fail = @($script:rows | Where-Object { $_.Status -eq "FAIL" })
$warn = @($script:rows | Where-Object { $_.Status -eq "WARN" })
$script:rows | Format-Table -AutoSize | Out-String | Write-Output
Save "00-summary.txt" (($script:rows | ForEach-Object { "{0}`t{1}`t{2}" -f $_.Status, $_.Check, $_.Detail }) -join "`n")
Write-Output ("RESULT: {0} checks, {1} FAIL, {2} WARN -> {3}" -f $script:rows.Count, $fail.Count, $warn.Count, $(if ($fail.Count) { "FAILED" } else { "PASSED" }))
Write-Output ("evidence: " + (Resolve-Path $OutDir))
if ($fail.Count) { exit 1 } else { exit 0 }
