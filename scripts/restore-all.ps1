<#
.SYNOPSIS
    Undo everything the debloat / tuning scripts changed.

.DESCRIPTION
    Re-enables every package that is currently disabled, restores the animation
    scales and the screensaver component recorded by preflight.ps1, and prints
    the remaining state. Package rollback is a live diff, so it also covers
    packages disabled by hand.

.EXAMPLE
    .\scripts\restore-all.ps1

.EXAMPLE
    .\scripts\restore-all.ps1 -TvIp 192.168.1.50 -Snapshot backup\20261019-1205
#>
param(
    [string]$TvIp = $env:TV_IP,
    [string]$Snapshot = ""
)

$ErrorActionPreference = "Continue"
if (-not $TvIp) { throw "Pass -TvIp <address> or set the TV_IP environment variable." }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "adb was not found in PATH." }

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (-not $Snapshot) {
    $Snapshot = Get-ChildItem "backup" -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Snapshot -or -not (Test-Path $Snapshot)) { throw "No snapshot found. Run scripts\preflight.ps1 first." }
Write-Output ("using snapshot: " + $Snapshot)

function Sh([string]$cmd) { (& adb shell $cmd 2>&1) -join "`n" }

& adb start-server 2>&1 | Out-Null
& adb connect $TvIp 2>&1 | Out-Null
& adb wait-for-device

# 1. re-enable every currently disabled package
$disabled = (Sh "cmd package list packages -d") -split "`n" |
    Where-Object { $_ -match '^package:' } |
    ForEach-Object { $_.Substring(8).Trim() } |
    Where-Object { $_ }
foreach ($p in $disabled) {
    $r = Sh "pm enable $p"
    Write-Output ("[enable] {0} -> {1}" -f $p, $r.Trim())
}
if (-not $disabled) { Write-Output "[enable] nothing disabled" }

# 2. animation scales back to the snapshot values (absent means "never set")
function Get-Global([string]$name) {
    $line = Get-Content (Join-Path $Snapshot "settings-global.txt") -ErrorAction SilentlyContinue |
        Where-Object { $_ -match ("^" + $name + "=") } | Select-Object -First 1
    if ($line) { return ($line -split "=", 2)[1] } else { return $null }
}
foreach ($k in @("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
    $v = Get-Global $k
    if ($null -eq $v) {
        Sh "settings delete global $k" | Out-Null
        Write-Output ("[anim] {0} deleted (was unset)" -f $k)
    }
    else {
        Sh ("settings put global {0} {1}" -f $k, $v) | Out-Null
        Write-Output ("[anim] {0} = {1}" -f $k, $v)
    }
}

# 3. screensaver back to the snapshot value
$state = Get-Content (Join-Path $Snapshot "state.txt") -Raw -ErrorAction SilentlyContinue
if ($state -match "--- screensaver ---\r?\n([^\r\n]+)") {
    $ss = $Matches[1].Trim()
    if ($ss -and $ss -ne "null") {
        Sh ("settings put secure screensaver_components '" + $ss + "'") | Out-Null
        Sh "settings put secure screensaver_enabled 1" | Out-Null
        Write-Output ("[screensaver] restored to " + $ss)
    }
}

# 4. verify
Write-Output "--- remaining disabled ---"
Write-Output (Sh "cmd package list packages -d")
Write-Output "--- animations ---"
Write-Output (Sh "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale")

