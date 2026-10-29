param([switch]$Install)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$tools = Join-Path $root 'tools'
$jdk = Join-Path $tools 'jdk\jdk-17.0.20.1+1'
$bt = Join-Path $tools 'bt\android-15'
$androidJar = Join-Path $tools 'platform\android-35\android.jar'
$sh = Join-Path $tools 'shizuku'

$jars = @(
    (Join-Path $sh 'api-13.1.0-x\classes.jar'),
    (Join-Path $sh 'aidl-13.1.0\classes.jar'),
    (Join-Path $sh 'shared-13.1.0\classes.jar'),
    (Join-Path $sh 'provider-13.1.0\classes.jar'),
    (Join-Path $sh 'annotation-1.3.0.jar')
)

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$build = Join-Path $root 'app\build'
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $build 'classes') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $build 'dex') | Out-Null

$cp = ($jars + $androidJar) -join ';'

Write-Output 'aapt2 link'
& (Join-Path $bt 'aapt2.exe') link -o (Join-Path $build 'base.apk') -I $androidJar --manifest (Join-Path $root 'app\AndroidManifest.xml') --min-sdk-version 26 --target-sdk-version 34 --version-code 3 --version-name '1.2'
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Output 'javac'
$sources = Get-ChildItem -LiteralPath (Join-Path $root 'app\src\com\playtim\clearphone') -Filter '*.java' | ForEach-Object { $_.FullName }
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 --release 11 -classpath $cp -d (Join-Path $build 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Output 'jar classes'
& (Join-Path $jdk 'bin\jar.exe') cf (Join-Path $build 'app-classes.jar') -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Write-Output 'd8'
$d8inputs = @((Join-Path $build 'app-classes.jar')) + $jars
& (Join-Path $bt 'd8.bat') --release --min-api 26 --lib $androidJar --output (Join-Path $build 'dex') @d8inputs
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Output 'package'
Copy-Item (Join-Path $build 'base.apk') (Join-Path $build 'unsigned.apk') -Force
Push-Location (Join-Path $build 'dex')
& (Join-Path $jdk 'bin\jar.exe') uf (Join-Path $build 'unsigned.apk') classes.dex
Pop-Location

Write-Output 'zipalign'
& (Join-Path $bt 'zipalign.exe') -f 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = Join-Path $root 'clearphone.jks'
if (-not (Test-Path $ks)) {
    Write-Output 'keystore'
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -keystore $ks -alias clearphone -keyalg RSA -keysize 2048 -validity 10000 -storepass clearphone -keypass clearphone -dname 'CN=ClearPhone,O=PlayTim,C=RU'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

Write-Output 'sign'
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass pass:clearphone --key-pass pass:clearphone --out (Join-Path $build 'ClearPhone.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Write-Output "APK: $(Join-Path $build 'ClearPhone.apk')"
if ($Install) {
    & (Join-Path $root 'platform-tools\adb.exe') install -r (Join-Path $build 'ClearPhone.apk')
}
