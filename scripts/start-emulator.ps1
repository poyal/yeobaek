param(
    [string]$SdkPath,
    [ValidatePattern('^yeobaek-[a-z0-9_-]+$')][string]$Name = 'yeobaek-api36',
    [ValidateRange(5554, 5682)][int]$Port = 5580
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (Test-Path -LiteralPath '.tools/env.ps1') { . .tools/env.ps1 }
if (!$SdkPath) { $SdkPath = $env:YEOBAEK_EMULATOR_SDK }
if (!$SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (!$SdkPath) { $SdkPath = Join-Path $projectRoot '.tools/android-sdk' }
$SdkPath = (Resolve-Path -LiteralPath $SdkPath).Path
$emulator = Join-Path $SdkPath 'emulator/emulator.exe'
$manager = Join-Path $SdkPath 'cmdline-tools/latest/bin/avdmanager.bat'
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
foreach ($tool in @($emulator, $manager, $adb)) {
    if (!(Test-Path -LiteralPath $tool)) { throw "Missing Android tool: $tool" }
}
if ($Port % 2) { throw 'The emulator console port must be even.' }
$serial = "emulator-$Port"
$devices = & $adb devices
if ($devices -match "^$serial\s") {
    $runningName = (& $adb -s $serial emu avd name | Select-Object -First 1).Trim()
    if ($runningName -ne $Name) { throw "Port $Port is already used by $runningName." }
    Write-Output "$Name is already running at $serial."
    exit 0
}
$environmentBefore = @{}
foreach ($key in @('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_USER_HOME', 'ANDROID_AVD_HOME')) {
    $environmentBefore[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_SDK_ROOT = $SdkPath
    $env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-home'
    $env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools/avd'
    New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
    if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$Name.ini"))) {
        'no' | & $manager create avd --name $Name --package 'system-images;android-36;default;x86_64' --device pixel_7 --path (Join-Path $env:ANDROID_AVD_HOME "$Name.avd")
        if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed. Install the Android 36 AOSP x86_64 system image first.' }
        $configPath = Join-Path $env:ANDROID_AVD_HOME "$Name.avd/config.ini"
        $config = [IO.File]::ReadAllText($configPath)
        $config = $config -replace '(?m)^hw.lcd.width=.*$', 'hw.lcd.width=720' -replace '(?m)^hw.lcd.height=.*$', 'hw.lcd.height=1600' -replace '(?m)^hw.lcd.density=.*$', 'hw.lcd.density=320'
        [IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))
    }
    $logs = Join-Path $projectRoot '.tools/emulator-logs'
    New-Item -ItemType Directory -Force $logs | Out-Null
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $Name, '-port', "$Port", '-no-snapshot', '-no-audio', '-no-boot-anim', '-gpu', 'host', '-memory', '3072') -WindowStyle Normal -RedirectStandardOutput (Join-Path $logs "$Name-out.log") -RedirectStandardError (Join-Path $logs "$Name-err.log") | Out-Null
    Write-Output "Opened $Name at $serial. AVD data: $env:ANDROID_AVD_HOME"
} finally {
    foreach ($key in $environmentBefore.Keys) { [Environment]::SetEnvironmentVariable($key, $environmentBefore[$key], 'Process') }
}
