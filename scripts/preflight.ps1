<#
.SYNOPSIS
    Read-only snapshot of an Android TV before you change anything.

.DESCRIPTION
    Saves the package list, disabled-package list, all three settings tables,
    system properties and the handful of values the other scripts touch
    (animation scales, screensaver component, HOME handler).

.EXAMPLE
    $env:TV_IP = "192.168.1.50"
    .\scripts\preflight.ps1

.EXAMPLE
    .\scripts\preflight.ps1 -TvIp 192.168.1.50 -OutDir backup\before-debloat
#>
param(
    [string]$TvIp = $env:TV_IP,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Continue"
if (-not $TvIp) { throw "Pass -TvIp <address> or set the TV_IP environment variable." }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "adb was not found in PATH." }

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if (-not $OutDir) { $OutDir = Join-Path "backup" (Get-Date -Format "yyyyMMdd-HHmmss") }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Sh([string]$cmd) { (& adb shell $cmd 2>&1) -join "`n" }
function Save([string]$name, [string]$text) {
    Set-Content -Path (Join-Path $OutDir $name) -Value $text -Encoding UTF8
    Write-Output ("{0}: {1} chars" -f $name, $text.Length)
}

& adb start-server 2>&1 | Out-Null
& adb connect $TvIp 2>&1 | Out-Null
& adb wait-for-device

Save "packages-all.txt"      (Sh "pm list packages")
Save "packages-disabled.txt" (Sh "cmd package list packages -d")
Save "settings-global.txt"   (Sh "settings list global")
Save "settings-secure.txt"   (Sh "settings list secure")
Save "settings-system.txt"   (Sh "settings list system")
Save "props.txt"             (Sh "getprop")
Save "state.txt"             ((Sh "uptime") + "`n--- anim ---`n" +
    (Sh "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale") +
    "`n--- screensaver ---`n" + (Sh "settings get secure screensaver_components; settings get secure screensaver_enabled") +
    "`n--- home ---`n" + (Sh "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME | grep -E 'name=|packageName=' | head -4"))

Write-Output ""
Write-Output ("snapshot saved to {0}" -f (Resolve-Path $OutDir))
Write-Output ("rollback with  .\scripts\restore-all.ps1 -Snapshot '{0}'" -f (Resolve-Path $OutDir))

