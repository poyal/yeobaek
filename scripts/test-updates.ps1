param([string]$Serial = 'emulator-5580')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (Test-Path '.tools/env.ps1') { . ./.tools/env.ps1 }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $PWD '.tools/android-sdk' }
if (-not $env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-home' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if ($Serial -notlike 'emulator-*') { throw '전용 여백 에뮬레이터가 필요합니다.' }
$avd = & $adb -s $Serial emu avd name
if ($LASTEXITCODE -ne 0 -or $avd[0] -notlike 'yeobaek-*') { throw "검사할 수 없는 기기: $Serial" }
$bt = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
$fixture = Join-Path $PWD '.tools/update-fixture'
$assets = Join-Path $fixture 'assets'
New-Item -ItemType Directory -Force -Path $assets | Out-Null
@'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.poyal.yeobaek.debug" android:versionCode="9999" android:versionName="99.0.0">
    <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36" />
    <application android:label="여백 업데이트 검사" android:hasCode="false" />
</manifest>
'@ | Set-Content -Encoding utf8 (Join-Path $fixture 'AndroidManifest.xml')
& "$bt/aapt2.exe" link -I "$env:ANDROID_HOME/platforms/android-36/android.jar" --manifest "$fixture/AndroidManifest.xml" -o "$fixture/unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw '테스트 APK 생성 실패' }
& "$bt/zipalign.exe" -f 4 "$fixture/unsigned.apk" "$fixture/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw '테스트 APK 정렬 실패' }
# Android standard debug key. Never sign the fixture with the release key.
& "$bt/apksigner.bat" sign --ks "$env:ANDROID_USER_HOME/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$assets/update-fixture.apk" "$fixture/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw '테스트 APK 서명 실패: 먼저 debug 빌드를 실행해 주세요.' }
& ./scripts/build.ps1 :app:assembleDebug :app:assembleDebugAndroidTest -PupdateFixture
if ($LASTEXITCODE -ne 0) { throw '업데이트 테스트 빌드 실패' }
$evidence = Join-Path $PWD ('.tools/verification/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + "-$Serial-updates")
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
try {
    & $adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'debug 설치 실패' }
    & $adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw '테스트 APK 설치 실패' }
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP
    & $adb -s $Serial shell wm dismiss-keyguard
    $log = & $adb -s $Serial shell am instrument -w -r -e class com.poyal.yeobaek.UpdatesFlowTest,com.poyal.yeobaek.AndroidUpdateDownloadTest com.poyal.yeobaek.debug.test/androidx.test.runner.AndroidJUnitRunner
    $result = $LASTEXITCODE
    $log | Set-Content -Encoding utf8 "$evidence/instrumentation.txt"
    $log | Write-Output
    if ($result -ne 0 -or ($log -join "`n") -notmatch 'OK \(4 tests\)') { throw "업데이트 검사 실패: $evidence" }
    @{status='PASS'; tests=4; serial=$Serial; avd=$avd[0]; apk_sha256=(Get-FileHash app/build/outputs/apk/debug/app-debug.apk).Hash.ToLower(); test_apk_sha256=(Get-FileHash app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk).Hash.ToLower()} | ConvertTo-Json | Set-Content -Encoding utf8 "$evidence/summary.json"
    Write-Output "Evidence: $evidence"
} finally {
    # Revoking install permission may kill the debug app: do it after instrumentation.
    & $adb -s $Serial shell appops set com.poyal.yeobaek.debug REQUEST_INSTALL_PACKAGES default
}
