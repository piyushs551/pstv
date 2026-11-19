# Installs the debug APK on your Fire TV over the network.
# Usage:  .\install.ps1 -Ip 192.168.1.50 [-Build]
# On the Fire TV: Settings > My Fire TV > Developer Options > ADB debugging ON. The first time, accept the prompt on screen.
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
# Wait for the TV (no Enter needed). Reconnects by itself if the connection is stale/offline.
$ErrorActionPreference = "Continue"      # adb writes status text to stderr; do not treat that as a failure
$deadline = (Get-Date).AddSeconds(90); $warned = $false; $tries = 0
while ($true) {
    $state = ((& adb -s $target get-state 2>&1 | ForEach-Object { "$_" }) -join " ").Trim()
    if ($state -ne "device") { Write-Host "  waiting for the TV (state: $state)" -ForegroundColor DarkGray }
    if ($state -eq "device") { break }
    if ((Get-Date) -gt $deadline) { throw "Could not reach the TV (state: $state). Wake the TV, check ADB debugging is on and the IP is right, then run again." }
    if ($state -match "unauthorized") {
        if (-not $warned) { Write-Host "The TV is asking 'Allow USB debugging?'. Choose Allow (tick Always allow once) with the remote." -ForegroundColor Yellow; $warned = $true }
    } else {
        # offline / not found: drop the stale connection and connect again (restart adb on the 3rd try)
        $tries++
        if ($tries -ge 3 -and $tries % 3 -eq 0) { & adb kill-server 2>&1 | Out-Null; & adb start-server 2>&1 | Out-Null }
        & adb disconnect $target 2>&1 | Out-Null
        Start-Sleep -Milliseconds 600
        & adb connect $target 2>&1 | Out-Null
    }
    Start-Sleep -Seconds 3
}
$ErrorActionPreference = "Stop"

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
