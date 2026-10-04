[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedJarSha256,
    [switch]$ExcludeSableTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$version = '0.1.0-alpha.5'
$workspace = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath.TrimEnd('\', '/')
$workspacePrefix = $workspace + [IO.Path]::DirectorySeparatorChar

function Assert-WorkspacePath([string]$Path) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Resolved package path is outside the requested workspace.'
    }
    return $fullPath
}

function Assert-NoReparseAncestor([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current.Length -gt $workspace.Length) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'A package path contains a symbolic link or junction.'
            }
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}

$jar = Assert-WorkspacePath (Join-Path $workspace ('build/libs/universe-' + $version + '.jar'))
Assert-NoReparseAncestor $jar
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Expected already-built alpha JAR is missing.' }
$actualJarSha = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualJarSha -ne $ExpectedJarSha256.ToLowerInvariant()) { throw 'Built JAR SHA-256 does not match the reviewed hash.' }

# Read the historical verified ledger; do not ship the ledger or its private paths/logs.
$ledgerPath = Join-Path $workspace '.tooling/test-runs/20261004T203523132Z-33440/status.json'
$ledger = Get-Content -LiteralPath $ledgerPath -Raw | ConvertFrom-Json
if ($ledger.status -ne 'PASSED' -or $ledger.exitCode -ne 0 -or $ledger.versions.mod_version -ne $version -or
    $ledger.junit.tests -ne 106 -or $ledger.junit.failures -ne 0 -or $ledger.junit.errors -ne 0 -or
    $ledger.junit.skipped -ne 0 -or $ledger.sable.status -ne 'PASSED_NATIVE_PROBE' -or $ledger.sable.requiredTestsPassed -ne 10) {
    throw 'Historical alpha verification ledger is missing or does not match the reviewed results.'
}
$coreLog = Join-Path $workspace '.tooling/test-runs/20261004T203523132Z-33440/core-build-gametest.log'
if (-not (Select-String -LiteralPath $coreLog -SimpleMatch 'All 19 required tests passed' -Quiet)) {
    throw 'Historical 19 required core GameTests pass marker is missing.'
}

$releaseBase = Assert-WorkspacePath (Join-Path $workspace ('release/universe-core-' + $version))
Assert-NoReparseAncestor $releaseBase
$packageName = 'package-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$packageRoot = Assert-WorkspacePath (Join-Path $releaseBase $packageName)
if (Test-Path -LiteralPath $packageRoot) { throw 'Unique package destination already exists.' }
$null = [IO.Directory]::CreateDirectory($packageRoot)
$sourceRoot = Join-Path $packageRoot 'source'
$null = [IO.Directory]::CreateDirectory($sourceRoot)

$allowlist = @(
    'build.gradle', 'gradle.properties', 'settings.gradle', 'gradlew', 'gradlew.bat',
    'gradle/wrapper', 'src/main', 'src/test', 'src/sableClientTest', 'src/gravityRuntimeTest',
    'tools', 'configs', 'docs/PLAYER_TESTING.md', 'docs/RELEASE_README.md', 'README.md', 'LICENSE', 'LICENSE.md', 'LICENSE.txt'
)
if (-not $ExcludeSableTests) { $allowlist += 'src/sableTest' }
$deniedSegments = @('.git', '.tooling', '.gradle', '.codex', '.agents', 'build', 'runtime', 'node_modules', 'logs', 'reports', 'test-results')
$textExtensions = @('.md', '.txt', '.java', '.gradle', '.properties', '.ps1', '.json', '.toml', '.yml', '.yaml', '.xml', '.bat', '.sh')
$fileManifest = [Collections.Generic.List[object]]::new()

function Copy-AllowedFile([IO.FileInfo]$File) {
    $original = Assert-WorkspacePath $File.FullName
    Assert-NoReparseAncestor $original
    $relative = $original.Substring($workspacePrefix.Length)
    if ($relative -eq 'tools\prepare-alpha-release.ps1' -or $relative -eq 'tools/prepare-alpha-release.ps1') { return }
    if ($relative -eq 'README.md') { $original = Join-Path $workspace 'docs/RELEASE_README.md' }
    foreach ($segment in ($relative -split '[\\/]')) {
        if ($segment.StartsWith('.') -or $segment -in $deniedSegments) { return }
    }
    if ($File.Extension.ToLowerInvariant() -in $textExtensions -or $File.Name -eq 'gradlew' -or $File.Name -eq 'LICENSE') {
        $content = [IO.File]::ReadAllText($original)
        if ($content -match '(?i)(?:[a-z]:[\\/]Users[\\/]|/Users/|/home/|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})') {
            throw ('Source requires manual privacy review before packaging: ' + $relative)
        }
    }
    $destination = Assert-WorkspacePath (Join-Path $sourceRoot $relative)
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
    [IO.File]::Copy($original, $destination, $false)
    $fileManifest.Add([ordered]@{ path = $relative.Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() })
}

foreach ($allowedPath in $allowlist) {
    $source = Assert-WorkspacePath (Join-Path $workspace $allowedPath)
    if (-not (Test-Path -LiteralPath $source)) { continue }
    Assert-NoReparseAncestor $source
    $sourceItem = Get-Item -LiteralPath $source -Force
    if (-not $sourceItem.PSIsContainer) { Copy-AllowedFile $sourceItem; continue }
    $pendingDirectories = [Collections.Generic.Stack[string]]::new()
    $pendingDirectories.Push($source)
    while ($pendingDirectories.Count -gt 0) {
        $directory = $pendingDirectories.Pop()
        foreach ($entry in (Get-ChildItem -LiteralPath $directory -Force)) {
            if ($entry.Name.StartsWith('.') -or $entry.Name -in $deniedSegments) { continue }
            if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Source allowlist contains a symbolic link or junction.' }
            if ($entry.PSIsContainer) { $pendingDirectories.Push($entry.FullName) } else { Copy-AllowedFile $entry }
        }
    }
}
if ($fileManifest.Count -eq 0) { throw 'Source package is empty.' }

$publicNotes = @'
# Universe Core 0.1.0-alpha.5

Экспериментальное ядро для Minecraft 1.21.1 / NeoForge 21.1.251, Java 21.

Проверенный серверный прогон 2026-10-04: 106 JUnit, 19 обязательных core GameTest и 10 обязательных native Sable GameTest прошли. Native probe закреплён на Sable 2.0.5; он проверяет ограниченные серверные сценарии, а не готовый production backend.

Переходы пассажиров и групп, полноценное восстановление после сбоя, бесшовные межпланетные переходы, масштабные корабли, Photon-атмосферы и галактический рендер ещё требуют реализации и проверки. Динамический клиентский UDP-прогон выявил рассинхронизацию при остановке; TCP-диагностика не означает исправление UDP. Общий клиентский и визуальный PASS не заявлен.

JAR ядра не включает отдельные тестовые моды и зависимости Sable/Create/Veil. Подробные ограничения и команды ручной проверки находятся в исходниках и документации. Это alpha для проверки на отдельном тестовом мире.
'@
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $packageRoot 'RELEASE_NOTES.md'), $publicNotes, $utf8)
$packagedJar = Join-Path $packageRoot ([IO.Path]::GetFileName($jar))
[IO.File]::Copy($jar, $packagedJar, $false)
if ((Get-FileHash -LiteralPath $packagedJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $actualJarSha) { throw 'Copied JAR hash mismatch.' }
$manifest = [ordered]@{
    version = $version; createdAtUtc = [DateTime]::UtcNow.ToString('o'); jar = [IO.Path]::GetFileName($jar); jarSha256 = $actualJarSha
    verifiedHistoricalRun = '20261004T203523132Z-33440'; junitPassed = 106; coreGameTestsPassed = 19; nativeSableGameTestsPassed = 10
    clientVisualAcceptance = 'NOT_DECLARED'; sourceFiles = $fileManifest.ToArray()
}
[IO.File]::WriteAllText((Join-Path $packageRoot 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8), $utf8)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceZip = Join-Path $packageRoot ('universe-core-' + $version + '-source.zip')
if (Test-Path -LiteralPath $sourceZip) { throw 'Source ZIP destination already exists.' }
[IO.Compression.ZipFile]::CreateFromDirectory($sourceRoot, $sourceZip, [IO.Compression.CompressionLevel]::Optimal, $false)
$checksums = @(
    ($actualJarSha + '  ' + [IO.Path]::GetFileName($packagedJar))
    ((Get-FileHash -LiteralPath $sourceZip -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($sourceZip))
)
[IO.File]::WriteAllText((Join-Path $packageRoot 'SHA256SUMS.txt'), ($checksums -join "`n") + "`n", $utf8)
Write-Output $packageRoot
Write-Output 'Package prepared locally. No build, deletion, upload, or publication was performed.'

