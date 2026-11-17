# Quick screenshot of the Fire TV, saved small (960 px wide) so it is cheap to send.
# Usage:  .\scripts\shot.ps1 -Ip <your Fire TV IP> [-Note "scroll jumps after row 3"]
# Files go to reference\captures\shot_001.png, shot_002.png ...
param([Parameter(Mandatory = $true)][string]$Ip, [string]$Note = "")
$ErrorActionPreference = "Stop"
$dir = Join-Path $PSScriptRoot "..\reference\captures"
New-Item -ItemType Directory -Force $dir | Out-Null
$n = ((Get-ChildItem $dir -Filter "shot_*.png" -ErrorAction SilentlyContinue | Measure-Object).Count) + 1
$name = "shot_{0:000}" -f $n
$full = Join-Path $dir "$name`_full.png"
adb connect "${Ip}:5555" | Out-Null
adb -s "${Ip}:5555" shell screencap -p /sdcard/_s.png
adb -s "${Ip}:5555" pull /sdcard/_s.png $full | Out-Null
adb -s "${Ip}:5555" shell rm /sdcard/_s.png
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($full)
$h = [int]($img.Height * 960 / $img.Width)
$small = New-Object System.Drawing.Bitmap 960, $h
$g = [System.Drawing.Graphics]::FromImage($small); $g.InterpolationMode = "HighQualityBicubic"; $g.DrawImage($img, 0, 0, 960, $h); $g.Dispose()
$out = Join-Path $dir "$name.png"; $small.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$small.Dispose(); $img.Dispose(); Remove-Item $full
if ($Note) { "$name : $Note" | Add-Content (Join-Path $dir "notes.txt") }
Write-Host "Saved $out"
