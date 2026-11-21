# Saves your API keys AND your playlist to keys.properties (git-ignored; never committed). They are built into
# the app at build time, so you never type them on the TV (you can still override them in the app's Settings).
# Press Enter on any question to keep what is already saved.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\set-keys.ps1
$f = Join-Path $PSScriptRoot "..\keys.properties"
function Plain($s) { [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)) }
function Esc($v) { ($v -replace '\\', '\\\\') }          # keys.properties is a Java properties file: escape backslashes

$old = @{}
if (Test-Path $f) { Get-Content $f | ForEach-Object { if ($_ -match '^\s*([^#=]+)=(.*)$') { $old[$Matches[1].Trim()] = $Matches[2] -replace '\\\\', '\' } } }
function Keep($new, $name) { if ($new) { $new } else { $old[$name] } }

Write-Host "`n== API keys ==" -ForegroundColor Cyan
Write-Host "TMDB: themoviedb.org > Settings > API. Use the shorter 'API Key (v3)', not the long Read Access Token."
$tmdb = Keep (Plain (Read-Host "TMDB API key (Enter = keep)" -AsSecureString)) "tmdb"
Write-Host "OpenSubtitles: the API key from opensubtitles.com > API consumers. Your OpenSubtitles username/password are entered in the app (Settings > Subtitles)."
$osk = Keep (Plain (Read-Host "OpenSubtitles API key (Enter = keep)" -AsSecureString)) "os_key"

Write-Host "`n== Your playlist (your own provider; the app ships no content) ==" -ForegroundColor Cyan
$cur = if ($old["source"]) { $old["source"] } else { "none" }
Write-Host "Currently: $cur.   1 = M3U URL   2 = Xtream login   Enter = keep"
$choice = Read-Host "Playlist type"
$source = switch ($choice) { "1" { "m3u" } "2" { "xtream" } default { $old["source"] } }
$m3u = $old["m3u_url"]; $xh = $old["x_host"]; $xu = $old["x_user"]; $xp = $old["x_pass"]
if ($source -eq "m3u") {
    $m3u = Keep (Plain (Read-Host "M3U URL (Enter = keep; hidden because it may contain your login)" -AsSecureString)) "m3u_url"
} elseif ($source -eq "xtream") {
    $xh = Keep (Read-Host "Xtream server, e.g. http://host:port (Enter = keep)") "x_host"
    $xu = Keep (Read-Host "Xtream username (Enter = keep)") "x_user"
    $xp = Keep (Plain (Read-Host "Xtream password (Enter = keep)" -AsSecureString)) "x_pass"
}

@("tmdb=$(Esc $tmdb)", "os_key=$(Esc $osk)", "os_user=", "os_pass=",
  "source=$source", "m3u_url=$(Esc $m3u)", "x_host=$(Esc $xh)", "x_user=$(Esc $xu)", "x_pass=$(Esc $xp)") | Set-Content -Encoding ASCII $f
Write-Host "`nSaved to $f (git-ignored). Now rebuild: .\install.ps1 -Ip <your Fire TV IP> -Build" -ForegroundColor Green
