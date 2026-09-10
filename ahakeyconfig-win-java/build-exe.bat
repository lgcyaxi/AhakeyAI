@echo off
setlocal

rem Keep one maintained application-image build path. The PowerShell script
rem runs tests, builds the sibling BLE bridge, and requires both bridge files.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-exe.ps1"
set "BUILD_EXIT=%ERRORLEVEL%"

if not "%BUILD_EXIT%"=="0" pause
exit /b %BUILD_EXIT%
