param([string]$Task = 'build')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $localJava = Join-Path $projectRoot '.tooling/java21/jdk-21.0.12.1+1'
    if (Test-Path (Join-Path $localJava 'bin/java.exe')) { $env:JAVA_HOME = $localJava }
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tooling/gradle'
    $localGradle = Join-Path $projectRoot '.tooling/gradle-8.14.5/bin/gradle.bat'
    if (Test-Path $localGradle) { & $localGradle $Task --console=plain }
    else { & ./gradlew.bat $Task --console=plain }
    exit $LASTEXITCODE
} finally { Pop-Location }
