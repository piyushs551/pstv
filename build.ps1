# Builds the debug APK with the Gradle wrapper.   Usage:  .\build.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "C:\Android\sdk" }
if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME. Run scripts\setup.ps1 first." }
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME) { throw "JDK 17 not found. Run scripts\setup.ps1 first." }

# Tell Gradle where the SDK is (forward slashes avoid escaping problems).
"sdk.dir=$($env:ANDROID_HOME -replace '\\','/')" | Set-Content -Encoding ASCII local.properties

& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$apk = Get-Item "app\build\outputs\apk\debug\app-debug.apk"
"{0}  ({1:N1} MB)" -f $apk.FullName, ($apk.Length / 1MB)
