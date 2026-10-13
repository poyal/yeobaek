$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (Test-Path -LiteralPath '.tools/env.ps1') { . .tools/env.ps1 }
$hasProperties = Test-Path -LiteralPath 'signing.properties'
$hasKey = Test-Path -LiteralPath '.tools/signing/yeobaek.jks'
if ($hasProperties -and $hasKey) {
    Write-Output 'Existing signing configuration preserved.'
    exit 0
}
if ($hasProperties -or $hasKey) { throw 'Signing configuration is incomplete. Restore the existing key and properties before continuing.' }
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { 'keytool.exe' }
New-Item -ItemType Directory -Force '.tools/signing' | Out-Null
$secretBytes = New-Object byte[] 32
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($secretBytes) } finally { $random.Dispose() }
$env:YEOBAEK_KEY_PASSWORD = [Convert]::ToBase64String($secretBytes)
try {
    & $keytool -genkeypair -keystore '.tools/signing/yeobaek.jks' -storetype JKS -storepass:env YEOBAEK_KEY_PASSWORD -keypass:env YEOBAEK_KEY_PASSWORD -alias yeobaek -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Yeobaek Local APK'
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
    $properties = "storeFile=.tools/signing/yeobaek.jks`nstorePassword=$env:YEOBAEK_KEY_PASSWORD`nkeyAlias=yeobaek`nkeyPassword=$env:YEOBAEK_KEY_PASSWORD`n"
    [System.IO.File]::WriteAllText((Join-Path $projectRoot 'signing.properties'), $properties, [System.Text.UTF8Encoding]::new($false))
    Write-Output 'Local signing key created. Keep .tools/signing/yeobaek.jks and signing.properties for future updates.'
} finally {
    Remove-Item Env:YEOBAEK_KEY_PASSWORD
    [Array]::Clear($secretBytes, 0, $secretBytes.Length)
}
