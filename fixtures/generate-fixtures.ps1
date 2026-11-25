<#
.SYNOPSIS
  Regenerates the Maven-generated dependency-tree fixtures from the synthetic projects in fixtures/.

.DESCRIPTION
  Uses an isolated local repository (target/fixture-work/m2), so the first run downloads Maven plugins from
  Maven Central. Normal unit tests never run this script; they only read the committed output. Works from a clean
  checkout (the target/fixture-work log directory is created on demand) and can be re-run: each case's tree and
  console files are removed before being rewritten, so nothing accumulates.

.PARAMETER Maven
  Maven executable or wrapper. Defaults to "mvn" from PATH.
#>
param([string] $Maven = "mvn")

$ErrorActionPreference = "Stop"
$plugin = "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$repo = Join-Path $root "target\fixture-work\m2"
$out = Join-Path $root "src\test\resources\fixtures\maven-generated"
New-Item -ItemType Directory -Force $out | Out-Null

function Invoke-Maven([string] $workDir, [string] $logFile, [string[]] $mavenArgs) {
    # cmd redirection keeps the console log byte-for-byte as Maven wrote it (no BOM, no re-encoding).
    # The log directory (target\fixture-work on a clean checkout) must exist before cmd opens the redirection.
    New-Item -ItemType Directory -Force (Split-Path -Parent $logFile) | Out-Null
    $argLine = ($mavenArgs | ForEach-Object { '"' + $_ + '"' }) -join " "
    Push-Location $workDir
    try {
        & cmd.exe /c "`"$Maven`" $argLine > `"$logFile`" 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "Maven failed in $workDir; see $logFile" }
    } finally { Pop-Location }
}

$common = @("-B", "-ntp", "-Dmaven.repo.local=$repo")

Invoke-Maven (Join-Path $root "fixtures\libs") (Join-Path $root "target\fixture-work\libs-install.log") ($common + @("clean", "install"))

$treeArgs = @("-Dverbose=true", "-Dtokens=standard", "-DoutputType=text", "-DappendOutput=true")
foreach ($case in "conflict", "noconflict", "classifier", "managed", "multimodule") {
    $dir = Join-Path $root "fixtures\projects\$case"
    $treeFile = Join-Path $out "$case.tree.txt"
    $logFile = Join-Path $out "$case.console.log"
    $scratchLog = Join-Path $root "target\fixture-work\$case.outputfile-run.log"
    Remove-Item $treeFile, $logFile -ErrorAction SilentlyContinue
    # Run 1: console only. The console log of an outputFile run prints the absolute output path, so it is not kept.
    Invoke-Maven $dir $logFile ($common + @($plugin) + $treeArgs)
    # Run 2: plugin outputFile (plain tree text, no log prefixes).
    Invoke-Maven $dir $scratchLog ($common + @($plugin) + $treeArgs + @("-DoutputFile=$treeFile"))
}

$versions = & $Maven -v 2>&1 | Where-Object { $_ -match "^Apache Maven|^Java version" } |
    ForEach-Object { $_ -replace ", runtime:.*$", "" }
Set-Content -Encoding ascii (Join-Path $out "tool-versions.txt") (@($versions) + "maven-dependency-plugin 3.11.0")
Write-Host "Fixtures written to $out"
