$ErrorActionPreference = "Continue"
$ROOT = $PSScriptRoot
$MOD  = "$ROOT\module"
$BT   = "$ROOT\sdk\build-tools\android-14"
$ANDJAR = "$ROOT\sdk\android.jar"
if(-not $env:JAVA_HOME -or -not (Test-Path "$($env:JAVA_HOME)\bin\javac.exe")){
  $javaHomes = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending
  if($javaHomes.Count -gt 0){ $env:JAVA_HOME = $javaHomes[0].FullName }
}
if($env:JAVA_HOME){ $env:PATH = "$($env:JAVA_HOME)\bin;$($env:PATH)" }
$JAVAC = "$($env:JAVA_HOME)\bin\javac.exe"
$JAR   = "$($env:JAVA_HOME)\bin\jar.exe"
$KEYTOOL = "$($env:JAVA_HOME)\bin\keytool.exe"
$OUT  = "$ROOT\build"

Remove-Item -Recurse -Force $OUT -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$OUT\stubs_out","$OUT\mod_out","$OUT\dex" | Out-Null

function Check($name){ if($LASTEXITCODE -ne 0){ Write-Output "FAILED at: $name (exit $LASTEXITCODE)"; exit 1 } }

Write-Output "==== 1) javac stubs ===="
$stubs = Get-ChildItem -Recurse "$MOD\stubs" -Filter *.java | ForEach-Object { $_.FullName }
& $JAVAC -Xlint:-options --release 8 -d "$OUT\stubs_out" $stubs 2>&1; Check "javac stubs"

Write-Output "==== 2) javac module ===="
$src = Get-ChildItem -Recurse "$MOD\src" -Filter *.java | ForEach-Object { $_.FullName }
& $JAVAC -Xlint:-options --release 8 -cp "$OUT\stubs_out;$ANDJAR" -d "$OUT\mod_out" $src 2>&1; Check "javac module"
Get-ChildItem -Recurse "$OUT\mod_out" -Filter *.class | ForEach-Object { Write-Output ("  class: " + $_.FullName.Substring($OUT.Length)) }

Write-Output "==== 3) jar module classes ===="
& $JAR cf "$OUT\mod.jar" -C "$OUT\mod_out" . 2>&1; Check "jar"

Write-Output "==== 4) d8 -> classes.dex (module only) ===="
& "$BT\d8.bat" --min-api 26 --lib "$ANDJAR" --classpath "$OUT\stubs_out" --output "$OUT\dex" "$OUT\mod.jar" 2>&1; Check "d8"
if(-not (Test-Path "$OUT\dex\classes.dex")){ Write-Output "no classes.dex produced"; exit 1 }
Write-Output ("  classes.dex size: " + (Get-Item "$OUT\dex\classes.dex").Length)

Write-Output "==== 5) aapt2 compile/link manifest + resources -> base.apk ===="
$AAPT_INPUTS = @()
if(Test-Path "$MOD\res"){
  & "$BT\aapt2.exe" compile --dir "$MOD\res" -o "$OUT\compiled_res.zip" 2>&1; Check "aapt2 compile"
  $AAPT_INPUTS += "$OUT\compiled_res.zip"
}
& "$BT\aapt2.exe" link -I "$ANDJAR" --manifest "$MOD\AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 34 -o "$OUT\base.apk" $AAPT_INPUTS 2>&1; Check "aapt2 link"

Write-Output "==== 6) assemble (add dex + assets) ===="
python "$ROOT\assemble_apk.py" "$OUT\base.apk" "$OUT\dex\classes.dex" "$MOD\assets\xposed_init" "$MOD\assets\xposed_scope" "$OUT\unsigned.apk" 2>&1; Check "assemble"

Write-Output "==== 7) zipalign ===="
& "$BT\zipalign.exe" -p -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk" 2>&1; Check "zipalign"

Write-Output "==== 8) keystore ===="
$KS = "$ROOT\debug.keystore"
if(-not (Test-Path $KS)){
  & $KEYTOOL -genkeypair -keystore $KS -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>&1; Check "keytool"
}

Write-Output "==== 9) apksigner sign ===="
& "$BT\apksigner.bat" sign --ks $KS --ks-pass pass:android --key-pass pass:android --min-sdk-version 26 --out "$OUT\AirpodsOffFix.apk" "$OUT\aligned.apk" 2>&1; Check "apksigner sign"

Write-Output "==== 10) verify ===="
& "$BT\apksigner.bat" verify --verbose "$OUT\AirpodsOffFix.apk" 2>&1; Check "apksigner verify"

Write-Output "==== DONE ===="
Write-Output ("APK: $OUT\AirpodsOffFix.apk  size=" + (Get-Item "$OUT\AirpodsOffFix.apk").Length)
