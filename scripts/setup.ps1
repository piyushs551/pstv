# Checks your Windows machine and installs what is missing. Asks before every install.
# Run from PowerShell:  powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # the progress bar makes Invoke-WebRequest downloads very slow in Windows PowerShell 5.1
$sdk = "C:\Android\sdk"

function Ask($msg) { (Read-Host "$msg [y/N]") -match '^(y|yes)$' }

Write-Host "`n== PSTV environment check ==" -ForegroundColor Cyan

# --- JDK 17 ---
$javaOk = $false
try { $v = (& java -version 2>&1 | Out-String); if ($v -match 'version "17\.') { $javaOk = $true } } catch {}
if ($javaOk) { Write-Host "JDK 17: found" -ForegroundColor Green }
else {
    Write-Host "JDK 17: missing (Temurin 17 via winget, ~180 MB)"
    if (Ask "Install JDK 17 with winget?") {
        winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-source-agreements --accept-package-agreements
    } else { Write-Host "Skipped. The build needs JDK 17." -ForegroundColor Yellow }
}
$jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { [Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk.FullName, "User"); $env:JAVA_HOME = $jdk.FullName }

# --- Android command-line tools ---
$sdkmanager = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
if (Test-Path $sdkmanager) { Write-Host "Android command-line tools: found" -ForegroundColor Green }
else {
    Write-Host "Android command-line tools: missing (~150 MB from dl.google.com into $sdk)"
    if (Ask "Download and install them?") {
        $zip = "$env:TEMP\cmdline-tools.zip"
        Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $zip
        New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
        Expand-Archive $zip -DestinationPath "$sdk\cmdline-tools" -Force
        if (Test-Path "$sdk\cmdline-tools\latest") { Remove-Item "$sdk\cmdline-tools\latest" -Recurse -Force }
        Rename-Item "$sdk\cmdline-tools\cmdline-tools" "latest"
        Remove-Item $zip
    }
}

# --- SDK components ---
if (Test-Path $sdkmanager) {
    Write-Host "SDK packages: platform-tools (adb), platforms;android-34, build-tools;34.0.0 (~300 MB)"
    if (Ask "Accept Google's SDK licences and install/update these?") {
        "y`n" * 30 | & $sdkmanager --sdk_root=$sdk --licenses | Out-Null
        & $sdkmanager --sdk_root=$sdk "platform-tools" "platforms;android-34" "build-tools;34.0.0"
    }
    [Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User"); $env:ANDROID_HOME = $sdk
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($userPath -notlike "*$sdk\platform-tools*") {
        if (Ask "Add $sdk\platform-tools to your user PATH (so 'adb' works)?") {
            [Environment]::SetEnvironmentVariable("Path", "$userPath;$sdk\platform-tools", "User")
        }
    }
}

Write-Host "`nDone. Open a NEW PowerShell window so the environment variables apply, then run .\build.ps1" -ForegroundColor Cyan
