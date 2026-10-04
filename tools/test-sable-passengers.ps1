$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$taskSource = Join-Path $taskRoot 'src/sableTest/java/dev/heiko/universe/sabletest/SableStandingPassengerProbe.java'
$taskRunId = 'passenger-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + $PID
$taskDirectory = Join-Path $taskRoot ('.tooling/passenger-native-runs/' + $taskRunId)
if (Test-Path -LiteralPath $taskDirectory) { throw 'Passenger evidence directory already exists; refusing to overwrite a previous run.' }
[IO.Directory]::CreateDirectory($taskDirectory) | Out-Null
$taskReservation = [IO.File]::Open((Join-Path $taskDirectory 'run-reserved'), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
$taskReservation.Dispose()
$taskLedgerPath = Join-Path $taskDirectory 'status.json'
$taskLogPath = Join-Path $taskDirectory 'native-gametest.log'
$taskExpectedBatches = @('sable_passenger_standing_roundtrip','sable_passenger_compensating_return','sable_passenger_travel_refusal')
$taskLedger = [ordered]@{
    runId=$taskRunId; startedAtUtc=[DateTime]::UtcNow.ToString('o'); finishedAtUtc=$null; status='RUNNING'
    scope='Stationary real vanilla Villager on an isolated Sable body: native roundtrip, compensating return and travel-hook refusal. No players, moving passenger, production recovery or docking group acceptance.'
    sourceSha256=(Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash.ToLowerInvariant()
    runnerSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    coreJarSha256=(Get-FileHash -LiteralPath (Join-Path $taskRoot 'build/libs/universe-0.1.0-alpha.5.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    expectedRequiredTests=13; batches=$taskExpectedBatches; minecraft='1.21.1'; neoforge='21.1.251'; sable='2.0.5'
    gradleExitCode=$null; nativeLogSha256=$null; failure=$null
}
function Save-PassengerLedger {
    $taskLedger | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $taskLedgerPath -Encoding utf8
}
Save-PassengerLedger
$taskDirectory | Set-Content -LiteralPath (Join-Path $taskRoot '.tooling/current-passenger-run.txt') -Encoding utf8
$taskExit=1
try {
    $env:JAVA_HOME=(Resolve-Path -LiteralPath (Join-Path $taskRoot '.tooling/java21/jdk-21.0.12.1+1')).Path
    $env:GRADLE_USER_HOME=Join-Path $taskRoot '.tooling/gradle'
    Push-Location -LiteralPath $taskRoot
    try { & (Join-Path $taskRoot '.tooling/gradle-8.14.5/bin/gradle.bat') -PsableTests=true runSableGameTestServer --offline --console=plain *> $taskLogPath; $taskExit=$LASTEXITCODE }
    finally { Pop-Location }
    $taskLedger.gradleExitCode=$taskExit
    $taskLog=Get-Content -LiteralPath $taskLogPath -Raw
    $taskCompletions=[regex]::Matches($taskLog,'(?m)^.*\[minecraft/GameTestServer\]: =+ ([0-9]+) GAME TESTS COMPLETE IN [0-9.]+ s =+\r?$')
    $taskSuccesses=[regex]::Matches($taskLog,'(?m)^.*\[minecraft/GameTestServer\]: All ([0-9]+) required tests passed :\)\r?$')
    if ($taskCompletions.Count -eq 1) { $taskLedger.discoveredExecutedTests=[int]$taskCompletions[0].Groups[1].Value }
    if ($taskLog -match '([0-9]+) required tests failed') { $taskLedger.requiredTestsFailed=[int]$matches[1] }
    if ($taskExit -ne 0) { throw ('Native GameTest failed; Gradle exit ' + $taskExit) }
    if ($taskCompletions.Count -ne 1 -or [int]$taskCompletions[0].Groups[1].Value -ne 13) { throw 'Missing unique confirmation that exactly 13 native tests completed.' }
    if ($taskSuccesses.Count -ne 1 -or [int]$taskSuccesses[0].Groups[1].Value -ne 13) { throw 'Missing unique confirmation of all 13 required native tests passing.' }
    if ($taskLog -match 'required tests failed|SABLE_PASSENGER_FAILED|\[[^\]\r\n]*/(?:ERROR|FATAL)\]|(?m)^BUILD FAILED|(?m)^FAILURE: Build failed') { throw 'Native log contains a failure or error alongside the success summary.' }
    foreach ($taskBatch in $taskExpectedBatches) {
        $taskBatchPattern='(?m)^.*\[minecraft/GameTestRunner\]: Running test batch '''+[regex]::Escape($taskBatch)+':0'' \(1 tests\)\.\.\.\r?$'
        if ([regex]::Matches($taskLog,$taskBatchPattern).Count -ne 1) { throw ('Expected unique single-test batch not executed: ' + $taskBatch) }
    }
    if ((Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskLedger.sourceSha256) { throw 'Native source changed while the probe was running.' }
    $taskLedger.requiredTestsPassed=13
    $taskLedger.status='PASSED_NATIVE_STATIONARY_MOB_PROBE'
    $taskExit=0
} catch {
    $taskLedger.status='FAILED'; $taskLedger.failure=$_.Exception.Message
    if ($taskExit -eq 0) { $taskExit=1 }
} finally {
    $taskLedger.finishedAtUtc=[DateTime]::UtcNow.ToString('o')
    if (Test-Path -LiteralPath $taskLogPath) {
        $taskLedger.nativeLogSha256=(Get-FileHash -LiteralPath $taskLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Get-Content -LiteralPath $taskLogPath -Tail 18
    }
    Save-PassengerLedger
    Write-Output ('Native passenger ledger: ' + $taskLedgerPath)
}
exit $taskExit
