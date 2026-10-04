param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$RunId,
    [ValidateRange(60,240)][int]$TimeoutSeconds=210,
    [switch]$Tcp
)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$taskLaunch=Get-Content -LiteralPath (Join-Path $taskRoot ".tooling/client-launches/$RunId.json") -Raw | ConvertFrom-Json
if (!$taskLaunch.server.dynamic -or !$taskLaunch.client.dynamic) { throw 'Dynamic supervisor requires dynamic launch' }
if ([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq 25575 }) { throw 'Test loopback port is occupied; no process stopped' }
$taskStates=@{}
$taskProcesses=@{}
$taskClock=[Diagnostics.Stopwatch]::StartNew()
$taskFailure=$null
function Start-OwnedRole([string]$Role) {
    $taskState=& (Join-Path $PSScriptRoot 'start-client-probe.ps1') -Role $Role -RunId $RunId -Tcp:$Tcp | ConvertFrom-Json -DateKind String
    $taskProcess=Get-Process -Id $taskState.pid -ErrorAction Stop
    $null=$taskProcess.Handle # Retain handle before the child can finish, preserving its actual exit code.
    $taskStates[$Role]=$taskState;$taskProcesses[$Role]=$taskProcess
    if ($taskProcess.StartTime.ToUniversalTime().ToString('o') -ne $taskState.startedAtUtc) { throw 'PID provenance mismatch' }
    Write-Output "Started own $Role PID $($taskState.pid)"
}
try {
    Start-OwnedRole 'server'
    $taskReady=Join-Path $taskRoot ".tooling/client-probes/$RunId/server/dynamic-state.json"
    while (!(Test-Path -LiteralPath $taskReady)) {
        if ($taskProcesses.server.HasExited) { throw 'Own server exited before fixture readiness' }
        if ($taskClock.Elapsed.TotalSeconds -gt 75) { throw 'Fixture readiness timeout' }
        Start-Sleep -Milliseconds 250
    }
    $taskState=Get-Content -LiteralPath $taskReady -Raw | ConvertFrom-Json
    if ($taskState.phase -ne 'HELD' -or $taskState.stage -ne 0 -or $taskState.runId -ne $RunId -or $taskState.sessionNonce -ne $taskLaunch.server.nonce) { throw 'Fixture readiness failed or mismatched' }
    Start-OwnedRole 'client'
    while (@($taskProcesses.Values | Where-Object { !$_.HasExited }).Count) {
        if ($taskClock.Elapsed.TotalSeconds -gt $TimeoutSeconds) { throw 'Own dynamic JVMs exceeded wall timeout' }
        Start-Sleep -Milliseconds 500
    }
    foreach ($taskRole in @('server','client')) {
        $taskProcesses[$taskRole].WaitForExit();$taskProcesses[$taskRole].Refresh()
        $taskStates[$taskRole].status='EXITED';$taskStates[$taskRole] | Add-Member exitCode $taskProcesses[$taskRole].ExitCode -Force
        if ($taskProcesses[$taskRole].ExitCode -ne 0) { throw "Own $taskRole exit code is nonzero" }
        $taskReport=Get-Content -LiteralPath (Join-Path $taskRoot ".tooling/client-probes/$RunId/$taskRole/report.json") -Raw | ConvertFrom-Json
        if ($taskReport.status -ne 'DYNAMIC_CAPTURED_PENDING_VALIDATION' -or $taskReport.runId -ne $RunId -or !$taskReport.dynamic -or $taskReport.sessionNonce -ne $taskLaunch.server.nonce -or $taskReport.pid -ne $taskStates[$taskRole].pid) { throw "Own $taskRole evidence failed" }
    }
    Write-Output 'Both own JVMs exited 0; captures await independent numeric, pixel and visual validation'
} catch {
    $taskFailure=$_.Exception.Message
    throw
} finally {
    foreach ($taskRole in @($taskProcesses.Keys)) {
        $taskProcess=$taskProcesses[$taskRole]
        if (!$taskProcess.HasExited) {
            $taskProcess.Kill();$taskProcess.WaitForExit();$taskStates[$taskRole].status='FAILED_SUPERVISOR_TERMINATED_OWN_PROCESS'
        }
        $taskProcess.Refresh()
        if ($taskStates[$taskRole].status -eq 'RUNNING') { $taskStates[$taskRole].status='EXITED_WITH_VALIDATION_FAILURE' }
        $taskStates[$taskRole] | Add-Member exitCode $taskProcess.ExitCode -Force
        $taskStates[$taskRole] | Add-Member elapsedSeconds $taskClock.Elapsed.TotalSeconds -Force
        $taskStates[$taskRole] | Add-Member supervisorError $taskFailure -Force
        $taskStates[$taskRole] | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskStates[$taskRole].workingDirectory 'supervisor.json') -Encoding utf8
    }
}
