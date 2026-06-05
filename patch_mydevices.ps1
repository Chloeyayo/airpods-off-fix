param(
    [Parameter(Mandatory = $true)]
    [string]$InputApk,

    [string]$OutputDir = "build-root",
    [string]$ApktoolJar = ".scratch\tools\apktool_3.0.2.jar",
    [string]$BuildToolsDir = "sdk\build-tools\android-14",
    [string]$Keystore = "debug.keystore",
    [string]$KeystorePass = "android",
    [string]$KeyPass = "android",
    [switch]$KeepWorkDir
)

$ErrorActionPreference = "Stop"

function Resolve-RequiredPath {
    param([string]$Path, [string]$Name)
    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue
    if (-not $resolved) {
        throw "$Name not found: $Path"
    }
    return $resolved.Path
}

function Ensure-Apktool {
    param([string]$JarPath)
    if (Test-Path -LiteralPath $JarPath) {
        return (Resolve-Path -LiteralPath $JarPath).Path
    }

    $toolDir = Split-Path -Parent $JarPath
    if (-not $toolDir) { $toolDir = "." }
    New-Item -ItemType Directory -Force $toolDir | Out-Null

    Write-Host "apktool not found, downloading latest release..."
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/iBotPeaches/Apktool/releases/latest"
    $asset = $release.assets | Where-Object { $_.name -match '^apktool_.*\.jar$' } | Select-Object -First 1
    if (-not $asset) {
        throw "Could not find apktool jar asset in latest Apktool release."
    }
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $JarPath
    return (Resolve-Path -LiteralPath $JarPath).Path
}

function Run-Checked {
    param([string]$FilePath, [string[]]$Arguments)
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
    }
}

function Remove-TreeSafe {
    param([string]$Path, [string]$AllowedParent)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $resolvedParent = (Resolve-Path -LiteralPath $AllowedParent).Path
    $parentWithSep = $resolvedParent.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) +
        [System.IO.Path]::DirectorySeparatorChar

    if (-not $resolvedPath.StartsWith($parentWithSep, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside output directory: $resolvedPath"
    }

    Remove-Item -LiteralPath $resolvedPath -Recurse -Force
}

function Patch-OffSupport {
    param([string]$WorkDir)

    $matches = Get-ChildItem -LiteralPath $WorkDir -Directory -Filter "smali*" |
        ForEach-Object {
            Get-ChildItem -LiteralPath $_.FullName -Recurse -File -Filter "AirpodsSettingActivity.smali" |
                Where-Object { $_.FullName -like "*com\oplus\mydevices\bluetooth\airpods\AirpodsSettingActivity.smali" }
        }

    if (-not $matches -or $matches.Count -eq 0) {
        throw "AirpodsSettingActivity.smali not found. This patch targets MyDevices 17.1.5."
    }

    $file = $matches[0].FullName
    $text = Get-Content -LiteralPath $file -Raw

    if ($text -match "or-int/lit16\s+p1,\s+p1,\s+0x200") {
        Write-Host "Off support patch already present."
        return $file
    }

    $needle = ".method public final m2(I)V`r`n    .locals 6`r`n"
    if (-not $text.Contains($needle)) {
        $needle = ".method public final m2(I)V`n    .locals 6`n"
    }
    if (-not $text.Contains($needle)) {
        throw "Target method m2(I)V with .locals 6 not found. This patch targets MyDevices 17.1.5."
    }

    $replacement = $needle + "`r`n    or-int/lit16 p1, p1, 0x200`r`n"
    if ($needle.Contains("`n") -and -not $needle.Contains("`r`n")) {
        $replacement = $needle + "`n    or-int/lit16 p1, p1, 0x200`n"
    }

    $text = $text.Replace($needle, $replacement)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($file, $text, $utf8NoBom)
    return $file
}

$inputPath = Resolve-RequiredPath $InputApk "Input APK"
$apktoolPath = Ensure-Apktool $ApktoolJar
$buildToolsPath = Resolve-RequiredPath $BuildToolsDir "Android build-tools directory"
$zipalign = Resolve-RequiredPath (Join-Path $buildToolsPath "zipalign.exe") "zipalign.exe"
$apksigner = Resolve-RequiredPath (Join-Path $buildToolsPath "apksigner.bat") "apksigner.bat"
$aapt2 = Resolve-RequiredPath (Join-Path $buildToolsPath "aapt2.exe") "aapt2.exe"
$keystorePath = Resolve-RequiredPath $Keystore "Signing keystore"

New-Item -ItemType Directory -Force $OutputDir | Out-Null
$workDir = Join-Path $OutputDir "mydevices-patch-work"
$unsignedApk = Join-Path $OutputDir "MyDevices-17.1.5-AirPodsOffFix-unsigned.apk"
$alignedApk = Join-Path $OutputDir "MyDevices-17.1.5-AirPodsOffFix-aligned.apk"
$patchedApk = Join-Path $OutputDir "MyDevices-17.1.5-AirPodsOffFix-patched.apk"

if (Test-Path -LiteralPath $workDir) {
    Remove-TreeSafe $workDir $OutputDir
}

Write-Host "Decompiling: $inputPath"
Run-Checked "java" @("-jar", $apktoolPath, "d", "-f", $inputPath, "-o", $workDir, "--no-debug-info")

$patchedFile = Patch-OffSupport $workDir
Write-Host "Patched: $patchedFile"

Write-Host "Building unsigned APK..."
Run-Checked "java" @("-jar", $apktoolPath, "b", $workDir, "-o", $unsignedApk)

Write-Host "Zipalign..."
Run-Checked $zipalign @("-p", "-f", "4", $unsignedApk, $alignedApk)

Write-Host "Signing..."
Run-Checked $apksigner @(
    "sign",
    "--ks", $keystorePath,
    "--ks-pass", "pass:$KeystorePass",
    "--key-pass", "pass:$KeyPass",
    "--out", $patchedApk,
    $alignedApk
)

Write-Host "Verifying signature..."
Run-Checked $apksigner @("verify", "--verbose", $patchedApk)

Write-Host "Package info:"
Run-Checked $aapt2 @("dump", "badging", $patchedApk)

if (-not $KeepWorkDir) {
    Remove-TreeSafe $workDir $OutputDir
}

Write-Host ""
Write-Host "Done: $patchedApk"
Write-Host "Install note: this APK is re-signed. On rooted phones, replace/overlay the system MyDevices APK; normal adb install -r over the vendor-signed package will fail."
