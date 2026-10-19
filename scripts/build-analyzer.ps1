#Requires -Version 7.4

param([switch]$AcceptModules, [switch]$Help)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$RepoRoot = Split-Path -Parent $PSScriptRoot
$AnalyzerDir = Join-Path $RepoRoot 'analyzer'
$Stage = Join-Path $RepoRoot 'src-tauri\resources\analyzer'
$ModulesFile = Join-Path $AnalyzerDir 'runtime-modules.txt'
$JavaRelease = '25'
$MainClass = 'br.pucpr.tcc.strata.analyzer.bootstrap.Main'
$SelfCheckTimeoutSeconds = 120
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$Usage = @'
build-analyzer.ps1: roda verify, jdeps e jlink e prepara o analisador para o `pnpm package` no Windows

  pwsh -NoProfile -File scripts/build-analyzer.ps1
  pwsh -NoProfile -File scripts/build-analyzer.ps1 -AcceptModules

Saída: 0 analisador preparado, 1 alguma verificação falhou, 2 erro de uso ou de ambiente
'@

function Stop-Build([string]$Message, [int]$Code = 1) {
  [Console]::Error.WriteLine("build-analyzer.ps1: $Message")
  exit $Code
}

function Write-Step([string]$Message) { Write-Host "build-analyzer: $Message" }

function Invoke-Native {
  param([string]$What, [string]$FilePath, [string[]]$ArgumentList, [int]$FailureCode = 1, [switch]$MergeStderr)
  try {
    if ($MergeStderr) {
      & $FilePath @ArgumentList 2>&1 | ForEach-Object { "$_" }
    } else {
      & $FilePath @ArgumentList
    }
  } catch [System.Management.Automation.NativeCommandExitException] {
    Stop-Build "$What saiu com $LASTEXITCODE" $FailureCode
  } catch [System.Management.Automation.CommandNotFoundException] {
    Stop-Build "não foi possível rodar ${FilePath}: não encontrado" 2
  }
  if ($LASTEXITCODE -ne 0) { Stop-Build "$What saiu com $LASTEXITCODE" $FailureCode }
}

function Invoke-WithClosedStdin([string]$FilePath, [string[]]$ArgumentList) {
  $info = [Diagnostics.ProcessStartInfo]::new($FilePath)
  foreach ($argument in $ArgumentList) { $info.ArgumentList.Add($argument) }
  $info.UseShellExecute = $false
  $info.RedirectStandardInput = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.StandardInputEncoding = $Utf8NoBom
  $info.StandardOutputEncoding = $Utf8NoBom
  $info.StandardErrorEncoding = $Utf8NoBom
  $process = [Diagnostics.Process]::Start($info)
  try {
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($SelfCheckTimeoutSeconds * 1000)) {
      $process.Kill($true)
      [Console]::Error.Write($stderr.GetAwaiter().GetResult())
      Stop-Build "$(Split-Path -Leaf $FilePath) não saiu em até $SelfCheckTimeoutSeconds s depois do EOF do stdin (o stderr dele está acima)"
    }
    return [pscustomobject]@{
      ExitCode = $process.ExitCode
      Stdout   = $stdout.GetAwaiter().GetResult()
      Stderr   = $stderr.GetAwaiter().GetResult()
    }
  } finally {
    $process.Dispose()
  }
}

function Get-JavaProperty([string]$Java, [string]$Name) {
  $pattern = '^\s*' + [regex]::Escape($Name) + ' = (.*)$'
  $settings = @('-XshowSettings:properties', '-version')
  $lines = @(Invoke-Native -What "$Java -XshowSettings" -FilePath $Java -ArgumentList $settings -FailureCode 2 -MergeStderr)
  foreach ($line in $lines) {
    if ($line -match $pattern) { return $Matches[1].Trim() }
  }
  return ''
}

function Resolve-Jdk {
  $jdk = $env:JAVA_HOME
  if (-not $jdk) {
    $java = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($java) { $jdk = Get-JavaProperty $java.Source 'java.home' }
  }
  if (-not $jdk) { Stop-Build "nenhum JDK encontrado: aponte JAVA_HOME para um JDK $JavaRelease" 2 }
  foreach ($tool in 'java', 'jdeps', 'jlink') {
    if (-not (Test-Path -LiteralPath (Join-Path $jdk "bin\$tool.exe") -PathType Leaf)) {
      Stop-Build "$jdk\bin\$tool.exe não existe; JAVA_HOME tem de ser um JDK completo, não um runtime" 2
    }
  }
  $version = Get-JavaProperty (Join-Path $jdk 'bin\java.exe') 'java.specification.version'
  if ($version -ne $JavaRelease) { Stop-Build "$jdk é Java $version; o analisador é feito para o Java $JavaRelease" 2 }
  return $jdk
}

function Get-AnalyzerJar {
  $jars = @(Get-ChildItem -LiteralPath (Join-Path $AnalyzerDir 'target') -File -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -match '^analyzer-.*\.jar$' -and $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' })
  if ($jars.Count -ne 1) { Stop-Build "era esperado exatamente um jar do analisador em analyzer\target, e há $($jars.Count)" }
  return $jars[0].FullName
}

function Read-ModulesFile {
  if (Test-Path -LiteralPath $ModulesFile -PathType Leaf) { Get-Content -LiteralPath $ModulesFile }
}

function Test-BaselineLine([string]$Line) { return -not ($Line -match '^(#|\+)' -or -not $Line.Trim()) }

function Get-BaselineModules([string[]]$Lines) {
  foreach ($line in $Lines) {
    if (Test-BaselineLine $line) { return $line }
  }
  return ''
}

function Get-ExtraModules {
  foreach ($line in @(Read-ModulesFile)) {
    $match = [regex]::Match($line, '^\+([A-Za-z0-9.]+)')
    if ($match.Success) { $match.Groups[1].Value }
  }
}

function Test-Modules([string]$Computed, [bool]$Accept) {
  $lines = @(Read-ModulesFile)
  $expected = Get-BaselineModules $lines
  # -ceq, e não -eq: o -eq do PowerShell ignora caixa, e o build-analyzer.sh não.
  if ($Computed -ceq $expected) {
    Write-Step "o conjunto de módulos confere com analyzer/runtime-modules.txt: $Computed"
    return
  }
  $shown = if ($expected) { $expected } else { '<nenhuma>' }
  if ($Accept) {
    $done = $false
    $rewritten = @(foreach ($line in $lines) {
        if (-not $done -and (Test-BaselineLine $line)) { $done = $true; $Computed } else { $line }
      })
    if (-not $done) { $rewritten = $rewritten + $Computed }
    [IO.File]::WriteAllText($ModulesFile, (($rewritten -join "`n") + "`n"), $Utf8NoBom)
    Write-Step "baseline de módulos regravada: $shown -> $Computed"
    return
  }
  Stop-Build @"
o conjunto de módulos do runtime mudou
  baseline (analyzer/runtime-modules.txt): $shown
  calculado pelo jdeps:                    $Computed
O analisador agora usa um recurso de biblioteca que precisa de outro módulo do JDK. Confira se
o novo conjunto é intencional e então rode: pwsh -NoProfile -File scripts/build-analyzer.ps1 -AcceptModules
e inclua a baseline no mesmo commit do código que causou a mudança.
"@
}

function Get-ModuleDeps([string]$Jdk, [string]$Jar) {
  $output = @(Invoke-Native -What 'jdeps' -FilePath (Join-Path $Jdk 'bin\jdeps.exe') -ArgumentList @(
      '--print-module-deps', '--ignore-missing-deps', '--multi-release', $JavaRelease,
      '--class-path', (Join-Path $AnalyzerDir 'target\lib\*'), $Jar))
  $lines = @($output | Where-Object { "$_".Trim() })
  if ($lines.Count -ne 1) {
    Stop-Build "o jdeps imprimiu $($lines.Count) linhas não vazias no stdout; era esperada exatamente uma lista de módulos"
  }
  return "$($lines[0])".Trim()
}

function Reset-Stage {
  if (-not $Stage.EndsWith('\src-tauri\resources\analyzer', [StringComparison]::OrdinalIgnoreCase)) {
    Stop-Build "recusando limpar um caminho de staging inesperado: $Stage" 2
  }
  if (Test-Path -LiteralPath $Stage) {
    try {
      Remove-Item -LiteralPath $Stage -Recurse -Force
    } catch {
      Stop-Build "não foi possível limpar ${Stage}: $($_.Exception.Message) Feche qualquer Strata ou analisador em execução que use o runtime preparado e rode de novo." 2
    }
  }
  New-Item -ItemType Directory -Path (Join-Path $Stage 'lib') | Out-Null
}

function Test-StagedRuntime([string[]]$Modules) {
  $java = Join-Path $Stage 'runtime\bin\java.exe'
  $listed = @(Invoke-Native -What 'java --list-modules' -FilePath $java -ArgumentList @('--list-modules') |
      ForEach-Object { ($_ -split '@')[0] })
  foreach ($module in $Modules) {
    if ($listed -cnotcontains $module) { Stop-Build "falta o módulo $module no runtime preparado" }
  }
  $scratch = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) "build-analyzer-$([guid]::NewGuid())")
  try {
    $classPath = (Join-Path $Stage 'analyzer.jar') + ';' + (Join-Path $Stage 'lib\*')
    $run = Invoke-WithClosedStdin $java @('-Xss2m', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
      '--enable-native-access=ALL-UNNAMED',
      '-cp', $classPath, $MainClass, "--data-dir=$(Join-Path $scratch.FullName 'data')")
    if ($run.ExitCode -ne 0) {
      [Console]::Error.Write($run.Stderr)
      Stop-Build "o runtime preparado saiu com $($run.ExitCode) no EOF do stdin (o stderr dele está acima)"
    }
    if (-not $run.Stdout.Contains('"event":"protocol.ready"')) {
      [Console]::Error.Write($run.Stderr)
      Stop-Build 'o runtime preparado não imprimiu protocol.ready (o stderr dele está acima)'
    }
  } finally {
    Remove-Item -LiteralPath $scratch.FullName -Recurse -Force -ErrorAction Continue
  }
  Write-Step "o runtime preparado tem os $($Modules.Count) módulos e responde ao handshake"
}

if ($Help) {
  [Console]::Out.WriteLine($Usage)
  exit 0
}
if ($args.Count -gt 0) {
  [Console]::Error.WriteLine("build-analyzer.ps1: argumento desconhecido: $($args -join ' ')")
  [Console]::Error.WriteLine($Usage)
  exit 2
}
if (-not $IsWindows) { Stop-Build 'no macOS e no Linux, use scripts/build-analyzer.sh' 2 }

$jdk = Resolve-Jdk
Write-Step "JDK $(Get-JavaProperty (Join-Path $jdk 'bin\java.exe') 'java.runtime.version') em $jdk"

Write-Step 'mvnw verify'
Push-Location -LiteralPath $AnalyzerDir
try {
  $env:JAVA_HOME = $jdk
  Invoke-Native -What 'mvnw verify' -FilePath (Join-Path $AnalyzerDir 'mvnw.cmd') -ArgumentList @('-q', 'verify')
} finally {
  Pop-Location
}
$jar = Get-AnalyzerJar

$modules = Get-ModuleDeps $jdk $jar
Test-Modules -Computed $modules -Accept $AcceptModules.IsPresent
$extras = @(Get-ExtraModules)
if ($extras.Count -gt 0) {
  Write-Step "módulos que o jdeps não enxerga, tirados de analyzer/runtime-modules.txt: $($extras -join ',')"
}
$allModules = @($modules -split ',') + $extras
$moduleList = $allModules -join ','

Reset-Stage

$runtimeDir = Join-Path $Stage 'runtime'
Write-Step "jlink --add-modules $moduleList"
Invoke-Native -What 'jlink' -FilePath (Join-Path $jdk 'bin\jlink.exe') -ArgumentList @(
  '--add-modules', $moduleList, '--strip-debug', '--no-header-files',
  '--no-man-pages', '--compress=zip-6', '--output', $runtimeDir)
Get-ChildItem -LiteralPath $runtimeDir -Recurse -File |
  Where-Object IsReadOnly | ForEach-Object { $_.IsReadOnly = $false }
Copy-Item -LiteralPath $jar -Destination (Join-Path $Stage 'analyzer.jar')
Copy-Item -Path (Join-Path $AnalyzerDir 'target\lib\*.jar') -Destination (Join-Path $Stage 'lib')

Test-StagedRuntime $allModules

$stagedJars = @(Get-ChildItem -LiteralPath (Join-Path $Stage 'lib') -File | Where-Object Extension -eq '.jar')
$stagedBytes = (Get-ChildItem -LiteralPath $Stage -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Step ('preparado: {0} MiB em src-tauri\resources\analyzer ({1} jars)' -f [math]::Round($stagedBytes / 1MB), $stagedJars.Count)
exit 0
