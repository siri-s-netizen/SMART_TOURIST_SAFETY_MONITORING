@echo off
REM ============================================================================
REM  One-click build + install for the Tourist Safety Monitoring System.
REM  Double-click this file, or run it from PowerShell:  .\build-and-install.bat
REM  It rebuilds the debug APK and installs it on the phone connected by USB.
REM ============================================================================
setlocal

set "PROJECT=D:\TouristSafetyMonitoring-src"
set "ADB=D:\AndroidTools\Sdk\platform-tools\adb.exe"
set "APK=%PROJECT%\app\build\outputs\apk\debug\app-debug.apk"

echo(
echo === [1/3] Building the app ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT%\build.ps1" ":app:assembleDebug"
if errorlevel 1 goto :failed

echo(
echo === [2/3] Installing on the phone ===
"%ADB%" install -r "%APK%"
if errorlevel 1 goto :failed

echo(
echo === [3/3] Starting the app ===
"%ADB%" shell am start -n com.touristsafety.app/.MainActivity

echo(
echo Done. The Tourist Safety app should be open on your phone.
exit /b 0

:failed
echo(
echo *** BUILD OR INSTALL FAILED. ***
echo Check for: BUILD SUCCESSFUL above. If the install failed, is the phone
echo connected by USB with "USB debugging" enabled, and did you tap Allow?
exit /b 1
