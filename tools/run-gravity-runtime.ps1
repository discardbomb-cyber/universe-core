param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$RunId,
    [Parameter(Mandatory=$true)][ValidateSet('unload','restart-write','restart-read')][string]$Scenario,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_-]{16,80}$')][string]$Nonce,
    [ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$WriterRunId,
    [ValidateRange(30,180)][int]$TimeoutSeconds=150
)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
function Assert-DisposablePath([string]$Path) {
    $taskChecked=[IO.Path]::GetFullPath($Path)
    if (!$taskChecked.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Disposable path escaped workspace' }
    while ($taskChecked) {
        if (Test-Path -LiteralPath $taskChecked) {
            if ((Get-Item -LiteralPath $taskChecked -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse point in disposable path ancestor' }
        }
        if ([string]::Equals($taskChecked,$taskRoot,[StringComparison]::OrdinalIgnoreCase)) { break }
        $taskChecked=[IO.Path]::GetDirectoryName($taskChecked)
    }
}
$taskRuns=Join-Path $taskRoot '.tooling/gravity-runtime-runs'
$taskExpected=[IO.Path]::GetFullPath((Join-Path $taskRuns "$RunId/server"))
Assert-DisposablePath $taskExpected
$taskLaunch=Get-Content -LiteralPath (Join-Path $taskRoot ".tooling/gravity-runtime-launches/$RunId.json") -Raw | ConvertFrom-Json
if ($taskLaunch.runId -ne $RunId -or $taskLaunch.scenario -ne $Scenario -or $taskLaunch.nonce -ne $Nonce) { throw 'Launch provenance mismatch' }
if (![string]::Equals($taskExpected,$taskLaunch.workingDirectory,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected disposable launch directory' }
if ($taskLaunch.arguments | Where-Object { $_ -match '[\s"]' }) { throw 'Launch arguments require Windows quoting' }
if (($taskLaunch.arguments -join ' ').Length -gt 30000) { throw 'Launch exceeds Windows command limit' }
if (Test-Path -LiteralPath $taskExpected) {
    if (@(Get-ChildItem -LiteralPath $taskExpected -Force).Count) { throw 'Refusing nonempty disposable run' }
} else { New-Item -ItemType Directory -Path $taskExpected | Out-Null }
$taskWorld=Join-Path $taskExpected 'world'
$taskCopy=$null
if ($Scenario -eq 'restart-read') {
    if (!$WriterRunId -or $WriterRunId -eq $RunId) { throw 'Reader requires a distinct writer run' }
    $taskWriterDir=[IO.Path]::GetFullPath((Join-Path $taskRuns "$WriterRunId/server"))
    $taskSourceWorld=[IO.Path]::GetFullPath((Join-Path $taskWriterDir 'world'))
    Assert-DisposablePath $taskSourceWorld
    Assert-DisposablePath $taskWorld
    # Verify resolved targets and reject junctions before a recursive copy within our disposable roots.
    foreach ($taskPath in @($taskSourceWorld,$taskWorld)) {
        if (!$taskPath.StartsWith($taskRuns+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Copy path escaped disposable roots' }
    }
    $taskWriterState=Get-Content -LiteralPath (Join-Path $taskWriterDir 'supervisor.json') -Raw | ConvertFrom-Json
    if ($taskWriterState.exitCode -ne 0 -or $taskWriterState.status -ne 'PASSED_REAL_SAVE_AND_STOP_WRITER' -or $taskWriterState.nonce -ne $Nonce) { throw 'Writer did not finish successfully' }
    $taskExisting=Get-Process -Id $taskWriterState.pid -ErrorAction SilentlyContinue
    if ($taskExisting -and $taskExisting.StartTime.ToUniversalTime().ToString('o') -eq $taskWriterState.startedAtUtc) { throw 'Writer JVM is still running' }
    $taskReport=Get-Content -LiteralPath (Join-Path $taskRoot ".tooling/gravity-runtime-probes/$WriterRunId/report.json") -Raw | ConvertFrom-Json
    if ($taskReport.status -ne $taskWriterState.status -or $taskReport.runId -ne $WriterRunId -or $taskReport.pid -ne $taskWriterState.pid -or $taskReport.nonce -ne $Nonce) { throw 'Writer evidence mismatch' }
    $taskMarker=Get-Content -LiteralPath (Join-Path $taskSourceWorld 'gravity-runtime-expected.json') -Raw | ConvertFrom-Json
    if ($taskMarker.status -ne 'PASSED_WRITE_STOP' -or $taskMarker.runId -ne $WriterRunId -or $taskMarker.nonce -ne $Nonce -or $taskMarker.pid -ne $taskWriterState.pid -or $taskMarker.runtimeNonce -ne $taskReport.runtimeNonce -or $taskMarker.pigUuid -ne $taskReport.pigUuid) { throw 'Writer world marker provenance mismatch' }
    $taskNodes=@(Get-Item -LiteralPath $taskRuns)+@(Get-Item -LiteralPath $taskWriterDir)+@(Get-Item -LiteralPath $taskSourceWorld)+@(Get-ChildItem -LiteralPath $taskSourceWorld -Recurse -Force)
    if ($taskNodes | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Reparse point in disposable copy path' }
    if ((Get-Content -LiteralPath (Join-Path $taskSourceWorld 'gravity-runtime-optin.txt') -Raw).Trim() -ne $Nonce) { throw 'Writer fence mismatch' }
    foreach ($taskRequired in @('level.dat','gravity-runtime-expected.json','entities','region')) {
        if (!(Test-Path -LiteralPath (Join-Path $taskSourceWorld $taskRequired))) { throw 'Incomplete writer world' }
    }
    $taskHashes=@(Get-ChildItem -LiteralPath $taskSourceWorld -Recurse -File -Force | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($taskSourceWorld,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
    Copy-Item -LiteralPath $taskSourceWorld -Destination $taskExpected -Recurse
    foreach ($taskFile in $taskHashes) {
        if ((Get-FileHash -LiteralPath (Join-Path $taskWorld $taskFile.path) -Algorithm SHA256).Hash -ne $taskFile.sha256) { throw 'Copied world hash mismatch' }
    }
    $taskCopy=[ordered]@{writerRunId=$WriterRunId;writerPid=$taskWriterState.pid;source=$taskSourceWorld;destination=$taskWorld;files=$taskHashes}
    $taskCopy | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskExpected 'copied-world.json') -Encoding utf8
} else {
    if ($WriterRunId) { throw 'WriterRunId is only valid for a reader' }
    New-Item -ItemType Directory -Path $taskWorld | Out-Null
    $Nonce | Set-Content -LiteralPath (Join-Path $taskWorld 'gravity-runtime-optin.txt') -Encoding ascii
}
@'
server-ip=127.0.0.1
server-port=25576
online-mode=false
level-name=world
level-type=minecraft:flat
generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"lakes":false,"features":false,"structure_overrides":[]}
level-seed=42
view-distance=2
simulation-distance=4
max-players=1
enable-rcon=false
enable-query=false
spawn-protection=0
gamemode=spectator
difficulty=peaceful
spawn-animals=true
spawn-monsters=false
sync-chunk-writes=true
'@ | Set-Content -LiteralPath (Join-Path $taskExpected 'server.properties') -Encoding ascii
'eula=true' | Set-Content -LiteralPath (Join-Path $taskExpected 'eula.txt') -Encoding ascii
foreach ($taskProperty in $taskLaunch.environment.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($taskProperty.Name,[string]$taskProperty.Value,'Process')
}
$taskClock=[Diagnostics.Stopwatch]::StartNew()
$taskProcess=Start-Process -FilePath $taskLaunch.executable -ArgumentList ([string[]]$taskLaunch.arguments) `
    -WorkingDirectory $taskExpected -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $taskExpected 'stdout.log') -RedirectStandardError (Join-Path $taskExpected 'stderr.log') -PassThru
$taskState=[ordered]@{runId=$RunId;scenario=$Scenario;nonce=$Nonce;pid=$taskProcess.Id;startedAtUtc=$taskProcess.StartTime.ToUniversalTime().ToString('o');executable=$taskLaunch.executable;workingDirectory=$taskExpected;status='RUNNING'}
$taskState.sourceSha256=[ordered]@{}
foreach ($taskSource in @('src/gravityRuntimeTest/java/dev/heiko/universe/gravityruntime/GravityRuntimeProbe.java','tools/gravity-runtime-profile.gradle','tools/run-gravity-runtime.ps1')) {
    $taskState.sourceSha256[$taskSource]=(Get-FileHash -LiteralPath (Join-Path $taskRoot $taskSource) -Algorithm SHA256).Hash
}
$taskState.argumentFiles=@($taskLaunch.arguments | Where-Object { $_.StartsWith('@') } | ForEach-Object {
    [ordered]@{path=$_.Substring(1);sha256=(Get-FileHash -LiteralPath $_.Substring(1) -Algorithm SHA256).Hash}
})
$taskState.coreJarSha256=(Get-FileHash -LiteralPath (Join-Path $taskRoot 'build/libs/universe-0.1.0-alpha.5.jar') -Algorithm SHA256).Hash
$taskState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskExpected 'supervisor.json') -Encoding utf8
while (!$taskProcess.WaitForExit(1000)) {
    if ($taskClock.Elapsed.TotalSeconds -gt $TimeoutSeconds) {
        $taskState.status='FAILED_EXTERNAL_TIMEOUT'
        $taskState.elapsedSeconds=$taskClock.Elapsed.TotalSeconds
        $taskState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskExpected 'supervisor.json') -Encoding utf8
        # Kill only the exact JVM launched and retained by this supervisor.
        $taskProcess.Kill();$taskProcess.WaitForExit()
        throw 'Own disposable server exceeded wall timeout; artifacts retained'
    }
}
$taskProcess.WaitForExit();$taskProcess.Refresh()
$taskState.exitCode=$taskProcess.ExitCode
$taskState.elapsedSeconds=$taskClock.Elapsed.TotalSeconds
$taskState.finishedAtUtc=[DateTime]::UtcNow.ToString('o')
$taskReportPath=Join-Path $taskRoot ".tooling/gravity-runtime-probes/$RunId/report.json"
$taskExpectedStatus=@{'unload'='PASSED_ACTUAL_UNLOAD_RELOAD';'restart-write'='PASSED_REAL_SAVE_AND_STOP_WRITER';'restart-read'='PASSED_NEW_PROCESS_SAVED_WORLD_READ'}[$Scenario]
$taskState.status='FAILED_SUPERVISOR_VALIDATION'
try {
    $taskReport=Get-Content -LiteralPath $taskReportPath -Raw | ConvertFrom-Json
    $taskStdout=Get-Content -LiteralPath (Join-Path $taskExpected 'stdout.log') -Raw
    $taskStderr=Get-Content -LiteralPath (Join-Path $taskExpected 'stderr.log') -Raw
    if ($taskStdout -match '(?m)\b(?:ERROR|FATAL)\b|Exception stopping the server|Encountered an unexpected exception' -or $taskStderr -match '(?m)Exception in thread|Exception stopping the server') { throw 'Runtime or shutdown error in logs' }
    if ($taskState.exitCode -ne 0 -or $taskReport.status -ne $taskExpectedStatus -or $taskReport.runId -ne $RunId -or $taskReport.scenario -ne $Scenario -or $taskReport.nonce -ne $Nonce -or $taskReport.pid -ne $taskState.pid) { throw 'Process/report evidence failed' }
    $taskRuntimeGuid=[Guid]::Empty
    if (![Guid]::TryParse($taskReport.runtimeNonce,[ref]$taskRuntimeGuid) -or $taskReport.writtenAtMillis -lt ([DateTimeOffset]::Parse($taskState.startedAtUtc)).ToUnixTimeMilliseconds() -or $taskReport.writtenAtMillis -gt ([DateTimeOffset]::Parse($taskState.finishedAtUtc)).ToUnixTimeMilliseconds()) { throw 'Stale runtime evidence' }
    $taskState.status=$taskExpectedStatus
} finally {
    $taskState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskExpected 'supervisor.json') -Encoding utf8
}
$taskState | ConvertTo-Json -Depth 8
