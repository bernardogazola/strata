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
$ShutdownExitMilliseconds = 4000
$AnalysisStages = @('REPOSITORY_VALIDATION', 'LAYOUT_DISCOVERY', 'SOURCE_ANALYSIS', 'GIT_HISTORY_CHEAP',
  'STRUCTURAL_GRAPH', 'GIT_HISTORY_DETAILED', 'EVOLUTIONARY_COUPLING', 'HOTSPOT_PROFILES', 'GUIDED_TOUR',
  'PERSISTENCE')
$TerminalEvents = @('analysis.completed', 'analysis.cancelled', 'analysis.failed')
$SelectedRootKinds = @('MAIN', 'GENERATED', 'TEST')
$SelfCheckFixture = 'fixtures/repositories/multi-module'
$SelfCheckSidecars = @('expected.json', 'README.md', 'cloc-golden.txt')
$FixtureName = 'Strata Fixture'
$FixtureEmail = 'fixtures@strata.invalid'
$FixtureDate = '2024-01-02T03:04:05+0000'
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

function Get-FinalPath([string]$Directory) {
  if (-not ('StrataBuild.Native' -as [type])) {
    Add-Type -Namespace StrataBuild -Name Native -MemberDefinition @'
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern Microsoft.Win32.SafeHandles.SafeFileHandle CreateFileW(
  string name, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern uint GetFinalPathNameByHandleW(
  Microsoft.Win32.SafeHandles.SafeFileHandle handle, System.Text.StringBuilder path, uint size, uint flags);
'@
  }
  $openExisting = 3; $backupSemantics = 0x02000000; $shareAll = 7
  $handle = [StrataBuild.Native]::CreateFileW($Directory, 0, $shareAll, [IntPtr]::Zero, $openExisting, $backupSemantics, [IntPtr]::Zero)
  if ($handle.IsInvalid) { Stop-Build "não foi possível abrir $Directory para obter o caminho canônico" 2 }
  try {
    $buffer = [Text.StringBuilder]::new(32768)
    $length = [StrataBuild.Native]::GetFinalPathNameByHandleW($handle, $buffer, $buffer.Capacity, 0)
    if ($length -eq 0 -or $length -ge $buffer.Capacity) { Stop-Build "não foi possível obter o caminho canônico de $Directory" 2 }
    $path = $buffer.ToString()
  } finally {
    $handle.Dispose()
  }
  if ($path.StartsWith('\\?\UNC\', [StringComparison]::Ordinal)) { return '\\' + $path.Substring(8) }
  if ($path.StartsWith('\\?\', [StringComparison]::Ordinal)) { return $path.Substring(4) }
  return $path
}

function Invoke-HermeticGit([string]$Directory, [string[]]$GitArguments, [string]$EmptyConfig) {
  $variables = @{
    GIT_CONFIG_GLOBAL = $EmptyConfig; GIT_CONFIG_SYSTEM = $EmptyConfig; GIT_CONFIG_NOSYSTEM = '1'
    GIT_AUTHOR_NAME = $FixtureName; GIT_AUTHOR_EMAIL = $FixtureEmail
    GIT_COMMITTER_NAME = $FixtureName; GIT_COMMITTER_EMAIL = $FixtureEmail
    GIT_AUTHOR_DATE = $FixtureDate; GIT_COMMITTER_DATE = $FixtureDate; EMAIL = $null
  }
  $saved = @{}
  foreach ($name in $variables.Keys) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $variables[$name])
  }
  try {
    Invoke-Native -What "git $($GitArguments[0])" -FilePath 'git' -ArgumentList (@('-C', $Directory,
        '-c', "core.excludesFile=$EmptyConfig", '-c', "core.attributesFile=$EmptyConfig",
        '-c', 'core.autocrlf=false', '-c', 'core.eol=lf', '-c', 'commit.gpgsign=false',
        '-c', 'init.defaultBranch=main') + $GitArguments) | Out-Null
  } finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
  }
}

function New-ScratchRepository([string]$Scratch) {
  $target = Join-Path $Scratch 'repository'
  $emptyConfig = Join-Path $Scratch 'empty.gitconfig'
  [IO.File]::WriteAllText($emptyConfig, '')
  $listed = @(Invoke-Native -What 'git ls-files' -FilePath 'git' -ArgumentList @('-C', $RepoRoot, 'ls-files', '-z', '--', $SelfCheckFixture)) -join ''
  $copied = 0
  foreach ($file in @($listed -split "`0" | Where-Object { $_ })) {
    $relative = $file.Substring($SelfCheckFixture.Length + 1)
    if ($SelfCheckSidecars -ccontains $relative) { continue }
    $destination = Join-Path $target ($relative -replace '/', '\')
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath (Join-Path $RepoRoot ($file -replace '/', '\')) -Destination $destination
    if ($relative.EndsWith('.java', [StringComparison]::Ordinal)) { $copied++ }
  }
  if ($copied -eq 0) { Stop-Build "nenhum .java versionado em $SelfCheckFixture" }
  Invoke-HermeticGit $target @('init', '-q') $emptyConfig
  Invoke-HermeticGit $target @('add', '-A') $emptyConfig
  Invoke-HermeticGit $target @('commit', '-q', '--no-verify', '-m', 'build-analyzer self-check') $emptyConfig
  return Get-FinalPath $target
}

function Start-Analyzer([string]$Java, [string]$ClassPath, [string]$DataDir) {
  $info = [Diagnostics.ProcessStartInfo]::new($Java)
  foreach ($argument in @('-Xmx2g', '-XX:+ExitOnOutOfMemoryError', '-Xss2m', '-Dfile.encoding=UTF-8',
      '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Duser.language=en', '-Duser.country=US',
      '-Djava.awt.headless=true', '--enable-native-access=ALL-UNNAMED',
      "-Djava.io.tmpdir=$DataDir", "-Dorg.sqlite.tmpdir=$DataDir",
      '-cp', $ClassPath, $MainClass, "--data-dir=$DataDir")) { $info.ArgumentList.Add($argument) }
  foreach ($name in @('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')) {
    $info.Environment.Remove($name) | Out-Null
  }
  $info.UseShellExecute = $false
  $info.WorkingDirectory = $DataDir
  $info.RedirectStandardInput = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.StandardInputEncoding = $Utf8NoBom
  $info.StandardOutputEncoding = $Utf8NoBom
  $info.StandardErrorEncoding = $Utf8NoBom
  $process = [Diagnostics.Process]::Start($info)
  $process.StandardInput.NewLine = "`n"
  $process.StandardInput.AutoFlush = $true
  return [pscustomobject]@{ Process = $process; Stderr = $process.StandardError.ReadToEndAsync(); Pending = $null; Next = 0 }
}

function Stop-Analysis($Session, [string]$Message) {
  if (-not $Session.Process.HasExited) {
    $Session.Process.Kill($true)
    $Session.Process.WaitForExit(5000) | Out-Null
  }
  [Console]::Error.Write($Session.Stderr.GetAwaiter().GetResult())
  Stop-Build "o runtime preparado não concluiu uma análise: $Message (o stderr dele está acima)"
}

function Read-Frame($Session, [datetime]$Deadline, [string]$Waiting) {
  if (-not $Session.Pending) { $Session.Pending = $Session.Process.StandardOutput.ReadLineAsync() }
  $remaining = [int][Math]::Max(0, ($Deadline - [datetime]::UtcNow).TotalMilliseconds)
  if (-not $Session.Pending.Wait($remaining)) { Stop-Analysis $Session "sem $Waiting (prazo esgotado)" }
  $line = $Session.Pending.Result
  $Session.Pending = $null
  if ($null -eq $line) { return $null }
  try {
    return ConvertFrom-Json -InputObject $line -AsHashtable
  } catch {
    Stop-Analysis $Session "uma linha do stdout que não é um frame JSON: $($line.Substring(0, [Math]::Min(200, $line.Length)))"
  }
}

function Get-FrameName($Frame) {
  if ($Frame.kind -ceq 'event') { return "$($Frame.event)" }
  return "um frame $($Frame.kind)"
}

function Get-ErrorCode($Payload) {
  if ($Payload -isnot [Collections.IDictionary] -or -not $Payload.Contains('error')) { return '' }
  $failure = $Payload['error']
  if ($failure -isnot [Collections.IDictionary]) { return '' }
  return "$($failure['code'])"
}

function Send-Request($Session, [string]$Method, $Payload, [string]$RepositoryPath) {
  $Session.Next++
  $id = 'req_' + $Session.Next.ToString('D26')
  $frame = [ordered]@{ id = $id; kind = 'request'; method = $Method; payload = $Payload; protocol = 1 }
  if ($RepositoryPath) { $frame.repositoryPath = $RepositoryPath }
  $Session.Process.StandardInput.WriteLine(($frame | ConvertTo-Json -Compress -Depth 5))
  return $id
}

function Wait-Response($Session, [string]$Id, [datetime]$Deadline, [string]$Method, [string]$Terminal = '') {
  while ($true) {
    $frame = Read-Frame $Session $Deadline "resposta a $Method"
    if ($null -eq $frame) { Stop-Analysis $Session "o analisador fechou o stdout antes de responder a $Method" }
    if ($frame.kind -ceq 'response' -and $frame.id -ceq $Id) { return $frame }
    if ($Terminal) { Stop-Analysis $Session "$(Get-FrameName $frame) chegou depois do evento terminal $Terminal" }
    Stop-Analysis $Session "$(Get-FrameName $frame) chegou antes da resposta a $Method"
  }
}

function Get-SelectedJavaFiles($Described) {
  $total = 0
  foreach ($root in @($Described.payload.sourceRoots)) {
    if ($SelectedRootKinds -ccontains "$($root.kind)") { $total += [long]$root.javaFileCount }
  }
  return $total
}

function Test-Tick($Session, $Run, $Tick) {
  $stage = "$($Tick.stage)"
  if ($Run.Stages.Contains($stage)) { Stop-Analysis $Session "um tick de $stage depois do analysis.stage.completed da etapa" }
  if ($stage -cne 'SOURCE_ANALYSIS') { return }
  if ("$($Tick.unit)" -cne 'JAVA_FILES') { Stop-Analysis $Session "um tick de SOURCE_ANALYSIS conta $($Tick.unit), e não JAVA_FILES" }
  $completed = $Tick.completed
  $total = $Tick.total
  if ($null -eq $completed -or $null -eq $total) { Stop-Analysis $Session 'um tick de SOURCE_ANALYSIS sem completed e total' }
  if ($null -ne $Run.SourceTotal -and $total -ne $Run.SourceTotal) { Stop-Analysis $Session "o total de SOURCE_ANALYSIS mudou: $($Run.SourceTotal) -> $total" }
  if ($null -ne $Run.SourceLast -and $completed -lt $Run.SourceLast) { Stop-Analysis $Session "o progresso de SOURCE_ANALYSIS voltou: $($Run.SourceLast) -> $completed" }
  if ($completed -gt $total) { Stop-Analysis $Session "um tick de SOURCE_ANALYSIS além do total: $completed de $total" }
  $Run.SourceLast = $completed
  $Run.SourceTotal = $total
}

function Add-CompletedStage($Session, $Run, $Payload) {
  $stage = "$($Payload.stage)"
  $status = "$($Payload.status)"
  if ($AnalysisStages -cnotcontains $stage) { Stop-Analysis $Session "analysis.stage.completed de uma etapa desconhecida '$stage'" }
  if ($Run.Stages.Contains($stage)) { Stop-Analysis $Session "um segundo analysis.stage.completed para $stage" }
  $Run.Stages.Add($stage)
  if (@('COMPLETED', 'SKIPPED') -cnotcontains $status) { Stop-Analysis $Session "$stage terminou com $status" }
}

function Read-AnalysisRun($Session, [string]$AnalysisId, [datetime]$Deadline) {
  $run = [pscustomobject]@{
    Started = $false; Stages = [Collections.Generic.List[string]]::new()
    SourceLast = $null; SourceTotal = $null; Terminal = $null
  }
  while ($null -eq $run.Terminal) {
    $frame = Read-Frame $Session $Deadline 'evento terminal da análise'
    if ($null -eq $frame) { Stop-Analysis $Session 'o analisador fechou o stdout no meio da análise' }
    if ($frame.kind -cne 'event') { Stop-Analysis $Session "$(Get-FrameName $frame) que ninguém pediu, no meio da análise" }
    $name = "$($frame.event)"
    $payload = $frame.payload
    if ($name.StartsWith('analysis.', [StringComparison]::Ordinal) -and "$($payload.analysisId)" -cne $AnalysisId) {
      Stop-Analysis $Session "$name traz analysisId '$($payload.analysisId)', e não $AnalysisId"
    }
    if (-not $run.Started -and $name -cne 'analysis.started') { Stop-Analysis $Session "$name chegou antes de analysis.started" }
    if ($name -ceq 'analysis.started') {
      if ($run.Started) { Stop-Analysis $Session 'um segundo analysis.started' }
      $run.Started = $true
    } elseif ($name -ceq 'analysis.progress') {
      Test-Tick $Session $run $payload
    } elseif ($name -ceq 'analysis.stage.completed') {
      Add-CompletedStage $Session $run $payload
    } elseif ($TerminalEvents -ccontains $name) {
      $run.Terminal = $frame
    } elseif (@('analysis.warning', 'cache.invalidated') -cnotcontains $name) {
      Stop-Analysis $Session "um frame inesperado durante a análise: $name"
    }
  }
  return $run
}

function Assert-CompletedRun($Session, $Run, [long]$JavaFiles) {
  $terminal = $Run.Terminal
  if ($terminal.event -cne 'analysis.completed') {
    Stop-Analysis $Session "a análise terminou com $($terminal.event) $(Get-ErrorCode $terminal.payload)"
  }
  $stages = $Run.Stages
  if ($stages.Count -ne $AnalysisStages.Count) {
    Stop-Analysis $Session "$($stages.Count) analysis.stage.completed, e eram esperados exatamente dez: $($stages -join ' ')"
  }
  $expected = @($AnalysisStages | Where-Object { $_ -cne 'GIT_HISTORY_CHEAP' }) -join ' '
  $ordered = @($stages | Where-Object { $_ -cne 'GIT_HISTORY_CHEAP' }) -join ' '
  if ($ordered -cne $expected) { Stop-Analysis $Session "as etapas terminaram fora da ordem de AnalysisStage: $($stages -join ' ')" }
  if ($stages.IndexOf('GIT_HISTORY_CHEAP') -lt $stages.IndexOf('LAYOUT_DISCOVERY')) {
    Stop-Analysis $Session "GIT_HISTORY_CHEAP terminou antes de LAYOUT_DISCOVERY: $($stages -join ' ')"
  }
  $totalFiles = $terminal.payload.completeness.totalFiles
  if ($totalFiles -ne $JavaFiles) {
    Stop-Analysis $Session "completeness.totalFiles é $totalFiles, mas o describe da mesma sessão conta $JavaFiles nas raízes selecionadas"
  }
  if ($Run.SourceLast -ne $totalFiles -or $Run.SourceTotal -ne $totalFiles) {
    Stop-Analysis $Session "os ticks de SOURCE_ANALYSIS terminam em $($Run.SourceLast) de $($Run.SourceTotal), totalFiles $totalFiles"
  }
}

function Stop-AnalyzerCleanly($Session, [datetime]$Deadline, [string]$Terminal) {
  $exitBy = [datetime]::UtcNow.AddMilliseconds($ShutdownExitMilliseconds)
  $id = Send-Request $Session 'system.shutdown' @{} $null
  Wait-Response $Session $id $Deadline 'system.shutdown' $Terminal | Out-Null
  $Session.Process.StandardInput.Close()
  while ($true) {
    $frame = Read-Frame $Session $exitBy 'fim do stdout em até 4 s depois do system.shutdown'
    if ($null -eq $frame) { break }
    Stop-Analysis $Session "$(Get-FrameName $frame) chegou depois da resposta ao system.shutdown"
  }
  $left = [int][Math]::Max(0, ($exitBy - [datetime]::UtcNow).TotalMilliseconds)
  if (-not $Session.Process.WaitForExit($left)) { Stop-Analysis $Session 'o analisador não saiu em até 4 s depois do system.shutdown' }
  if ($Session.Process.ExitCode -ne 0) { Stop-Analysis $Session "saída $($Session.Process.ExitCode) depois do system.shutdown" }
}

function Test-StagedAnalysis([string]$Java, [string]$ClassPath, [string]$Scratch) {
  $repository = New-ScratchRepository $Scratch
  $dataDir = Join-Path $Scratch 'analysis-data'
  New-Item -ItemType Directory -Path $dataDir | Out-Null
  $session = Start-Analyzer $Java $ClassPath $dataDir
  $deadline = [datetime]::UtcNow.AddSeconds($SelfCheckTimeoutSeconds)
  try {
    $ready = Read-Frame $session $deadline 'protocol.ready'
    if ($null -eq $ready -or $ready.event -cne 'protocol.ready') { Stop-Analysis $session 'o primeiro frame não é protocol.ready' }

    $id = Send-Request $session 'repository.describe' @{} $repository
    $described = Wait-Response $session $id $deadline 'repository.describe'
    if (-not $described.ok) { Stop-Analysis $session "repository.describe recusado com $(Get-ErrorCode $described)" }
    $javaFiles = Get-SelectedJavaFiles $described

    $config = [ordered]@{ historyScope = 'ALL'; historyScopeCommits = $null; includeTests = $true; rebuildCache = $false; sourceRootIds = $null }
    $id = Send-Request $session 'analysis.start' $config $repository
    $started = Wait-Response $session $id $deadline 'analysis.start'
    if (-not $started.ok) {
      $code = Get-ErrorCode $started
      if ($code -ceq 'PROTO_UNKNOWN_METHOD') { Stop-Analysis $session 'analysis.start desconhecido por este analisador (PROTO_UNKNOWN_METHOD)' }
      Stop-Analysis $session "analysis.start recusado com $code"
    }
    $analysisId = "$($started.payload.analysisId)"
    if ($analysisId -cnotmatch '^an_[0-7][0-9A-HJKMNP-TV-Z]{25}$') { Stop-Analysis $session "analysis.start não respondeu com um analysisId válido: '$analysisId'" }
    if ($started.payload.reusedCache -ne $false) { Stop-Analysis $session 'reusedCache não é false em uma pasta de dados nova' }

    $run = Read-AnalysisRun $session $analysisId $deadline
    Assert-CompletedRun $session $run $javaFiles
    Stop-AnalyzerCleanly $session $deadline $run.Terminal.event
  } finally {
    if (-not $session.Process.HasExited) {
      $session.Process.Kill($true)
      $session.Process.WaitForExit(5000) | Out-Null
    }
    $session.Process.Dispose()
  }
  Write-Step "o runtime preparado conclui uma análise da fixture multi-module ($javaFiles arquivos .java, $($AnalysisStages.Count) etapas)"
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
      '-Djava.awt.headless=true', '--enable-native-access=ALL-UNNAMED',
      '-cp', $classPath, $MainClass, "--data-dir=$(Join-Path $scratch.FullName 'data')")
    if ($run.ExitCode -ne 0) {
      [Console]::Error.Write($run.Stderr)
      Stop-Build "o runtime preparado saiu com $($run.ExitCode) no EOF do stdin (o stderr dele está acima)"
    }
    if (-not $run.Stdout.Contains('"event":"protocol.ready"')) {
      [Console]::Error.Write($run.Stderr)
      Stop-Build 'o runtime preparado não imprimiu protocol.ready (o stderr dele está acima)'
    }
    Write-Step "o runtime preparado tem os $($Modules.Count) módulos e responde ao handshake"
    Test-StagedAnalysis $java $classPath $scratch.FullName
  } finally {
    Remove-Item -LiteralPath $scratch.FullName -Recurse -Force -ErrorAction Continue
  }
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
