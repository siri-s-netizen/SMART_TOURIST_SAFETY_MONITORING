# Builds the project with the toolchain that lives under D:\AndroidTools.
# Usage:  powershell -ExecutionPolicy Bypass -File .\build.ps1 [gradle args...]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs = @(":app:assembleDebug")
)

$ErrorActionPreference = "Stop"

# Always build the project this script lives in, regardless of the caller's directory.
Set-Location -LiteralPath $PSScriptRoot

$env:JAVA_HOME        = "D:\AndroidTools\jdk-21"
$env:ANDROID_HOME     = "D:\AndroidTools\Sdk"
$env:ANDROID_SDK_ROOT = "D:\AndroidTools\Sdk"
$env:GRADLE_USER_HOME = "D:\AndroidTools\gradle-home"

$gradle = "D:\AndroidTools\gradle-8.11.1\bin\gradle.bat"
if (-not (Test-Path $gradle)) { throw "Gradle not found at $gradle" }

& $gradle @GradleArgs
exit $LASTEXITCODE
