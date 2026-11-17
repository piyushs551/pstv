# Asks for your API keys once and saves them to keys.properties (git-ignored; never committed).
# They are baked into the app at build time, so you never type them on the TV.
# Press Enter to skip any item. Usage: powershell -ExecutionPolicy Bypass -File scripts\set-keys.ps1
$f = Join-Path $PSScriptRoot "..\keys.properties"
function Plain($s) { [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)) }
Write-Host "TMDB: free key at themoviedb.org > Settings > API (the v3 'API Key')."
$tmdb = Plain (Read-Host "TMDB API key" -AsSecureString)
Write-Host "OpenSubtitles: API key from opensubtitles.com (Consumers) plus your own account login (needed to download)."
$osk = Plain (Read-Host "OpenSubtitles API key" -AsSecureString)
$osu = Read-Host "OpenSubtitles username"
$osp = Plain (Read-Host "OpenSubtitles password" -AsSecureString)
@("tmdb=$tmdb", "os_key=$osk", "os_user=$osu", "os_pass=$osp") | Set-Content -Encoding ASCII $f
Write-Host "Saved to $f. Now rebuild: .\install.ps1 -Ip <your Fire TV IP> -Build" -ForegroundColor Green
