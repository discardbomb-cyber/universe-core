param(
    [Parameter(Mandatory=$true)][ValidateSet('server','client')][string]$Role,
    [string]$RunId,
    [switch]$Tcp
)
$ErrorActionPreference='Stop'
$taskProjectRoot=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
function Assert-ClientDisposablePath([string]$Path) {
    $taskChecked=[IO.Path]::GetFullPath($Path)
    if (!$taskChecked.StartsWith($taskProjectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Probe path escaped workspace' }
    while ($taskChecked) {
        if (Test-Path -LiteralPath $taskChecked) {
            if ((Get-Item -LiteralPath $taskChecked -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse point in probe path' }
        }
        if ([string]::Equals($taskChecked,$taskProjectRoot,[StringComparison]::OrdinalIgnoreCase)) { break }
        $taskChecked=[IO.Path]::GetDirectoryName($taskChecked)
    }
}
if (-not $RunId) {
    $RunId=(Get-Content -LiteralPath (Join-Path $taskProjectRoot '.tooling/client-current.json') -Raw | ConvertFrom-Json).runId
}
if ($RunId -notmatch '^[A-Za-z0-9_-]{1,64}$') { throw 'Invalid probe run ID' }
$taskLaunch=Get-Content -LiteralPath (Join-Path $taskProjectRoot ".tooling/client-launches/$RunId.json") -Raw | ConvertFrom-Json
$taskRoleLaunch=$taskLaunch.$Role
$taskOtherRole=if ($Role -eq 'server') { 'client' } else { 'server' }
if ($taskRoleLaunch.runId -ne $RunId -or $taskLaunch.$taskOtherRole.runId -ne $RunId -or
    $taskRoleLaunch.nonce -notmatch '^[A-Za-z0-9_-]{16,80}$' -or $taskRoleLaunch.nonce -ne $taskLaunch.$taskOtherRole.nonce -or
    $taskRoleLaunch.dynamic -ne $taskLaunch.$taskOtherRole.dynamic) { throw 'Launch run/nonce/mode provenance mismatch' }
$taskExpected=[IO.Path]::GetFullPath((Join-Path $taskProjectRoot ".tooling/client-runs/$RunId/$Role"))
Assert-ClientDisposablePath $taskExpected
if (![string]::Equals($taskExpected,$taskRoleLaunch.workingDirectory,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Launch directory is outside the selected disposable role'
}
if (Test-Path -LiteralPath $taskExpected) {
    if (@(Get-ChildItem -LiteralPath $taskExpected -Force).Count -ne 0) { throw 'Refusing a nonempty role directory' }
} else { New-Item -ItemType Directory -Path $taskExpected | Out-Null }
if (($taskRoleLaunch.arguments -join ' ').Length -gt 30000) { throw 'Windows launch command is too long' }
# Generated arguments in this workspace have no whitespace/quotes; refuse instead of guessing escaping.
if ($taskRoleLaunch.arguments | Where-Object { $_ -match '[\s"]' }) { throw 'Launch arguments require Windows quoting' }
$taskBundle=[IO.Path]::GetFullPath((Join-Path $taskProjectRoot ".tooling/client-launches/$RunId"))
$taskArgFiles=@($taskRoleLaunch.arguments | Where-Object { $_.StartsWith('@') } | ForEach-Object {
    $taskArgFile=[IO.Path]::GetFullPath($_.Substring(1))
    Assert-ClientDisposablePath $taskArgFile
    if (!$taskArgFile.StartsWith($taskBundle+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Argument file is not frozen for this run' }
    [ordered]@{path=$taskArgFile;sha256=(Get-FileHash -LiteralPath $taskArgFile -Algorithm SHA256).Hash}
})
if ($taskArgFiles.Count -lt 2) { throw 'Missing frozen VM/program argument files' }
if ($Tcp) {
    $taskConfigDirectory=Join-Path $taskExpected 'config'
    New-Item -ItemType Directory -Path $taskConfigDirectory | Out-Null
    'attempt_udp_networking = false' | Set-Content -LiteralPath (Join-Path $taskConfigDirectory 'sable-common.toml') -Encoding ascii
    if ($Role -eq 'client') { 'attempt_udp_networking = false' | Set-Content -LiteralPath (Join-Path $taskConfigDirectory 'sable-client.toml') -Encoding ascii }
}
if ($Role -eq 'server') {
    @'
server-ip=127.0.0.1
server-port=25575
online-mode=false
level-name=world
level-type=minecraft:flat
generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"lakes":false,"features":false,"structure_overrides":[]}
level-seed=42
view-distance=6
simulation-distance=4
max-players=2
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
} else {
    @'
skipMultiplayerWarning:true
onboardAccessibility:false
tutorialStep:none
pauseOnLostFocus:false
enableVsync:false
maxFps:60
renderDistance:6
simulationDistance:5
guiScale:2
'@ | Set-Content -LiteralPath (Join-Path $taskExpected 'options.txt') -Encoding ascii
}
foreach ($taskProperty in $taskRoleLaunch.environment.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($taskProperty.Name,[string]$taskProperty.Value,'Process')
}
$taskState=[ordered]@{
    role=$Role;runId=$RunId;pid=$null;startedAtUtc=$null
    executable=$taskRoleLaunch.executable;workingDirectory=$taskExpected
    nonce=$taskRoleLaunch.nonce;dynamic=$taskRoleLaunch.dynamic;status='RUNNING';argumentFiles=$taskArgFiles
    tcpRequested=[bool]$Tcp
    coreJarSha256=(Get-FileHash -LiteralPath (Join-Path $taskProjectRoot 'build/libs/universe-0.1.0-alpha.5.jar') -Algorithm SHA256).Hash
}
$taskState.sourceSha256=[ordered]@{}
foreach ($taskSource in @(Get-ChildItem -LiteralPath (Join-Path $taskProjectRoot 'src/sableClientTest') -File -Recurse)+
    @(Get-Item -LiteralPath (Join-Path $taskProjectRoot 'build.gradle'),$PSCommandPath,(Join-Path $PSScriptRoot 'run-dynamic-client-probe.ps1'))) {
    $taskState.sourceSha256[[IO.Path]::GetRelativePath($taskProjectRoot,$taskSource.FullName)]=(Get-FileHash -LiteralPath $taskSource.FullName -Algorithm SHA256).Hash
}
    $taskProcess=$null
try {
    $taskProcess=Start-Process -FilePath $taskRoleLaunch.executable -ArgumentList ([string[]]$taskRoleLaunch.arguments) `
        -WorkingDirectory $taskExpected -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $taskExpected 'stdout.log') `
        -RedirectStandardError (Join-Path $taskExpected 'stderr.log') -PassThru
    $null=$taskProcess.Handle
    $taskState.pid=$taskProcess.Id;$taskState.startedAtUtc=$taskProcess.StartTime.ToUniversalTime().ToString('o')
$taskState | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskExpected 'supervisor.json') -Encoding utf8
$taskState | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskProjectRoot ".tooling/client-$Role-process.json") -Encoding utf8
$taskState | ConvertTo-Json -Depth 10
} catch {
    if ($taskProcess) {
        if (!$taskProcess.HasExited) { $taskProcess.Kill();$taskProcess.WaitForExit() }
        $taskProcess.Refresh();$taskState.status='FAILED_LAUNCH_REGISTRATION'
        $taskState.exitCode=$taskProcess.ExitCode;$taskState.error=$_.Exception.Message
        $taskState | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskExpected 'supervisor.json') -Encoding utf8
    }
    throw
}
