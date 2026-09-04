<#
AhaKey Studio Build Script - Package JavaFX app to Windows EXE Installer
Requires: JDK 17+, Maven 3.6+, NSIS (for --type exe)

Usage: .\build-installer.ps1
#>

$ErrorActionPreference = "Stop"

$ProjectName = "AhaKeyStudio"
$Version = "1.0.2"
$MainClass = "com.example.ahakey.App"
$TargetDir = Join-Path $PSScriptRoot "target"
$MavenRepo = Join-Path $TargetDir ".m2repo"
$InstallerDir = "$TargetDir\installer"
$TempDir = "$TargetDir\jpackage-input"
$RuntimeDir = "$TargetDir\runtime"
$ResourceDir = "$TargetDir\jpackage-resources"
$IconPath = Join-Path $PSScriptRoot "VibeCodeKeyboard.ico"
$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BleProjectRoot = Join-Path $RepositoryRoot "BLE_tcp_bridge"
$BleProject = Join-Path $BleProjectRoot "BLE_tcp_driver.csproj"
$BleOutputDir = Join-Path $BleProjectRoot "bin\Release"

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

Write-Status "AhaKey Studio Installer Build v$Version" Cyan
Write-Status "====================================" Cyan

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

# Check if local model is enabled
$modelEnabled = $false
$propsFile = Join-Path $PSScriptRoot "src/main/resources/model_config.properties"
if (Test-Path $propsFile) {
    $match = Select-String -Path $propsFile -Pattern '^\s*model\.enabled\s*=\s*(.+)$'
    if ($match) {
        $modelEnabled = $match.Matches[0].Groups[1].Value.Trim() -eq 'true'
    }
}

if ($modelEnabled) {
    Write-Status "model.enabled=true: Including model files and ONNX runtime" Cyan
} else {
    Write-Status "model.enabled=false: EXCLUDING model files and ONNX runtime" Yellow
}

# Copy only required files
Copy-Item -Path $jarPath -Destination $TempDir
Copy-Item -Path "$TargetDir\lib\*.jar" -Destination "$TempDir\lib"

if ($modelEnabled) {
    Write-Status "Copying SenseVoice model files..." Cyan
    New-Item -ItemType Directory -Path "$TempDir\models" | Out-Null
    Copy-Item -Path (Join-Path $PSScriptRoot "src/main/resources/models/model_q8.onnx") -Destination "$TempDir\models" -Force
    Copy-Item -Path (Join-Path $PSScriptRoot "src/main/resources/models/tokens.txt") -Destination "$TempDir\models" -Force
    Write-Status "Model files copied successfully" Green
} else {
    Write-Status "Removing onnxruntime from lib..." Yellow
    Remove-Item -Path "$TempDir\lib\onnxruntime*.jar" -Force -ErrorAction SilentlyContinue
    Write-Status "Removing model files from JAR..." Yellow
    $jarName = Split-Path $jarPath -Leaf
    $zipPath = "$TempDir\$jarName"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Update')
    $entries = $zip.Entries | Where-Object { $_.FullName -like 'models/*' }
    foreach ($entry in $entries) { $entry.Delete() }
    $zip.Dispose()
    Write-Status "onnxruntime + model files removed from package (saved ~233MB)" Green
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

$jlinkArgs = @(
    "--module-path", "$TargetDir\lib",
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
