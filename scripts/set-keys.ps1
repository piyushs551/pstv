# Saves your API keys to keys.properties (git-ignored; never committed). They are baked into the app at
# build time, so you never type them on the TV.
# Press Enter on any question to keep what is already saved.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\set-keys.ps1
$f = Join-Path $PSScriptRoot "..\keys.properties"
function Plain($s) { [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)) }

$old = @{}
if (Test-Path $f) { Get-Content $f | ForEach-Object { if ($_ -match '^\s*([^#=]+)=(.*)$') { $old[$Matches[1].Trim()] = $Matches[2] } } }

Write-Host "TMDB: free key at themoviedb.org > Settings > API. Use the shorter 'API Key (v3)', not the long Read Access Token."
$tmdb = Plain (Read-Host "TMDB API key (Enter = keep)" -AsSecureString)
Write-Host "OpenSubtitles: the API key from opensubtitles.com > API consumers."
Write-Host "Your OpenSubtitles username and password are NOT asked here: enter them in the app (Settings > Subtitles)."
$osk = Plain (Read-Host "OpenSubtitles API key (Enter = keep)" -AsSecureString)

if (-not $tmdb) { $tmdb = $old["tmdb"] }
if (-not $osk)  { $osk  = $old["os_key"] }
@("tmdb=$tmdb", "os_key=$osk", "os_user=", "os_pass=") | Set-Content -Encoding ASCII $f
Write-Host "Saved to $f. Now rebuild: .\install.ps1 -Ip <your Fire TV IP> -Build" -ForegroundColor Green
