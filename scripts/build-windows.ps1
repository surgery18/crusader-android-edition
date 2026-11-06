# Build Crusader: Android Edition on Windows through WSL (Ubuntu).
#
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1            # build
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1 -SetupSdk  # first time: install toolchain
#   powershell -ExecutionPolicy Bypass -File scripts\build-windows.ps1 -Install   # build + adb install
#
# The ScummVM checkout and object files live inside WSL (~/cae-build) because
# compiling on /mnt/c is many times slower. The finished APK lands in dist\.
param(
	[switch]$SetupSdk,
	[switch]$Install,
	[string]$Distro = "Ubuntu"
)
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$drive = $repo.Substring(0, 1).ToLower()
$wslRepo = "/mnt/$drive" + ($repo.Substring(2) -replace '\\', '/')

if ($SetupSdk) {
	Write-Host "Installing build packages in WSL (needs root once)..."
	wsl -d $Distro -u root -- bash -c "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y -qq build-essential pkg-config git curl unzip zip openjdk-17-jdk-headless python3"
	if ($LASTEXITCODE -ne 0) { throw "apt-get failed" }
}

$buildArgs = @("--work", "`$HOME/cae-build")
if ($SetupSdk) { $buildArgs += "--setup-sdk" }

wsl -d $Distro -- bash -lc "cd '$wslRepo' && bash scripts/build.sh $($buildArgs -join ' ')"
if ($LASTEXITCODE -ne 0) { throw "build failed" }

$apk = Join-Path $repo "dist\CrusaderAndroidEdition.apk"
Write-Host "APK: $apk"

if ($Install) {
	$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
	if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }
	if (-not (Test-Path $adb)) { throw "adb not found: install Android platform-tools or put adb on PATH" }
	& $adb install -r $apk
}
