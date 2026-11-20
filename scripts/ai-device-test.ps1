# Lumora physical-device smoke runner (PowerShell 5.1+).
# Build -> install IN PLACE -> launch -> collect screenshot/logcat -> optional tests.
# Never uninstalls the app or clears its user data.
[CmdletBinding()]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [switch]$SkipBuild,
    [switch]$RunMaestro,
    [switch]$RunAndroidTests
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Gradle = Join-Path $ProjectRoot 'gradlew.bat'
$Apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$Timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$ReportDir = Join-Path $ProjectRoot "artifacts\ai-device\$Timestamp"
New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null

$Report = [ordered]@{
    status = 'failed'
    stage = 'preflight'
    date_utc = [DateTime]::UtcNow.ToString('o')
    branch = ''
    commit = ''
    device_serial = ''
    device_model = ''
    android_version = ''
    app_id = ''
    apk = $Apk
    screenshot = ''
    logcat = ''
    maestro_executed = $false
    instrumentation_executed = $false
    error = ''
}

function Assert-NativeExit([string]$What) {
    if ($LASTEXITCODE -ne 0) {
        throw "$What failed with exit code $LASTEXITCODE"
    }
}

function Resolve-Adb {
    $found = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }

    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    foreach ($root in $roots) {
        if ([string]::IsNullOrWhiteSpace($root)) { continue }
        $candidate = Join-Path $root 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    $fallback = 'C:\Android\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $fallback -PathType Leaf) { return $fallback }
    throw 'ADB not found. Install platform-tools or add adb.exe to PATH.'
}

function Select-Serial([string]$Adb, [string]$Requested) {
    $rows = @(& $Adb devices)
    Assert-NativeExit 'adb devices'
    $connected = @(foreach ($row in $rows) {
        if ($row -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
    })
    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        if ($connected -notcontains $Requested) {
            throw "Device '$Requested' not authorized or not connected."
        }
        return $Requested
    }
    if ($connected.Count -eq 0) { throw 'No authorized Android devices detected.' }
    if ($connected.Count -gt 1) {
        throw "Multiple devices present ($($connected -join ', ')); select one using -Serial."
    }
    return [string]$connected[0]
}

try {
    if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) {
        throw "Missing gradlew.bat at $Gradle"
    }
    $config = Get-Content -LiteralPath (Join-Path $ProjectRoot 'app\build.gradle.kts') -Raw
    $match = [regex]::Match($config, 'applicationId\s*=\s*"([^"]+)"')
    if (-not $match.Success) { throw 'Unable to detect applicationId from app/build.gradle.kts.' }
    $AppId = $match.Groups[1].Value
    $Report.app_id = $AppId

    $adb = Resolve-Adb
    $device = Select-Serial $adb $Serial
    $Report.device_serial = $device

    $Report.device_model = ((& $adb -s $device shell getprop ro.product.model) | Out-String).Trim()
    Assert-NativeExit 'read device model'
    $Report.android_version = ((& $adb -s $device shell getprop ro.build.version.release) | Out-String).Trim()
    Assert-NativeExit 'read Android version'

    if (Get-Command 'git.exe' -ErrorAction SilentlyContinue) {
        $Report.branch = ((& git -C $ProjectRoot rev-parse --abbrev-ref HEAD) | Out-String).Trim()
        $Report.commit = ((& git -C $ProjectRoot rev-parse HEAD) | Out-String).Trim()
    }

    if (-not $SkipBuild) {
        $Report.stage = 'build'
        Push-Location $ProjectRoot
        try {
            & $Gradle ':app:assembleDebug' '--console=plain'
            Assert-NativeExit 'Gradle assembleDebug'
        } finally {
            Pop-Location
        }
    }
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        throw "Missing debug APK: $Apk"
    }

    $Report.stage = 'install'
    & $adb -s $device install -r $Apk
    Assert-NativeExit 'adb install -r'

    $Report.stage = 'launch'
    & $adb -s $device shell am force-stop $AppId
    Assert-NativeExit 'stop previous process'
    & $adb -s $device shell monkey -p $AppId -c android.intent.category.LAUNCHER 1
    Assert-NativeExit 'launch via Android launcher'
    Start-Sleep -Seconds 3
    $appProcessIds = ((& $adb -s $device shell pidof $AppId) | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($appProcessIds)) {
        throw "App process '$AppId' not running after launch. Inspect logcat."
    }

    $Report.stage = 'screenshot'
    $remote = "/sdcard/Download/lumora-agent-$Timestamp.png"
    $local = Join-Path $ReportDir 'launch.png'
    & $adb -s $device shell screencap -p $remote
    Assert-NativeExit 'capture device screenshot'
    & $adb -s $device pull $remote $local
    Assert-NativeExit 'pull device screenshot'
    & $adb -s $device shell rm $remote | Out-Null
    $Report.screenshot = $local

    if ($RunMaestro) {
        $Report.stage = 'maestro'
        $Maestro = Get-Command 'maestro' -ErrorAction SilentlyContinue
        if (-not $Maestro) { throw 'Maestro CLI not found on PATH.' }
        $flow = Join-Path $ProjectRoot 'tests\maestro\lumora-launch.yaml'
        & $Maestro.Source "--device=$device" 'test' $flow "--test-output-dir=$ReportDir"
        Assert-NativeExit 'Maestro launch flow'
        $Report.maestro_executed = $true
    }

    if ($RunAndroidTests) {
        $Report.stage = 'instrumentation'
        Push-Location $ProjectRoot
        try {
            & $Gradle ':app:connectedDebugAndroidTest' '--console=plain'
            Assert-NativeExit 'Gradle connectedDebugAndroidTest'
            $Report.instrumentation_executed = $true
        } finally {
            Pop-Location
        }
    }

    $Report.stage = 'complete'
    $Report.status = 'passed'
} catch {
    $Report.error = $_.Exception.Message
    Write-Warning $Report.error
} finally {
    if ($Report.device_serial) {
        try {
            $adb = Resolve-Adb
            $logPath = Join-Path $ReportDir 'logcat.txt'
            @(& $adb -s $Report.device_serial logcat -d -v threadtime -t 2500) |
                Set-Content -LiteralPath $logPath -Encoding UTF8
            $Report.logcat = $logPath
        } catch {
            Write-Warning "Could not collect logcat: $($_.Exception.Message)"
        }
    }
    $Report | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $ReportDir 'result.json') -Encoding UTF8
    Write-Host "Lumora device report: $ReportDir"
    Write-Host "Status: $($Report.status); Last stage: $($Report.stage)"
}

if ($Report.status -ne 'passed') { exit 1 }
