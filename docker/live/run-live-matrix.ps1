#Requires -Version 7.0
<#
.SYNOPSIS
Runs the LIVE JDBC suite against the Netezza appliance from ephemeral Docker
containers in a WSL2 distro, once per supported JDK.

.DESCRIPTION
The Netezza appliance keeps running in its own WSL distro (for example
NPS-UBU26). This script only uses the Docker engine in the distro passed with
-Distro (default: Ubuntu). Containers reach the appliance through
host.docker.internal, so the database does not need to be exposed publicly.

Each JDK gets its own image, its own container and its own Surefire report
directory. Containers run sequentially because the LIVE suite shares the
CONFORMANCE schema.

.PARAMETER JavaVersions
JDK versions to test. Supported values: 17, 21, 25.

.PARAMETER Distro
WSL2 distribution that runs Docker. Keep the Netezza distro out of this.

.PARAMETER DatabaseHost
Host name used inside the container. The default host.docker.internal works
with the Docker bridge network and --add-host=host.docker.internal:host-gateway.

.PARAMETER Schema
Conformance schema used by the LIVE tests. Defaults to NZ_CONFORMANCE_SCHEMA
or CONFORMANCE.

.PARAMETER MavenImagePrefix
Prefix of the official Maven image; the JDK version is appended.

.PARAMETER CacheVolume
Named Docker volume that caches /root/.m2/repository between runs.

.EXAMPLE
.\docker\live\run-live-matrix.ps1

.EXAMPLE
.\docker\live\run-live-matrix.ps1 -JavaVersions 21 -KeepImages

.EXAMPLE
.\docker\live\run-live-matrix.ps1 -JavaVersions 17,25 -CleanCache -FailFast
#>
[CmdletBinding()]
param(
    [string[]]$JavaVersions = @('17', '21', '25'),

    [string]$Distro = 'Ubuntu',

    [string]$DatabaseHost = 'host.docker.internal',

    [string]$Schema = '',

    [string]$MavenImagePrefix = 'maven:3.10-eclipse-temurin-',

    [string]$CacheVolume = 'justybase-nz-maven-cache',

    [switch]$KeepImages,
    [switch]$CleanCache,
    [switch]$SkipBuild,
    [switch]$FailFast,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Convert-ToWslPath {
    param([Parameter(Mandatory)][string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Only local Windows drive paths are supported: $full"
    }
    $drive = $Matches[1].ToLowerInvariant()
    $rest = $Matches[2] -replace '\\', '/'
    return "/mnt/$drive/$rest"
}

function New-LiveEnvFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$HostName,
        [Parameter(Mandatory)][string]$Port,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$AllowDdl,
        [Parameter(Mandatory)][string]$SchemaName
    )

    $values = [ordered]@{
        NZ_DEV_HOST                = $HostName
        NZ_DEV_PORT                = $Port
        NZ_DEV_USER                = $User
        NZ_DEV_PASSWORD            = $Password
        NZ_DEV_DB                  = $Database
        NZ_DEV_ALLOW_FIXTURE_DDL   = $AllowDdl
        NZ_CONFORMANCE_SCHEMA      = $SchemaName
    }

    foreach ($name in @('NZ_DEV_SSLMODE', 'NZ_CONFORMANCE_CONNECT_TIMEOUT', 'NZ_CONFORMANCE_SOCKET_TIMEOUT')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value) { $values[$name] = $value }
    }

    foreach ($entry in $values.GetEnumerator()) {
        if ($entry.Value -match "[\r\n]") {
            throw "$($entry.Key) contains a newline and cannot be written to a Docker env file"
        }
    }

    $content = ($values.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join "`n"
    [IO.File]::WriteAllText($Path, $content + "`n", [Text.UTF8Encoding]::new($false))
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = (Resolve-Path (Join-Path $scriptDir '..\..')).Path
$dockerfile = Join-Path $projectDir 'docker\live\Dockerfile'
if (-not (Test-Path -LiteralPath $dockerfile)) {
    throw "Dockerfile not found: $dockerfile"
}

$projectWsl = Convert-ToWslPath $projectDir
$dockerfileWsl = Convert-ToWslPath $dockerfile

$user = $env:NZ_DEV_USER
$password = $env:NZ_DEV_PASSWORD
$database = if ($env:NZ_DEV_DB) { $env:NZ_DEV_DB } elseif ($env:NZ_DEV_DATABASE) { $env:NZ_DEV_DATABASE } else { $null }
$port = if ($env:NZ_DEV_PORT) { $env:NZ_DEV_PORT } else { '5480' }
$allowDdl = if ($null -ne $env:NZ_DEV_ALLOW_FIXTURE_DDL) { $env:NZ_DEV_ALLOW_FIXTURE_DDL } else { '1' }
$schemaName = if ($Schema) { $Schema } elseif ($env:NZ_CONFORMANCE_SCHEMA) { $env:NZ_CONFORMANCE_SCHEMA } else { 'CONFORMANCE' }

if (-not $user) { throw 'NZ_DEV_USER is required' }
if (-not $password) { throw 'NZ_DEV_PASSWORD is required' }
if (-not $database) { throw 'NZ_DEV_DB or NZ_DEV_DATABASE is required' }

$schemaName = $schemaName.ToUpperInvariant()

# -File passes "17,25" as one string; an interactive shell passes an array.
$versions = @()
foreach ($entry in $JavaVersions) {
    foreach ($part in ($entry -split '[, ]+')) {
        if ($part) { $versions += $part }
    }
}
foreach ($version in $versions) {
    if ($version -notin @('17', '21', '25')) {
        throw "Unsupported Java version '$version'. Supported values: 17, 21, 25."
    }
}

if (-not $DryRun) {
    & wsl -d $Distro -e bash -lc 'docker info >/dev/null 2>&1'
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is not available in WSL distro '$Distro'. Start it with: wsl -d $Distro -u root -e systemctl start docker"
    }
}

$probeHost = if ($DatabaseHost -eq 'host.docker.internal') { '127.0.0.1' } else { $DatabaseHost }
& wsl -d $Distro -e bash -lc "timeout 3 bash -c '</dev/tcp/$probeHost/$port' 2>/dev/null"
if ($LASTEXITCODE -ne 0) {
    Write-Warning "Netezza is not reachable at ${probeHost}:$port from WSL '$Distro'; container runs may fail."
}

$workDir = Join-Path $projectDir 'target\docker-live'
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
$envFile = Join-Path $workDir 'live.env'
New-LiveEnvFile -Path $envFile -HostName $DatabaseHost -Port $port -User $user -Password $password `
    -Database $database -AllowDdl $allowDdl -SchemaName $schemaName
$envFileWsl = Convert-ToWslPath $envFile

if ($CleanCache -and -not $DryRun) {
    & wsl -d $Distro -e bash -lc "docker volume rm -f '$CacheVolume' >/dev/null 2>&1 || true" | Out-Null
}

$results = [System.Collections.Generic.List[object]]::new()

try {
    foreach ($version in $versions) {
        $image = "$MavenImagePrefix$version"
        $tag = "justybase-nz-live:$version"
        $container = "justybase-nz-live-$version"
        $reportHost = Join-Path $workDir "jdk-$version"
        $reportWsl = Convert-ToWslPath $reportHost
        New-Item -ItemType Directory -Path $reportHost -Force | Out-Null

        Write-Host ''
        Write-Host "=== Java $version ===" -ForegroundColor Cyan

        if (-not $SkipBuild) {
            $buildCommand = "cd '$projectWsl' && docker build -f '$dockerfileWsl' --build-arg MAVEN_IMAGE='$image' -t '$tag' ."
            Write-Host "Building $tag from $image"
            if ($DryRun) {
                Write-Host "[dry-run] $buildCommand"
            } else {
                & wsl -d $Distro -e bash -lc $buildCommand
                if ($LASTEXITCODE -ne 0) {
                    $results.Add([pscustomobject]@{
                        Java     = $version
                        Result   = 'BUILD FAILED'
                        ExitCode = $LASTEXITCODE
                        Reports  = ''
                    })
                    if ($FailFast) { break }
                    continue
                }
            }
        }

        $runCommand = "docker rm -f '$container' >/dev/null 2>&1 || true; " +
            "docker run --name '$container' --add-host=host.docker.internal:host-gateway " +
            "--env-file '$envFileWsl' -v '${CacheVolume}:/root/.m2/repository' '$tag'"

        Write-Host 'Running LIVE suite'
        if ($DryRun) {
            Write-Host "[dry-run] $runCommand"
            $exitCode = 0
        } else {
            & wsl -d $Distro -e bash -lc $runCommand
            $exitCode = $LASTEXITCODE

            $copyCommand = "rm -rf '$reportWsl'; mkdir -p '$reportWsl'; " +
                "docker cp '${container}:/workspace/target/surefire-reports' '$reportWsl/' >/dev/null 2>&1 || true; " +
                "docker rm -f '$container' >/dev/null 2>&1 || true"
            & wsl -d $Distro -e bash -lc $copyCommand | Out-Null

            if (-not $KeepImages) {
                & wsl -d $Distro -e bash -lc "docker rmi -f '$tag' >/dev/null 2>&1 || true" | Out-Null
            }
        }

        $results.Add([pscustomobject]@{
            Java     = $version
            Result   = if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' }
            ExitCode = $exitCode
            Reports  = $reportHost
        })

        if ($exitCode -ne 0 -and $FailFast) { break }
    }
} finally {
    Remove-Item -LiteralPath $envFile -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host 'LIVE matrix summary' -ForegroundColor Cyan
$results | Format-Table Java, Result, ExitCode, Reports -AutoSize

$failed = @($results | Where-Object { $_.ExitCode -ne 0 })
if ($failed.Count -gt 0) {
    exit 1
}
