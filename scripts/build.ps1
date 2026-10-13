$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$localEnvironment = Join-Path $projectRoot '.tools/env.ps1'
if (Test-Path -LiteralPath $localEnvironment) { . $localEnvironment }
if (!$env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home' }
if (!$env:ANDROID_HOME -and !$env:ANDROID_SDK_ROOT -and !(Test-Path -LiteralPath 'local.properties')) {
    $env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
}
if (!$env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-home' }
& (Join-Path $projectRoot 'gradlew.bat') --console=plain @args
exit $LASTEXITCODE
