<#
AhaKey Studio Build Script - Package JavaFX app to Windows EXE Installer
Requires: JDK 17+, Maven 3.6+, NSIS (for --type exe)

Usage:
  .\build-installer.ps1
  .\build-installer.ps1 -IncludeLocalModel
  .\build-installer.ps1 -IncludeLocalModel -LocalModelDirectory <extracted-model-dir>
#>

param(
    [switch]$IncludeLocalModel,
    [string]$LocalModelDirectory
)

$ErrorActionPreference = "Stop"

$ProjectName = "AhaKeyStudio"
$Version = "1.1.1"
$MainClass = "com.example.ahakey.App"
$TargetDir = Join-Path $PSScriptRoot "target"
$MavenRepo = Join-Path $TargetDir ".m2repo"
$DependencyDir = Join-Path $TargetDir "lib"
$InstallerDir = "$TargetDir\installer"
$TempDir = "$TargetDir\jpackage-input"
$RuntimeDir = "$TargetDir\runtime"
$ResourceDir = "$TargetDir\jpackage-resources"
$IconPath = Join-Path $PSScriptRoot "VibeCodeKeyboard.ico"
$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BleProjectRoot = Join-Path $RepositoryRoot "BLE_tcp_bridge"
$BleProject = Join-Path $BleProjectRoot "BLE_tcp_driver.csproj"
$BleOutputDir = Join-Path $BleProjectRoot "bin\Release"
$DefaultLocalModelDirectory = Join-Path $TargetDir "model-cache\sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
$ExpectedModelSha256 = "C71F0CE00BEC95B07744E116345E33D8CBBE08CEF896382CF907BF4B51A2CD51"
$ExpectedTokensSha256 = "F449EB28DC567533D7FA59BE34E2ABCA8784F771850C78A47FB731A31429A1DC"
$modelEnabled = $IncludeLocalModel -or $PSBoundParameters.ContainsKey("LocalModelDirectory")
if ([string]::IsNullOrWhiteSpace($LocalModelDirectory)) {
    $LocalModelDirectory = $DefaultLocalModelDirectory
}

function Write-Status($Message, $Color) {
    Write-Host "[$(Get-Date -Format HH:mm:ss)] " -NoNewline
    Write-Host $Message -ForegroundColor $Color
}

function Find-MSBuild {
    $command = Get-Command MSBuild.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path -LiteralPath $vswhere) {
        $candidate = & $vswhere -latest -products * -requires Microsoft.Component.MSBuild `
            -find "MSBuild\**\Bin\MSBuild.exe" | Select-Object -First 1
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }
    throw "MSBuild.exe was not found. Install Visual Studio Build Tools with the .NET desktop workload."
}

function Get-RunningProcessesUnderPath {
    param(
        [Parameter(Mandatory = $true)][string]$ProcessName,
        [Parameter(Mandatory = $true)][string]$RootPath
    )

    $normalizedRoot = [System.IO.Path]::GetFullPath($RootPath).TrimEnd('\') + '\'
    @(
        Get-CimInstance Win32_Process -Filter "Name = '$ProcessName'" -ErrorAction Stop |
            Where-Object {
                $_.ExecutablePath -and
                [System.IO.Path]::GetFullPath($_.ExecutablePath).StartsWith(
                    $normalizedRoot,
                    [System.StringComparison]::OrdinalIgnoreCase
                )
            }
    )
}

function Resolve-LocalModelAssets {
    param([Parameter(Mandatory = $true)][string]$Directory)

    $resolvedDirectory = (Resolve-Path -LiteralPath $Directory -ErrorAction Stop).Path
    $model = Join-Path $resolvedDirectory "model.int8.onnx"
    $tokens = Join-Path $resolvedDirectory "tokens.txt"
    $notice = Join-Path $PSScriptRoot "THIRD_PARTY_NOTICES.md"
    $apacheLicense = Join-Path $RepositoryRoot "LICENSE"
    foreach ($required in $model, $tokens, $notice, $apacheLicense) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "Required local-model asset not found: $required"
        }
    }

    $modelHash = (Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash
    $tokensHash = (Get-FileHash -LiteralPath $tokens -Algorithm SHA256).Hash
    if ($modelHash -ne $ExpectedModelSha256) {
        throw "Unexpected SenseVoice model SHA-256: $modelHash"
    }
    if ($tokensHash -ne $ExpectedTokensSha256) {
        throw "Unexpected SenseVoice tokens SHA-256: $tokensHash"
    }

    [pscustomobject]@{
        Model = $model
        Tokens = $tokens
        Notice = $notice
        ApacheLicense = $apacheLicense
    }
}

Write-Status "AhaKey Studio Installer Build v$Version" Cyan
Write-Status "====================================" Cyan

$localModelAssets = $null
if ($modelEnabled) {
    $localModelAssets = Resolve-LocalModelAssets -Directory $LocalModelDirectory
    Write-Status "Full local-model installer enabled: $LocalModelDirectory" Cyan
} else {
    Write-Status "Engine included; model weights are optional and available from Settings" Yellow
}

if (-not (Test-Path -LiteralPath $BleProject)) {
    throw "Required sibling BLE bridge project not found: $BleProject"
}

$msbuild = Find-MSBuild
Write-Status "Building required BLE configuration bridge..." Cyan
& $msbuild $BleProject /restore /t:Build /p:Configuration=Release /p:Platform=AnyCPU /nologo
if ($LASTEXITCODE -ne 0) {
    throw "BLE bridge build failed"
}
$bleExeSource = Join-Path $BleOutputDir "BLE_tcp_driver.exe"
$bleConfigSource = Join-Path $BleOutputDir "BLE_tcp_driver.exe.config"
foreach ($requiredBleFile in $bleExeSource, $bleConfigSource) {
    if (-not (Test-Path -LiteralPath $requiredBleFile)) {
        throw "Required BLE bridge output not found: $requiredBleFile"
    }
}

# Ensure WiX tools are on PATH (jpackage --type exe requires candle.exe/light.exe)
$wixPaths = @(
    "C:\Program Files (x86)\WiX Toolset v3.14\bin",
    "C:\Program Files\WiX Toolset v3.14\bin",
    "C:\Program Files (x86)\WiX Toolset v3.11\bin"
)
foreach ($p in $wixPaths) {
    if ((Test-Path $p) -and $env:PATH -notlike "*$p*") {
        $env:PATH = "$p;$env:PATH"
    }
}

# Build project (must run from script directory so Maven finds pom.xml)
Set-Location $PSScriptRoot
Write-Status "Building project..." Cyan
if (Test-Path -LiteralPath $DependencyDir) {
    Remove-Item -LiteralPath $DependencyDir -Recurse -Force
}
& mvn "-Dmaven.repo.local=$MavenRepo" package

if ($LASTEXITCODE -ne 0) {
    Write-Status "ERROR: Maven build failed" Red
    exit 1
}
Write-Status "Maven build successful" Green

# Clean old installer
if (Test-Path $InstallerDir) {
    Write-Status "Removing old installer..." Yellow
    $runningProcesses = @(
        Get-RunningProcessesUnderPath `
            -ProcessName "$ProjectName.exe" `
            -RootPath $InstallerDir
    )
    if ($runningProcesses.Count -gt 0) {
        $runningPids = ($runningProcesses | ForEach-Object ProcessId) -join ", "
        Write-Status "ERROR: AhaKey Studio is running from the build output (PID $runningPids). Exit that test instance before rebuilding." Red
        exit 1
    }
    $null = New-Item -ItemType Directory -Path "$TargetDir\empty_dir" -Force -ErrorAction SilentlyContinue
    robocopy "$TargetDir\empty_dir" $InstallerDir /MIR /NFL /NDL /NJH /NJS | Out-Null
    Remove-Item -Path "$TargetDir\empty_dir" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $InstallerDir -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path $InstallerDir) {
        Write-Status "ERROR: Could not remove the previous installer output: $InstallerDir" Red
        exit 1
    }
}

# Create clean temporary input directory
Write-Status "Preparing clean input directory..." Cyan
if (Test-Path $TempDir) {
    Remove-Item -Path $TempDir -Recurse -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Path "$TempDir\lib" | Out-Null
if (Test-Path $ResourceDir) {
    Remove-Item -Path $ResourceDir -Recurse -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Path $ResourceDir | Out-Null
Copy-Item -Path $IconPath -Destination "$ResourceDir\$ProjectName.ico" -Force

$jarPath = "$TargetDir\ahakey-studio-$Version.jar"

# Copy only required files
Copy-Item -Path $jarPath -Destination $TempDir
Copy-Item -Path "$TargetDir\lib\*.jar" -Destination "$TempDir\lib"

New-Item -ItemType Directory -Force -Path (Join-Path $TempDir "licenses") | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "THIRD_PARTY_NOTICES.md") -Destination $TempDir -Force
Copy-Item -LiteralPath (Join-Path $RepositoryRoot "LICENSE") -Destination (Join-Path $TempDir "licenses\sherpa-onnx-Apache-2.0.txt") -Force
if ($modelEnabled) {
    Write-Status "Copying validated SenseVoice and license files..." Cyan
    New-Item -ItemType Directory -Path "$TempDir\models" | Out-Null
    New-Item -ItemType Directory -Force -Path "$TempDir\licenses" | Out-Null
    Copy-Item -LiteralPath $localModelAssets.Model -Destination "$TempDir\models\model.int8.onnx" -Force
    Copy-Item -LiteralPath $localModelAssets.Tokens -Destination "$TempDir\models\tokens.txt" -Force
    Copy-Item -LiteralPath $localModelAssets.Notice -Destination "$TempDir\THIRD_PARTY_NOTICES.md" -Force
    Copy-Item -LiteralPath $localModelAssets.ApacheLicense -Destination "$TempDir\licenses\sherpa-onnx-Apache-2.0.txt" -Force
    @(
        "model.enabled=true"
        "model.path=models/model.int8.onnx"
        "tokens.path=models/tokens.txt"
        "model.type=SenseVoice INT8 2024-07-17"
        "num_threads=1"
        "sample_rate=16000"
        "language=zh"
        "text_norm=true"
    ) | Set-Content -LiteralPath "$TempDir\model_config.properties" -Encoding ascii
    Write-Status "Validated local model and runtime configuration copied" Green
} else {
    Write-Status "Bundling speech engine without model weights..." Yellow

    Write-Status "Speech engine included; model weights not bundled" Green
}

Write-Status "Input directory ready" Green

# Copy the exact sibling bridge built above. Per-machine config_server.json is
# deliberately not copied because it contains the saved BLE device identity.
Write-Status "Copying BLE configuration bridge to input dir..." Cyan
Copy-Item -LiteralPath $bleExeSource -Destination "$TempDir\BLE_tcp_driver.exe" -Force
Copy-Item -LiteralPath $bleConfigSource -Destination "$TempDir\BLE_tcp_driver.exe.config" -Force
foreach ($requiredBundledFile in "BLE_tcp_driver.exe", "BLE_tcp_driver.exe.config") {
    if (-not (Test-Path -LiteralPath (Join-Path $TempDir $requiredBundledFile))) {
        throw "Required BLE bridge file was not bundled: $requiredBundledFile"
    }
}
Write-Status "BLE configuration bridge copied" Green

# Create custom runtime using jlink
Write-Status "Creating custom runtime using jlink..." Cyan
if (Test-Path $RuntimeDir) {
    Remove-Item -Path $RuntimeDir -Recurse -Force -ErrorAction SilentlyContinue
}

# Detect JDK version for --compress flag (JDK 21+ uses zip-6, JDK 17 uses 2)
$javaVersion = (& java -version 2>&1 | Select-String 'version "(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value })
$compressArg = if ([int]$javaVersion -ge 21) { "zip-6" } else { "2" }
Write-Status "JDK $javaVersion detected, using --compress=$compressArg" Cyan

# jlink only needs the modular JavaFX JARs. Pointing it at every runtime JAR
# makes it try to derive module names for classpath-only dependencies; the
# pinned sherpa filename contains v1.13.7 and is intentionally not a module.
$javaFxModuleJars = @(
    Get-ChildItem -LiteralPath "$TempDir/lib" -Filter "javafx-*-win.jar" -File
)
if ($javaFxModuleJars.Count -ne 4) {
    $found = ($javaFxModuleJars | ForEach-Object Name) -join ", "
    throw "Expected four Windows JavaFX module JARs for jlink; found: $found"
}
$javaFxModulePath = ($javaFxModuleJars.FullName -join [IO.Path]::PathSeparator)

$jlinkArgs = @(
    "--module-path", $javaFxModulePath,
    "--add-modules", "javafx.controls,javafx.fxml,javafx.graphics,java.base,java.logging,java.desktop,java.net.http,java.sql,java.naming,java.xml",
    "--output", $RuntimeDir,
    "--strip-debug",
    "--no-header-files",
    "--no-man-pages",
    "--compress", $compressArg
)

& jlink @jlinkArgs

if ($LASTEXITCODE -ne 0) {
    Write-Status "ERROR: jlink failed" Red
    exit 1
}
Write-Status "Custom runtime created successfully" Green

# Create EXE installer using jpackage
Write-Status "Creating EXE installer (requires NSIS)..." Cyan

$jpackageArgs = @(
    "--type", "exe",
    "--name", $ProjectName,
    "--app-version", $Version,
    "--vendor", "AhaKey",
    "--description", "AhaKey Studio - Keyboard Configuration Tool",
    "--copyright", "2024 AhaKey",
    "--icon", $IconPath,
    "--resource-dir", $ResourceDir,
    "--input", $TempDir,
    "--main-jar", (Split-Path $jarPath -Leaf),
    "--main-class", $MainClass,
    "--dest", $InstallerDir,
    "--runtime-image", $RuntimeDir,
    "--win-dir-chooser",
    "--win-shortcut",
    "--win-menu",
    "--win-menu-group", "AhaKey",
    "--java-options", "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED",
    "--java-options", "--add-opens=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED",
    "--java-options", "--add-opens=javafx.fxml/com.sun.javafx.fxml=ALL-UNNAMED",
    "--verbose"
)

& jpackage @jpackageArgs

if ($LASTEXITCODE -ne 0) {
    Write-Status "ERROR: jpackage failed. Make sure NSIS is installed (https://nsis.sourceforge.io)" Red
    exit 1
}

# Cleanup temporary directories
Remove-Item -Path $TempDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -Path $RuntimeDir -Recurse -Force -ErrorAction SilentlyContinue

# Rename output to timestamp-based filename
$timestamp = Get-Date -Format "yyyyMMddHHmmss"
$originalExe = "$InstallerDir\$ProjectName-$Version.exe"
$renamedExe  = "$InstallerDir\$ProjectName-$timestamp.exe"
if (Test-Path $originalExe) {
    Rename-Item -Path $originalExe -NewName "$ProjectName-$timestamp.exe"
}

Write-Status "====================================" Cyan
Write-Status "Installer build completed!" Green
Write-Status "Output: $renamedExe" Cyan
