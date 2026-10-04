param([switch]$Sable)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$startedAt = [DateTimeOffset]::UtcNow
$runName = $startedAt.ToString('yyyyMMddTHHmmssfffZ') + '-' + $PID
$runDirectory = Join-Path $projectRoot ('.tooling/test-runs/' + $runName)
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$runDirectory = (Resolve-Path -LiteralPath $runDirectory).Path
$ledgerPath = Join-Path $runDirectory 'status.json'
$runExitCode = 1

$ledger = [ordered]@{
    startedAtUtc = $startedAt.ToString('o')
    finishedAtUtc = $null
    workspace = $projectRoot
    versions = [ordered]@{}
    javaHome = $null
    gradle = $null
    stages = @()
    junit = $null
    sable = [ordered]@{
        requested = [bool]$Sable
        status = $(if ($Sable) { 'PENDING' } else { 'NOT_REQUESTED' })
        scope = 'Opt-in native Sable server probe; does not verify a production adapter, passengers or clients.'
        exitCode = $null
        requiredTestsPassed = $null
        log = $null
    }
    status = 'RUNNING'
    exitCode = $null
    failure = $null
}

function Save-Ledger {
    $ledger | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ledgerPath -Encoding UTF8
}

function Invoke-LoggedStage {
    param([string]$Name, [string]$Executable, [string[]]$Arguments)
    $logPath = Join-Path $runDirectory ($Name + '.log')
    $stage = [ordered]@{
        name = $Name
        executable = $Executable
        arguments = $Arguments
        startedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        finishedAtUtc = $null
        exitCode = $null
        status = 'RUNNING'
        log = $logPath
    }
    $ledger.stages += $stage
    Save-Ledger
    Write-Host "Running $Name. Log: $logPath"
    # Native stderr is retained in the log. The process exit code is authoritative.
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $nativePreferenceExists = $null -ne (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue)
    if ($nativePreferenceExists) {
        $previousNativePreference = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
    }
    try {
        # Native processes update the global automatic variable. A local assignment
        # would shadow that update inside this function and falsely report success.
        $global:LASTEXITCODE = 0
        & $Executable @Arguments 2>&1 | Tee-Object -FilePath $logPath -ErrorAction Stop | Out-Host
        $childExitCode = $global:LASTEXITCODE
        $stage.exitCode = $childExitCode
        $stage.status = $(if ($childExitCode -eq 0) { 'PASSED' } else { 'FAILED' })
    } catch {
        $stage.exitCode = 1
        $stage.status = 'FAILED_TO_START'
        $_ | Out-String | Add-Content -LiteralPath $logPath
        throw
    } finally {
        $ErrorActionPreference = $previousErrorPreference
        if ($nativePreferenceExists) { $PSNativeCommandUseErrorActionPreference = $previousNativePreference }
        $stage.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        Save-Ledger
    }
    return [int]$stage.exitCode
}

function Read-FreshJUnitSummary {
    param([DateTimeOffset]$Since)
    $reportDirectory = Join-Path $projectRoot 'build/test-results/test'
    if (!(Test-Path -LiteralPath $reportDirectory -PathType Container)) { throw 'JUnit report directory was not created.' }
    $reports = @(Get-ChildItem -LiteralPath $reportDirectory -Filter 'TEST-*.xml' -File)
    if ($reports.Count -eq 0) { throw 'JUnit did not produce any XML reports.' }
    $summary = [ordered]@{ suites = 0; tests = 0; failures = 0; errors = 0; skipped = 0; reportDirectory = $reportDirectory }
    foreach ($report in $reports) {
        if ($report.LastWriteTimeUtc -lt $Since.UtcDateTime) { throw "JUnit report is stale: $($report.FullName)" }
        [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
        if ($null -eq $document.testsuite) { throw "Invalid JUnit report: $($report.FullName)" }
        $summary.suites++
        foreach ($field in @('tests', 'failures', 'errors', 'skipped')) {
            $summary[$field] += [int]$document.testsuite.GetAttribute($field)
        }
    }
    if ($summary.tests -eq 0) { throw 'JUnit reported zero tests.' }
    return $summary
}

Push-Location $projectRoot
try {
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties')) {
        if ($line -match '^\s*([^#!\s][^=]*)=(.*)$') { $properties[$matches[1].Trim()] = $matches[2].Trim() }
    }
    foreach ($key in @('minecraft_version', 'neo_version', 'mod_version')) { $ledger.versions[$key] = $properties[$key] }

    $localJava = Join-Path $projectRoot '.tooling/java21/jdk-21.0.12.1+1'
    if (Test-Path -LiteralPath (Join-Path $localJava 'bin/java.exe') -PathType Leaf) {
        $env:JAVA_HOME = (Resolve-Path -LiteralPath $localJava).Path
    }
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tooling/gradle'
    $ledger.javaHome = $env:JAVA_HOME
    $localGradle = Join-Path $projectRoot '.tooling/gradle-8.14.5/bin/gradle.bat'
    $gradle = $(if (Test-Path -LiteralPath $localGradle -PathType Leaf) { $localGradle } else { Join-Path $projectRoot 'gradlew.bat' })
    $gradle = (Resolve-Path -LiteralPath $gradle).Path
    $ledger.gradle = $gradle
    $powerShell = (Get-Process -Id $PID).Path
    if (!$powerShell) { $powerShell = (Get-Command powershell.exe -ErrorAction Stop).Source }
    $powerShell = (Resolve-Path -LiteralPath $powerShell).Path
    $verifyScript = (Resolve-Path -LiteralPath (Join-Path $projectRoot 'tools/verify-resources.ps1')).Path
    Save-Ledger

    $runExitCode = Invoke-LoggedStage 'resources' $powerShell @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $verifyScript, '-ProjectRoot', $projectRoot)
    if ($runExitCode -ne 0) { throw "Resource verification failed with exit code $runExitCode." }

    $junitStartedAt = [DateTimeOffset]::UtcNow
    $runExitCode = Invoke-LoggedStage 'junit' $gradle @('test', '--rerun-tasks', '--offline', '--console=plain')
    # Keep fresh assertion-failure reports, but a compile failure can leave old
    # reports. Report collection must not replace the native process failure.
    try {
        $ledger.junit = Read-FreshJUnitSummary $junitStartedAt
    } catch {
        if ($runExitCode -eq 0) { throw }
    }
    Save-Ledger
    if ($runExitCode -ne 0) { throw "JUnit failed with exit code $runExitCode." }
    if ($ledger.junit.failures -gt 0 -or $ledger.junit.errors -gt 0) {
        $runExitCode = 1
        throw 'JUnit XML contains failures or errors despite the Gradle exit code.'
    }

    $runExitCode = Invoke-LoggedStage 'core-build-gametest' $gradle @('build', 'runGameTestServer', '--offline', '--console=plain')
    if ($runExitCode -ne 0) { throw "Core build/GameTest failed with exit code $runExitCode." }
    $coreLog = Get-Content -LiteralPath (Join-Path $runDirectory 'core-build-gametest.log') -Raw
    if ($coreLog -notmatch 'All\s+([1-9][0-9]*)\s+required tests passed') {
        $runExitCode = 1
        throw 'Core GameTest log does not confirm any required test passed.'
    }

    if ($Sable) {
        $ledger.sable.status = 'RUNNING'
        $ledger.sable.log = Join-Path $runDirectory 'sable-gametest.log'
        Save-Ledger
        $runExitCode = Invoke-LoggedStage 'sable-gametest' $gradle @('-PsableTests=true', 'runSableGameTestServer', '--offline', '--console=plain')
        $ledger.sable.exitCode = $runExitCode
        $sableLog = Get-Content -LiteralPath $ledger.sable.log -Raw
        if ($runExitCode -ne 0) {
            $ledger.sable.status = 'FAILED'
            throw "Native Sable probe failed with exit code $runExitCode."
        }
        if ($sableLog -notmatch 'All\s+([1-9][0-9]*)\s+required tests passed') {
            $runExitCode = 1
            $ledger.sable.status = 'UNCONFIRMED'
            throw 'Native Sable probe exited zero without confirmation that required tests passed.'
        }
        $ledger.sable.requiredTestsPassed = [int]$matches[1]
        $ledger.sable.status = 'PASSED_NATIVE_PROBE'
        Save-Ledger
    }
    $runExitCode = 0
    $ledger.status = 'PASSED'
} catch {
    if ($runExitCode -eq 0) { $runExitCode = 1 }
    $ledger.status = 'FAILED'
    $ledger.failure = $_.Exception.Message
    if ($Sable -and $ledger.sable.status -eq 'RUNNING') { $ledger.sable.status = 'FAILED_TO_RUN' }
    if ($Sable -and $ledger.sable.status -eq 'PENDING') { $ledger.sable.status = 'NOT_RUN_CORE_FAILED' }
    Write-Host "Test run failed: $($ledger.failure)" -ForegroundColor Red
} finally {
    $ledger.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $ledger.exitCode = $runExitCode
    Save-Ledger
    Pop-Location
    if ($null -ne $ledger.junit) {
        Write-Host "JUnit: $($ledger.junit.tests) tests, $($ledger.junit.failures) failures, $($ledger.junit.errors) errors, $($ledger.junit.skipped) skipped."
    }
    Write-Host "Sable: $($ledger.sable.status)"
    Write-Host "Result: $($ledger.status), exit code $runExitCode"
    Write-Host "Status ledger: $ledgerPath"
    foreach ($stage in $ledger.stages) { Write-Host "$($stage.name) log: $($stage.log)" }
}
exit $runExitCode
