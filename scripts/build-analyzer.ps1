#Requires -Version 7.3

[CmdletBinding()]
param([switch]$AcceptModules)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$RepoRoot = Split-Path -Parent $PSScriptRoot
$AnalyzerDir = Join-Path $RepoRoot 'analyzer'
$Stage = Join-Path (Join-Path (Join-Path $RepoRoot 'src-tauri') 'resources') 'analyzer'
$ModulesFile = Join-Path $AnalyzerDir 'runtime-modules.txt'
$JavaRelease = '25'
$MainClass = 'br.pucpr.tcc.strata.analyzer.bootstrap.Main'

function Stop-Build([string]$Message, [int]$Code = 1) {
  [Console]::Error.WriteLine("build-analyzer.ps1: $Message")
  exit $Code
}

function Write-Step([string]$Message) { Write-Host "build-analyzer: $Message" }

function Get-JavaProperty([string]$Java, [string]$Name) {
  $pattern = '^\s*' + [regex]::Escape($Name) + ' = (.*)$'
  foreach ($line in (& $Java -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" })) {
    if ($line -match $pattern) { return $Matches[1].Trim() }
  }
  return ''
}

function Resolve-Jdk {
  $jdk = $env:JAVA_HOME
  if (-not $jdk) {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) { $jdk = Get-JavaProperty $java.Source 'java.home' }
  }
  if (-not $jdk) { Stop-Build "nenhum JDK encontrado: aponte JAVA_HOME para um JDK $JavaRelease" 2 }
  foreach ($tool in 'java', 'jdeps', 'jlink') {
    if (-not (Test-Path (Join-Path $jdk "bin\$tool.exe"))) {
      Stop-Build "$jdk\bin\$tool.exe não existe; JAVA_HOME tem de ser um JDK completo, não um runtime" 2
    }
  }
  $version = Get-JavaProperty (Join-Path $jdk 'bin\java.exe') 'java.specification.version'
  if ($version -ne $JavaRelease) { Stop-Build "$jdk é Java $version; o analisador é feito para o Java $JavaRelease" 2 }
  return $jdk
}

function Get-AnalyzerJar {
  $jars = @(Get-ChildItem (Join-Path $AnalyzerDir 'target') -Filter 'analyzer-*.jar' |
      Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' })
  if ($jars.Count -ne 1) { Stop-Build "era esperado exatamente um jar do analisador em analyzer\target, e há $($jars.Count)" }
  return $jars[0].FullName
}

function Test-Modules([string]$Computed) {
  $lines = @(if (Test-Path $ModulesFile) { Get-Content $ModulesFile } else { @() })
  $expected = ($lines | Where-Object { $_ -notmatch '^#' -and $_.Trim() } | Select-Object -First 1)
  if ($Computed -eq $expected) {
    Write-Step "o conjunto de módulos confere com analyzer/runtime-modules.txt: $Computed"
    return
  }
  if ($AcceptModules) {
    $header = @($lines | Where-Object { $_ -match '^#' })
    $text = (($header + $Computed) -join "`n") + "`n"
    [IO.File]::WriteAllText($ModulesFile, $text, [Text.UTF8Encoding]::new($false))
    Write-Step "baseline de módulos regravada: $expected -> $Computed"
    return
  }
  Stop-Build @"
o conjunto de módulos do runtime mudou
  baseline (analyzer/runtime-modules.txt): $expected
  calculado pelo jdeps:                    $Computed
Confira se o novo conjunto é intencional e então rode: pwsh scripts/build-analyzer.ps1 -AcceptModules
e inclua a baseline no mesmo commit do código que causou a mudança.
"@
}

function Test-StagedRuntime {
  $scratch = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ("build-analyzer-" + [guid]::NewGuid()))
  try {
    $java = Join-Path $Stage 'runtime\bin\java.exe'
    $classpath = (Join-Path $Stage 'analyzer.jar') + ';' + (Join-Path $Stage 'lib\*')
    $PSNativeCommandUseErrorActionPreference = $false
    $stdout = $null | & $java '-Xss2m' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' `
      '-cp' $classpath $MainClass "--data-dir=$($scratch.FullName)" 2>$null
    if ($LASTEXITCODE -ne 0) { Stop-Build "o runtime preparado saiu com $LASTEXITCODE no EOF do stdin" }
    if (-not ($stdout -match '"event":"protocol.ready"')) { Stop-Build 'o runtime preparado não imprimiu protocol.ready' }
    Write-Step 'o runtime preparado responde ao handshake'
  } finally {
    Remove-Item -Recurse -Force $scratch.FullName -ErrorAction SilentlyContinue
  }
}

$jdk = Resolve-Jdk
Write-Step "JDK $(Get-JavaProperty (Join-Path $jdk 'bin\java.exe') 'java.runtime.version') em $jdk"

Write-Step 'mvnw verify'
Push-Location $AnalyzerDir
try {
  $env:JAVA_HOME = $jdk
  & .\mvnw.cmd -q verify
} finally {
  Pop-Location
}
$jar = Get-AnalyzerJar

$modules = (& (Join-Path $jdk 'bin\jdeps.exe') --print-module-deps --ignore-missing-deps `
    --multi-release $JavaRelease --class-path (Join-Path $AnalyzerDir 'target\lib\*') $jar |
    Select-Object -Last 1).Trim()
Test-Modules $modules

if (-not $Stage.EndsWith((Join-Path (Join-Path 'src-tauri' 'resources') 'analyzer'))) {
  Stop-Build "recusando limpar um caminho de staging inesperado: $Stage" 2
}
if (Test-Path $Stage) { Remove-Item -Recurse -Force $Stage }
New-Item -ItemType Directory -Path (Join-Path $Stage 'lib') | Out-Null

Write-Step "jlink --add-modules $modules"
& (Join-Path $jdk 'bin\jlink.exe') --add-modules $modules --strip-debug --no-header-files `
  --no-man-pages --compress=zip-6 --output (Join-Path $Stage 'runtime')
Get-ChildItem -Recurse -File (Join-Path $Stage 'runtime') |
  Where-Object IsReadOnly | ForEach-Object { $_.IsReadOnly = $false }
Copy-Item $jar (Join-Path $Stage 'analyzer.jar')
Copy-Item (Join-Path $AnalyzerDir 'target\lib\*.jar') (Join-Path $Stage 'lib')

Test-StagedRuntime
Write-Step "preparado em src-tauri\resources\analyzer ($(@(Get-ChildItem (Join-Path $Stage 'lib') -Filter *.jar).Count) jars)"
