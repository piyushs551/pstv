# Captures screenshots of your existing IPTV app over ADB as a layout reference.
# Safe: it only sends D-pad/back keys (never OK/Select), so it cannot start purchases or deletions.
# Usage:  .\scripts\capture-reference.ps1 -Ip 192.168.1.50 [-Package com.example.nextv]
# Then use -Interactive to navigate to other screens yourself with the remote and capture each on Enter.
param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [string]$Package,
    [string]$Out = "C:\iptv\reference",
    [switch]$Interactive
)
$ErrorActionPreference = "Stop"
$t = "${Ip}:5555"
adb connect $t | Out-Null
New-Item -ItemType Directory -Force $Out | Out-Null

if (-not $Package) {
    $found = adb -s $t shell pm list packages | Select-String -Pattern "nex" | ForEach-Object { $_.Line -replace '^package:', '' }
    if (-not $found) { throw "Could not find the app. Pass -Package. List with: adb shell pm list packages" }
    $Package = @($found)[0]
    Write-Host "Using package: $Package   (other matches: $($found -join ', '))"
}

function Shot($name) {
    adb -s $t shell screencap -p /sdcard/_ref.png
    adb -s $t pull /sdcard/_ref.png (Join-Path $Out "$name.png") | Out-Null
    Write-Host "  captured $name"
}
function Key($k, $n = 1) { 1..$n | ForEach-Object { adb -s $t shell input keyevent $k; Start-Sleep -Milliseconds 700 } }

adb -s $t shell monkey -p $Package -c android.intent.category.LEANBACK_LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 8
Shot "00_launch"
foreach ($i in 1..6) { Key KEYCODE_DPAD_DOWN; Shot ("10_down_{0:00}" -f $i) }
Key KEYCODE_DPAD_UP 6
foreach ($i in 1..4) { Key KEYCODE_DPAD_RIGHT; Shot ("20_right_{0:00}" -f $i) }
Key KEYCODE_DPAD_LEFT 4
Key KEYCODE_DPAD_UP 3; Shot "30_top"
Key KEYCODE_DPAD_LEFT; Shot "31_top_left"; Key KEYCODE_DPAD_RIGHT 3; Shot "32_top_right"

if ($Interactive) {
    Write-Host "`nInteractive: use your remote to open a screen (a channel list, a movie page, the player, settings...)."
    $n = 1
    while ($true) {
        $name = Read-Host "Screen name (e.g. player, settings, search) or blank to finish"
        if (-not $name) { break }
        Shot ("40_{0:00}_{1}" -f $n++, $name)
    }
}
adb -s $t shell rm /sdcard/_ref.png
Write-Host "`nDone. Screenshots in $Out. Note: video playback may capture black; photos of the TV work for that."
