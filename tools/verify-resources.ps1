param([string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$resourceRoot = Join-Path $ProjectRoot 'src/main/resources'
$script:failures = 0
$script:checks = 0
function Check([bool]$Condition, [string]$Message) {
    $script:checks++
    if (!$Condition) { $script:failures++; Write-Host "FAIL: $Message" -ForegroundColor Red }
}
function Ref([string]$Id, [string]$Registry, [string]$Origin) {
    Check ($Id -match '^[a-z0-9_.-]+:[a-z0-9_./-]+$') "$Origin invalid $Registry reference: $Id"
    if ($Id -notmatch '^universe:') { return }
    $relative = $Id.Substring(9)
    Check (Test-Path -LiteralPath (Join-Path $resourceRoot "data/universe/$Registry/$relative.json")) "$Origin unresolved $Registry reference: $Id"
}
$documents = @{}
$files = @(Get-ChildItem -LiteralPath $resourceRoot -Recurse -Filter '*.json' -File)
Check ($files.Count -gt 0) 'No JSON resources found'
foreach ($file in $files) {
    try { $documents[$file.FullName] = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json }
    catch { Check $false "$($file.FullName): invalid JSON: $($_.Exception.Message)" }
}
foreach ($name in @('space', 'shipyards', 'temperate', 'barren')) {
    foreach ($registry in @('dimension', 'dimension_type')) {
        Check (Test-Path -LiteralPath (Join-Path $resourceRoot "data/universe/$registry/$name.json")) "Missing required $registry/$name.json"
    }
}
foreach ($file in $files) {
    $doc = $documents[$file.FullName]
    if ($null -eq $doc) { continue }
    if ($file.Directory.Name -eq 'dimension') {
        Ref ([string]$doc.type) 'dimension_type' $file.FullName
        Check ($null -ne $doc.generator) "$($file.FullName): generator missing"
        Ref ([string]$doc.generator.type) 'worldgen/chunk_generator' $file.FullName
        if ($doc.generator.type -eq 'minecraft:flat') {
            Ref ([string]$doc.generator.settings.biome) 'worldgen/biome' $file.FullName
            Check ($null -ne $doc.generator.settings.layers) "$($file.FullName): flat layers missing"
        } elseif ($doc.generator.type -eq 'minecraft:noise') {
            if ($doc.generator.settings -is [string]) { Ref $doc.generator.settings 'worldgen/noise_settings' $file.FullName }
            Ref ([string]$doc.generator.biome_source.type) 'worldgen/biome_source' $file.FullName
            if ($doc.generator.biome_source.biome) { Ref $doc.generator.biome_source.biome 'worldgen/biome' $file.FullName }
            if ($doc.generator.biome_source.preset) { Ref $doc.generator.biome_source.preset 'worldgen/multi_noise_biome_source_parameter_list' $file.FullName }
        } else { Check $false "$($file.FullName): unsupported generator; extend validator before accepting" }
    }
    if ($file.Directory.Name -eq 'dimension_type') {
        foreach ($field in @('ambient_light','bed_works','coordinate_scale','effects','has_ceiling','has_raids','has_skylight','height','infiniburn','logical_height','min_y','monster_spawn_block_light_limit','monster_spawn_light_level','natural','piglin_safe','respawn_anchor_works','ultrawarm')) {
            Check ($null -ne $doc.PSObject.Properties[$field]) "$($file.FullName): missing $field"
        }
        Check (($doc.height -gt 0) -and ($doc.height % 16 -eq 0) -and ($doc.min_y % 16 -eq 0)) "$($file.FullName): invalid height/min_y alignment"
        Check (($doc.logical_height -gt 0) -and ($doc.logical_height -le $doc.height)) "$($file.FullName): invalid logical_height"
        Check ($doc.coordinate_scale -gt 0) "$($file.FullName): nonpositive coordinate_scale"
    }
}
$properties = @{}
Get-Content -LiteralPath (Join-Path $ProjectRoot 'gradle.properties') | ForEach-Object {
    if ($_ -match '^\s*([a-z_]+)\s*=\s*(.*?)\s*$') { $properties[$matches[1]] = $matches[2] }
}
$toml = Get-Content -LiteralPath (Join-Path $resourceRoot 'META-INF/neoforge.mods.toml') -Raw
# Deliberately a lexical check of this project's simple metadata, not a TOML parser.
$mods = [regex]::Match($toml, '(?s)\[\[mods\]\](.*?)(?=\[\[|\z)').Groups[1].Value
foreach ($pair in @(@('modId','mod_id'), @('version','mod_version'))) {
    $value = [regex]::Match($mods, ('(?m)^\s*' + $pair[0] + '\s*=\s*"([^"\r\n]+)"')).Groups[1].Value
    Check ($value -eq $properties[$pair[1]] -and $value.Length -gt 0) "TOML $($pair[0]) differs from gradle.properties"
}
Check ($properties['mod_id'] -eq 'universe') 'Validator expects universe namespace'
Check ($properties['minecraft_version'] -eq '1.21.1') 'Expected Minecraft 1.21.1'
Check ($toml -match '(?m)^modLoader\s*=\s*"javafml"') 'Expected javafml loader'
Check ($toml -match 'versionRange\s*=\s*"\[1\.21\.1,1\.21\.2\)"') 'Missing exact Minecraft dependency interval'
Check ($toml.Contains('versionRange="[' + $properties['neo_version'] + ',)"')) 'NeoForge minimum differs from pinned build version'
$modSource = Get-Content -LiteralPath (Join-Path $ProjectRoot 'src/main/java/dev/heiko/universe/UniverseMod.java') -Raw
Check ($modSource -match 'ID\s*=\s*"universe"') 'Java mod ID differs from resource namespace'
Write-Host "Static resource verification: $script:checks checks, $script:failures failures, $($files.Count) JSON files."
Write-Host 'Scope: JSON parsing, required files, selected local registry references and lexical metadata consistency.'
Write-Host 'Not verified: full TOML/codec schemas, vanilla references, registry loading, compilation, gameplay, saves or performance. Run the shared Gradle/runtime checks separately.'
if ($script:failures -gt 0) { exit 1 }
exit 0
