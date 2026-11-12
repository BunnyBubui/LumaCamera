# Lumora VS Code Auto Deploy for Windows PowerShell 5.1+
# Watches project inputs. On save: incremental Gradle build -> adb install -> launch.
# Does not modify the app's source code, camera settings or AI processing logic.
[CmdletBinding()]
param(
    [switch]$Once,
    [string]$Serial = $env:ANDROID_SERIAL,
    [ValidateRange(300, 10000)][int]$PollMs = 800,
    [ValidateRange(300, 10000)][int]$QuietMs = 1200
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Gradle = Join-Path $ProjectRoot 'gradlew.bat'
$ApkPath = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$AppId = 'th.ogay.luma.preview'
$LaunchActivity = 'th.ogay.luma.CameraActivity'

function Resolve-Adb {
    $cmd = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($env:LOCALAPPDATA) { $sdkRoots += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    foreach ($sdk in $sdkRoots) {
        if ([string]::IsNullOrWhiteSpace($sdk)) { continue }
        $candidate = Join-Path $sdk 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'adb.exe not found. Install Android SDK Platform-Tools and set ANDROID_HOME / ANDROID_SDK_ROOT or add platform-tools to PATH.'
}

function Select-Device([string]$adb, [string]$requestedSerial) {
    $lines = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed. Check Android SDK Platform-Tools.' }
    $connected = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device(?:\s|$)') { $connected += $Matches[1] }
    }
    if ($requestedSerial) {
        if ($connected -notcontains $requestedSerial) {
            throw "Device '$requestedSerial' not online. Check USB debugging authorization and run: adb devices"
        }
        return $requestedSerial
    }
    if ($connected.Count -eq 0) {
        throw 'No authorized Android device detected. Enable USB debugging, reconnect and accept the authorization popup; run adb devices.'
    }
    if ($connected.Count -gt 1) {
        throw "More than one device detected: $($connected -join ', '). Run scripts\auto-deploy.ps1 -Serial DEVICE_SERIAL"
    }
    return $connected[0]
}

function Get-InputsStamp {
    # Only watch project inputs; never app/build, .gradle, .git, or generated APKs.
    $inputs = @(
        (Join-Path $ProjectRoot 'app\src'),
        (Join-Path $ProjectRoot 'app\build.gradle.kts'),
        (Join-Path $ProjectRoot 'build.gradle.kts'),
        (Join-Path $ProjectRoot 'settings.gradle.kts'),
        (Join-Path $ProjectRoot 'gradle.properties'),
        (Join-Path $ProjectRoot 'gradle\libs.versions.toml')
    )
    $records = New-Object 'System.Collections.Generic.List[string]'
    foreach ($inputPath in $inputs) {
        if (-not (Test-Path -LiteralPath $inputPath)) { continue }
        $item = Get-Item -LiteralPath $inputPath
        if ($item.PSIsContainer) {
            $files = @(Get-ChildItem -LiteralPath $inputPath -File -Recurse -ErrorAction Stop)
        } else {
            $files = @($item)
        }
        foreach ($file in $files) {
            if ($file.Name -match '(~$|\.swp$|\.tmp$|\.bak$)') { continue }
            $records.Add(('{0}|{1}|{2}' -f $file.FullName, $file.Length, $file.LastWriteTimeUtc.Ticks))
        }
    }
    $records.Sort()
    return [string]::Join("`n", $records)
}

function Invoke-Deploy([string]$adb, [string]$device) {
    Write-Host ''
    Write-Host ('[{0}] Building Lumora debug...' -f (Get-Date -Format 'HH:mm:ss')) -ForegroundColor Cyan

    Push-Location $ProjectRoot
    try {
        & $Gradle ':app:assembleDebug' '--console=plain'
        $buildExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($buildExitCode -ne 0) {
        Write-Host 'BUILD FAILED. Keeping the existing app on the phone. Fix the error and save again.' -ForegroundColor Red
        return $false
    }
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        Write-Host "BUILD ERROR: APK missing at $ApkPath" -ForegroundColor Red
        return $false
    }

    Write-Host ('[{0}] Installing on {1}...' -f (Get-Date -Format 'HH:mm:ss'), $device) -ForegroundColor Cyan
    & $adb '-s' $device 'install' '-r' $ApkPath
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'INSTALL FAILED. Check device connection, storage and APK signing key.' -ForegroundColor Red
        Write-Host 'Do not uninstall automatically: uninstalling can delete Lumora app data.' -ForegroundColor Yellow
        return $false
    }

    # Android will normally stop/restart an updated process during APK install.
    # Explicitly relaunch it to show the change on the mirrored phone.
    & $adb '-s' $device 'shell' 'am' 'force-stop' $AppId | Out-Null
    & $adb '-s' $device 'shell' 'am' 'start' '-n' "$AppId/$LaunchActivity"
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Installed, but could not open the app. Launch Lumora manually.' -ForegroundColor Yellow
        return $false
    }
    Write-Host ('[{0}] Ready. Save a source file to deploy the next change.' -f (Get-Date -Format 'HH:mm:ss')) -ForegroundColor Green
    return $true
}

if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) {
    throw "gradlew.bat not found in $ProjectRoot. Copy both .vscode/ and scripts/ into the root of the cloned Lumora repository."
}

$adbPath = Resolve-Adb
$selectedDevice = Select-Device $adbPath $Serial
Write-Host "Project: $ProjectRoot"
Write-Host "ADB:     $adbPath"
Write-Host "Device:  $selectedDevice"
Write-Host "App:     $AppId"

$lastStamp = Get-InputsStamp
$null = Invoke-Deploy $adbPath $selectedDevice
if ($Once) { return }

Write-Host ''
Write-Host 'Watching app/src and Gradle config files. Press Ctrl+C to stop.' -ForegroundColor Yellow
while ($true) {
    Start-Sleep -Milliseconds $PollMs
    $currentStamp = Get-InputsStamp
    if ($currentStamp -eq $lastStamp) { continue }

    # Wait for the project to stop changing to avoid rebuilding on every keystroke
    # or multiple files modified by one VS Code Save All action.
    do {
        Start-Sleep -Milliseconds $QuietMs
        $stableStamp = Get-InputsStamp
        if ($stableStamp -eq $currentStamp) { break }
        $currentStamp = $stableStamp
    } while ($true)

    $lastStamp = $stableStamp
    $null = Invoke-Deploy $adbPath $selectedDevice
}
