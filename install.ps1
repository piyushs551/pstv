# Installs the debug APK on your Fire TV over the network.
# Usage:  .\install.ps1 -Ip 192.168.1.50 [-Build]
# On the Fire TV: Settings > My Fire TV > Developer Options > ADB debugging ON; accept the prompt on screen.
param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [int]$Port = 5555,
    [switch]$Build
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $home_ = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Android\sdk" }
    $env:Path += ";" + (Join-Path $home_ "platform-tools")
}
if ($Build -or -not (Test-Path "app\build\outputs\apk\debug\app-debug.apk")) { & .\build.ps1 }

$target = "${Ip}:${Port}"
adb connect $target
Write-Host "If the TV shows 'Allow USB debugging?', choose Always allow, then press Enter here."; [void](Read-Host)
adb -s $target wait-for-device

$abis = (adb -s $target shell getprop ro.product.cpu.abilist).Trim()
$want = (Select-String -Path gradle.properties -Pattern '^pstv\.abi=(.+)$').Matches.Groups[1].Value
Write-Host "Device ABIs: $abis   |   APK built for: $want"
if (($abis -split ',') -notcontains ($want -split ',')[0]) {
    Write-Host "WARNING: the APK ABI filter does not match this device. Edit pstv.abi in gradle.properties and rebuild." -ForegroundColor Yellow
}

adb -s $target install -r -d "app\build\outputs\apk\debug\app-debug.apk"
if ($LASTEXITCODE -ne 0) { throw "Install failed" }
adb -s $target shell monkey -p com.pstv.tv -c android.intent.category.LEANBACK_LAUNCHER 1 | Out-Null
Write-Host "Installed and launched PSTV." -ForegroundColor Green
