$ErrorActionPreference = "Continue"
$ROOT = $PSScriptRoot
$APP = "$ROOT\shizuku"
$BT = "$ROOT\sdk\build-tools\android-14"
$ANDJAR = "$ROOT\sdk\android.jar"
$SHIZUKU = "$ROOT\.scratch\shizuku"
if(-not $env:JAVA_HOME -or -not (Test-Path "$($env:JAVA_HOME)\bin\javac.exe")){
  $javaHomes = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending
  if($javaHomes.Count -gt 0){ $env:JAVA_HOME = $javaHomes[0].FullName }
}
if($env:JAVA_HOME){ $env:PATH = "$($env:JAVA_HOME)\bin;$($env:PATH)" }
$JAVAC = "$($env:JAVA_HOME)\bin\javac.exe"
$JAR = "$($env:JAVA_HOME)\bin\jar.exe"
$KEYTOOL = "$($env:JAVA_HOME)\bin\keytool.exe"
$OUT = "$ROOT\build-shizuku"
$SHIZUKU_CP = "$SHIZUKU\api\classes.jar;$SHIZUKU\provider\classes.jar;$SHIZUKU\aidl-aar\classes.jar"

Remove-Item -Recurse -Force $OUT -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$OUT\classes","$OUT\dex" | Out-Null

function Check($name){ if($LASTEXITCODE -ne 0){ Write-Output "FAILED at: $name (exit $LASTEXITCODE)"; exit 1 } }

Write-Output "==== 1) javac app ===="
$src = Get-ChildItem -Recurse "$APP\src" -Filter *.java | ForEach-Object { $_.FullName }
& $JAVAC -Xlint:-options --release 8 -cp "$ANDJAR;$SHIZUKU_CP" -d "$OUT\classes" $src 2>&1; Check "javac app"

Write-Output "==== 2) jar app classes ===="
& $JAR cf "$OUT\app.jar" -C "$OUT\classes" . 2>&1; Check "jar"

Write-Output "==== 3) d8 ===="
& "$BT\d8.bat" --min-api 29 --lib "$ANDJAR" --output "$OUT\dex" "$OUT\app.jar" "$SHIZUKU\api\classes.jar" "$SHIZUKU\provider\classes.jar" "$SHIZUKU\aidl-aar\classes.jar" 2>&1; Check "d8"

Write-Output "==== 3b) build BtHold asset ===="
$BTHOLD_OUT = "$OUT\bthold"
New-Item -ItemType Directory -Force "$BTHOLD_OUT" | Out-Null
& $JAVAC -Xlint:-options -source 8 -target 8 -bootclasspath "$ANDJAR" -d "$BTHOLD_OUT" "$APP\probe\BtHold.java" 2>&1; Check "javac BtHold"
$btClasses = Get-ChildItem -LiteralPath "$BTHOLD_OUT" -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --min-api 29 --output "$BTHOLD_OUT" $btClasses 2>&1; Check "d8 BtHold"

Write-Output "==== 4) aapt2 compile/link ===="
& "$BT\aapt2.exe" compile --dir "$APP\res" -o "$OUT\compiled_res.zip" 2>&1; Check "aapt2 compile"
& "$BT\aapt2.exe" link -I "$ANDJAR" --manifest "$APP\AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 34 -o "$OUT\base.apk" "$OUT\compiled_res.zip" 2>&1; Check "aapt2 link"

Write-Output "==== 5) assemble ===="
python "$ROOT\assemble_plain_apk.py" "$OUT\base.apk" "$OUT\dex\classes.dex" "$OUT\unsigned.apk" "$BTHOLD_OUT\classes.dex" "assets/bthold.dex" 2>&1; Check "assemble"

Write-Output "==== 6) zipalign/sign ===="
& "$BT\zipalign.exe" -p -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk" 2>&1; Check "zipalign"
$KS = "$ROOT\debug.keystore"
if(-not (Test-Path $KS)){
  & $KEYTOOL -genkeypair -keystore $KS -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>&1; Check "keytool"
}
& "$BT\apksigner.bat" sign --ks $KS --ks-pass pass:android --key-pass pass:android --min-sdk-version 29 --out "$OUT\AirpodsOffFix-Shizuku.apk" "$OUT\aligned.apk" 2>&1; Check "apksigner sign"
& "$BT\apksigner.bat" verify --verbose "$OUT\AirpodsOffFix-Shizuku.apk" 2>&1; Check "apksigner verify"

Write-Output "==== DONE ===="
Write-Output ("APK: $OUT\AirpodsOffFix-Shizuku.apk  size=" + (Get-Item "$OUT\AirpodsOffFix-Shizuku.apk").Length)
