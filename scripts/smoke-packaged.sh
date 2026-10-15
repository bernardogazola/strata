#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MAIN_CLASS="br.pucpr.tcc.strata.analyzer.bootstrap.Main"
APP_IDENTIFIER="br.pucpr.tcc.strata"
READY_BUDGET_MS=1500
CRASH_BUDGET_MS=2000
STARTUP_WAIT_MS=60000
QUIT_WAIT_MS=5000
WARM_RUNS=5
POLL_SECONDS=0.025
RECORD_FILE="$REPO_ROOT/benchmarks/startup.json"

PROBLEMS=0
CI_MODE="no"
WORK=""
MOUNT=""
APP_PID=""
APP_WINPID=""
OS_KIND=""
OS_LABEL=""
JAVA_NAME=""
KILL_LABEL=""
QUIT_SIGNAL=""
CRASH_EXIT_CODE=""
ARTIFACT=""
APP_EXE=""
RESOURCES=""
LAUNCH_MS=""
ANALYZER_PID=""
ANALYZER_EXE=""
ANALYZER_ARGS=""
FIRST_MS=""
SHELL_READY_MS=""
WARM_MS=""
CRASH_MS=""
EXIT_CODE=""
COMPLETED="no"

usage() {
  cat <<'USAGE'
smoke-packaged.sh: instala o app empacotado, abre o app e prova que o runtime empacotado responde

  bash scripts/smoke-packaged.sh                        mede e grava benchmarks/startup.json
  bash scripts/smoke-packaged.sh --no-record            mede sem gravar nada
  bash scripts/smoke-packaged.sh --ci                   runner de CI: abertura -> READY vira medida,
                                                        e benchmarks/startup.json nunca é gravado
  bash scripts/smoke-packaged.sh --record-to <arquivo>  grava em <arquivo> no lugar do padrão (também com --ci)
  bash scripts/smoke-packaged.sh --self-test
  xvfb-run -a bash scripts/smoke-packaged.sh --ci       o Linux precisa de um display

Artefatos: bundle/dmg/*.dmg (macOS) · bundle/deb/*.deb (Linux) · bundle/nsis/*-setup.exe (Windows,
Git Bash, só com --ci: a execução apaga os dados do Strata do usuário e instala por usuário)

Saída: 0 tudo certo · 1 alguma verificação falhou · 2 erro de uso ou de ambiente
USAGE
}

bad() { echo "  falha  $1"; PROBLEMS=$((PROBLEMS + 1)); }
good() { echo "  ok     $1"; }
measure() { echo "  medida  $1"; }
die() { echo "smoke-packaged.sh: $1" >&2; exit 2; }

case "$(date +%3N 2>/dev/null)" in
  [0-9][0-9][0-9]) now_ms() { date +%s%3N; } ;;
  *) now_ms() { /usr/bin/perl -MTime::HiRes=time -e 'printf "%d\n", time() * 1000'; } ;;
esac

lower() { printf '%s\n' "$1" | tr '[:upper:]' '[:lower:]'; }

canonical() {
  local dir
  dir="$(dirname -- "$1")"
  if [ -d "$dir" ]; then
    printf '%s/%s\n' "$(CDPATH= cd -P -- "$dir" && pwd)" "$(basename -- "$1")"
  else
    printf '%s\n' "$1"
  fi
}

report_line() {
  local file="$1" source="$2" phase="$3" phase_pattern
  if [ "$phase" = "*" ]; then phase_pattern='"phase":"'; else phase_pattern="\"phase\":\"$phase\""; fi
  grep "\"source\":\"$source\"" "$file" 2>/dev/null | grep -m1 "$phase_pattern" || return 1
}

report_field() {
  local line
  line="$(report_line "$1" "$2" "$3")" || return 1
  printf '%s\n' "$line" | sed -n "s/.*\"$4\":\([^,}]*\).*/\1/p"
}

wait_for_line() {
  local file="$1" source="$2" phase="$3" timeout_ms="$4" deadline value
  deadline=$(( $(now_ms) + timeout_ms ))
  while :; do
    if value="$(report_field "$file" "$source" "$phase" unixMs)" && [ -n "$value" ]; then
      printf '%s\n' "$value"
      return 0
    fi
    [ "$(now_ms)" -lt "$deadline" ] || return 1
    sleep "$POLL_SECONDS"
  done
}

later_of() { if [ "$1" -gt "$2" ]; then echo "$1"; else echo "$2"; fi; }

check_budget() {
  local label="$1" measured="$2" budget="$3"
  if [ "$measured" -lt "$budget" ]; then
    good "$label: ${measured} ms (< ${budget} ms)"
  else
    bad "$label: ${measured} ms, e o limite é ${budget} ms"
  fi
}

check_ready() {
  local label="$1" measured="$2"
  if [ "$CI_MODE" = "yes" ]; then
    measure "$label -> READY: ${measured} ms (meta < ${READY_BUDGET_MS} ms)"
  else
    check_budget "$label -> READY" "$measured" "$READY_BUDGET_MS"
  fi
}

check_executables() {
  local found="$1" expected="$2" names
  names="$(printf '%s\n' "$found" | sed '/^$/d' | sort | paste -sd, -)"
  if [ "$names" = "$expected" ]; then
    good "o artefato traz exatamente o executável do aplicativo ($expected)"
  else
    bad "o artefato traz os executáveis [$names], e era esperado exatamente [$expected]"
  fi
}

same_file() {
  local actual="$1" expected="$2" compare="$3"
  if [ "$compare" = "nocase" ]; then
    [ "$(lower "$actual")" = "$(lower "$expected")" ]
  else
    [ "$(canonical "$actual")" = "$expected" ]
  fi
}

check_analyzer_exe() {
  local actual="$1" expected="$2" compare="${3:-exact}"
  if same_file "$actual" "$expected" "$compare"; then
    good "o executável do analisador é o ${expected##*[/\\]} do runtime empacotado"
  else
    bad "o analisador roda '$actual', e não o '$expected' empacotado"
  fi
}

check_bundled_jvm() {
  local mapped="$1" expected="$2" library="$3" compare="${4:-exact}" path name
  while IFS= read -r path; do
    name="${path##*[/\\]}"
    if [ "$compare" = "nocase" ]; then name="$(lower "$name")"; fi
    [ "$name" = "$library" ] || continue
    if same_file "$path" "$expected" "$compare"; then
      good "a JVM do analisador é a do runtime empacotado ($library mapeada do bundle)"
    else
      bad "o analisador mapeia '$path', e não a $library empacotada"
    fi
    return 0
  done <<<"$mapped"
  bad "o processo do analisador não mapeia nenhuma $library"
}

check_jvm_flags() {
  local args="$1"
  case "$args" in
    *-XX:+ExitOnOutOfMemoryError*) good "a JVM sai em OutOfMemoryError" ;;
    *) bad "a JVM foi iniciada sem -XX:+ExitOnOutOfMemoryError" ;;
  esac
  case "$args" in
    *HeapDumpOnOutOfMemoryError*) bad "o heap dump em OOM está ativo; ele levaria código-fonte" ;;
    *) good "sem heap dump em OOM" ;;
  esac
}

names_utf8() {
  local codeset="${1%%@*}"
  case "$codeset" in *.*) codeset="$(lower "${codeset##*.}")" ;; *) return 1 ;; esac
  [ "$codeset" = "utf-8" ] || [ "$codeset" = "utf8" ]
}

check_child_environ() {
  local environ="$1" args="$2" names lang
  names="$(printf '%s\n' "$environ" | sed -n 's/^\([^=]*\)=.*/\1/p' | sort | paste -sd, -)"
  if [ "$names" = "HOME,LANG,TMPDIR" ]; then
    good "o ambiente do analisador tem exatamente HOME, LANG e TMPDIR"
  else
    bad "o ambiente do analisador tem [$names], e era esperado exatamente HOME,LANG,TMPDIR"
  fi
  lang="$(printf '%s\n' "$environ" | sed -n 's/^LANG=//p')"
  if names_utf8 "$lang"; then
    good "o locale do analisador indica um codeset UTF-8 (LANG=$lang)"
  else
    bad "o locale do analisador é LANG='$lang', que não é um codeset UTF-8"
  fi
  case "$environ $args" in
    *poison*) bad "o JAVA_TOOL_OPTIONS/_JAVA_OPTIONS envenenado do processo pai chegou ao analisador" ;;
    *) good "nada das variáveis de opção da JVM do processo pai chegou ao analisador" ;;
  esac
}

check_crashed_line() {
  local line="$1" expected="$2" fields code
  fields="$(printf '%s\n' "$line" | grep -o '"[A-Za-z]*":' | tr -d '":' | sort | paste -sd, -)"
  if [ "$fields" = "elapsedMs,exitCode,phase,source,unixMs" ]; then
    good "CRASHED traz só o código de saída"
  else
    bad "CRASHED traz os campos [$fields], e era esperado só o código de saída"
  fi
  code="$(printf '%s\n' "$line" | sed -n 's/.*"exitCode":\([^,}]*\).*/\1/p')"
  if [ "$code" = "$expected" ]; then
    good "o código de saída é $expected, como o sistema operacional informa esta saída forçada, sem nada inventado"
  else
    bad "a saída forçada apareceu com exitCode '${code:-ausente}', e era esperado $expected: $line"
  fi
}

find_artifact() {
  local dir="$1" pattern="$2" found
  # shellcheck disable=SC2206
  found=("$dir"/$pattern)
  [ -e "${found[0]}" ] || die "nenhum $pattern em ${dir#"$REPO_ROOT"/}; rode 'pnpm package' antes"
  [ "${#found[@]}" -eq 1 ] || die "${#found[@]} arquivos $pattern em ${dir#"$REPO_ROOT"/}; era esperado exatamente um"
  printf '%s\n' "${found[0]}"
}

json_string() {
  local value="${1//\\/\\\\}"
  printf '"%s"' "${value//\"/\\\"}"
}

json_number() { case "$1" in '' | *[!0-9]*) printf 'null' ;; *) printf '%s' "$1" ;; esac; }

median_of() {
  printf '%s\n' "$@" | sort -n | awk '{ v[NR] = $1 } END { print (NR % 2) ? v[(NR + 1) / 2] : int((v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}

max_of() { printf '%s\n' "$@" | sort -n | tail -1; }

self_test() {
  local dir report failures=0 saved_ci="$CI_MODE"
  dir="$(mktemp -d "${TMPDIR:-/tmp}/smoke-packaged-self-test.XXXXXX")"
  report="$dir/report.jsonl"
  printf '%s\n' \
    '{"phase":"STARTING","source":"event","elapsedMs":3,"unixMs":1000}' \
    '{"phase":"READY","protocolVersion":1,"source":"event","elapsedMs":410,"unixMs":1410}' \
    '{"phase":"READY","protocolVersion":1,"source":"query","elapsedMs":520,"unixMs":1520}' \
    '{"phase":"CRASHED","exitCode":null,"source":"event","elapsedMs":900,"unixMs":1900}' > "$report"

  expect() {
    if [ "$2" = "$3" ]; then echo "  ok     $1"; else echo "  falha  $1 (obtido '$2', esperado '$3')"; failures=$((failures + 1)); fi
  }
  status_of() { if ( "$@" ) >/dev/null 2>&1; then echo 0; else echo "$?"; fi; }

  expect "o unixMs do evento READY é lido" "$(report_field "$report" event READY unixMs)" "1410"
  expect "a linha da consulta é distinguida da do evento" "$(report_field "$report" query '*' unixMs)" "1520"
  expect "a versão do protocolo é lida" "$(report_field "$report" event READY protocolVersion)" "1"
  expect "um código de saída null é lido como null" "$(report_field "$report" event CRASHED exitCode)" "null"
  expect "uma fase ausente é erro" "$(report_field "$report" event INCOMPATIBLE unixMs || echo ausente)" "ausente"
  expect "esperar uma linha ausente estoura o tempo" "$(wait_for_line "$report" query CRASHED 60 || echo timeout)" "timeout"
  expect "READY é o mais tardio entre evento e consulta" "$(later_of 1410 1520)" "1520"
  grep -v '"CRASHED"' "$report" > "$dir/ready.jsonl"
  expect "ready_of lê a mais tardia das duas linhas" "$(ready_of "$dir/ready.jsonl")" "1520"
  printf '%s\n' '{"phase":"STARTING","source":"event","elapsedMs":3,"unixMs":1000}' \
    '{"phase":"CRASHED","exitCode":null,"source":"event","elapsedMs":10003,"unixMs":11000}' > "$dir/crashed.jsonl"
  local started_ms
  started_ms="$(now_ms)"
  expect "um CRASHED antes de READY encerra a espera" "$(ready_of "$dir/crashed.jsonl" || echo recusado)" "recusado"
  expect "...na hora, não depois da espera de inicialização" "$([ $(( $(now_ms) - started_ms )) -lt 5000 ] && echo rápido || echo lento)" "rápido"
  expect "uma sonda que falhou é gravada como null" "$(json_number 'n/a')" "null"

  PROBLEMS=0; check_budget "acima do limite" 1500 1500 >/dev/null
  expect "uma execução no limite falha" "$PROBLEMS" "1"
  CI_MODE="yes"; PROBLEMS=0; check_ready "primeira abertura" 2400 >"$dir/measure.out"
  expect "com --ci, uma abertura acima da meta não é falha" "$PROBLEMS" "0"
  expect "com --ci, a abertura é mostrada como medida" "$(cat "$dir/measure.out")" "  medida  primeira abertura -> READY: 2400 ms (meta < 1500 ms)"
  CI_MODE="no"; PROBLEMS=0; check_ready "primeira abertura" 2400 >/dev/null
  expect "sem --ci, a mesma abertura falha" "$PROBLEMS" "1"
  CI_MODE="$saved_ci"

  mkdir -p "$dir/none" "$dir/one" "$dir/two" "$dir/nsis"
  : >"$dir/one/Strata_0.1.0_amd64.deb"; : >"$dir/two/a.deb"; : >"$dir/two/b.deb"
  : >"$dir/nsis/Strata_0.1.0_x64-setup.exe"; : >"$dir/nsis/stray.exe"
  expect "nenhum artefato é recusado como erro de ambiente" "$(status_of find_artifact "$dir/none" '*.deb')" "2"
  expect "dois artefatos são recusados como ambíguos" "$(status_of find_artifact "$dir/two" '*.deb')" "2"
  expect "exatamente um artefato é encontrado" "$(find_artifact "$dir/one" '*.deb')" "$dir/one/Strata_0.1.0_amd64.deb"
  expect "só o setup NSIS casa com o padrão do instalador" "$(find_artifact "$dir/nsis" '*-setup.exe')" "$dir/nsis/Strata_0.1.0_x64-setup.exe"

  PROBLEMS=0; check_executables $'strata\nstub_child' "strata" >/dev/null
  expect "um binário só de teste no bundle falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_executables "strata" "strata" >/dev/null
  expect "o executável do aplicativo sozinho passa" "$PROBLEMS" "0"
  PROBLEMS=0; check_executables $'strata.exe\nstub_child.exe\nuninstall.exe' "strata.exe,uninstall.exe" >/dev/null
  expect "um .exe sobrando ao lado do app e do desinstalador falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_executables $'uninstall.exe\nstrata.exe' "strata.exe,uninstall.exe" >/dev/null
  expect "o app e o desinstalador NSIS passam" "$PROBLEMS" "0"
  expect "uma listagem de deb sem ./ dá os nomes de usr/bin" \
    "$(printf '%s\n' '-rwxr-xr-x root/root 1 2026-10-15 16:51 usr/bin/strata' \
      '-rwxr-xr-x root/root 1 2026-10-15 16:51 usr/lib/Strata/analyzer/runtime/bin/java' | deb_bin_entries)" "strata"
  expect "...e com ./, as duas entradas de um deb sujo" \
    "$(printf '%s\n' '-rwxr-xr-x root/root 1 2026-10-15 16:51 ./usr/bin/strata' \
      '-rwxr-xr-x root/root 1 2026-10-15 16:51 ./usr/bin/stub_child' | deb_bin_entries | paste -sd, -)" "strata,stub_child"

  PROBLEMS=0; check_bundled_jvm $'/usr/lib/dyld\n/Library/Java/x/lib/server/libjvm.dylib' "/apps/Strata.app/lib/server/libjvm.dylib" libjvm.dylib >/dev/null
  expect "uma JVM de fora do bundle falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_bundled_jvm "/usr/lib/dyld" "/apps/x/libjvm.dylib" libjvm.dylib >/dev/null
  expect "nenhuma libjvm mapeada falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_bundled_jvm $'/usr/lib/x86_64-linux-gnu/libc.so.6\n/s/usr/lib/Strata/analyzer/runtime/lib/server/libjvm.so' "/s/usr/lib/Strata/analyzer/runtime/lib/server/libjvm.so" libjvm.so >/dev/null
  expect "a libjvm.so empacotada passa" "$PROBLEMS" "0"
  PROBLEMS=0; check_bundled_jvm "/usr/lib/jvm/temurin-25/lib/server/libjvm.so" "/s/usr/lib/Strata/analyzer/runtime/lib/server/libjvm.so" libjvm.so >/dev/null
  expect "uma libjvm.so do sistema falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_bundled_jvm $'C:\\Windows\\System32\\KERNEL32.DLL\nC:\\Users\\RunnerAdmin\\Temp\\s\\Strata\\analyzer\\runtime\\bin\\server\\JVM.DLL' 'c:\users\runneradmin\temp\s\strata\analyzer\runtime\bin\server\jvm.dll' jvm.dll nocase >/dev/null
  expect "um caminho do Windows que só difere na caixa passa" "$PROBLEMS" "0"
  PROBLEMS=0; check_bundled_jvm 'C:\Program Files\Eclipse Adoptium\jdk-25\bin\server\jvm.dll' 'C:\s\Strata\analyzer\runtime\bin\server\jvm.dll' jvm.dll nocase >/dev/null
  expect "a jvm.dll de um JDK instalado falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_analyzer_exe "/usr/bin/java" "/s/usr/lib/Strata/analyzer/runtime/bin/java" >/dev/null
  expect "um executável java do sistema falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_analyzer_exe 'C:\S\STRATA\analyzer\runtime\bin\Java.exe' 'c:\s\strata\analyzer\runtime\bin\java.exe' nocase >/dev/null
  expect "o java.exe empacotado passa, qualquer que seja a caixa" "$PROBLEMS" "0"

  PROBLEMS=0; check_jvm_flags "-Xmx2g -XX:+HeapDumpOnOutOfMemoryError" >/dev/null
  expect "uma flag de heap dump e a falta de ExitOnOOM falham duas vezes" "$PROBLEMS" "2"
  PROBLEMS=0; check_jvm_flags "-Xmx2g -XX:+ExitOnOutOfMemoryError" >/dev/null
  expect "as flags entregues passam" "$PROBLEMS" "0"

  local environ=$'HOME=/home/runner\nLANG=C.UTF-8\nTMPDIR=/tmp/s/dados-ção/br.pucpr.tcc.strata'
  PROBLEMS=0; check_child_environ "$environ" "java -XX:+ExitOnOutOfMemoryError -cp x Main" >/dev/null
  expect "o ambiente da allowlist passa" "$PROBLEMS" "0"
  PROBLEMS=0; check_child_environ "$environ"$'\nPATH=/usr/bin' "java" >/dev/null
  expect "um nome herdado a mais falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_child_environ "$environ" "java -Dpoison=1 -cp x Main" >/dev/null
  expect "uma opção envenenada chegando ao analisador falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_child_environ "$environ"$'\nJAVA_TOOL_OPTIONS=-Dpoison=1' "java" >/dev/null
  expect "um JAVA_TOOL_OPTIONS vazado falha como nome e como veneno" "$PROBLEMS" "2"
  PROBLEMS=0; check_child_environ $'HOME=/h\nLANG=pt_BR.ISO-8859-1\nTMPDIR=/d' "java" >/dev/null
  expect "um LANG que não é UTF-8 falha" "$PROBLEMS" "1"
  expect "um codeset utf8 minúsculo com modificador é UTF-8" "$(names_utf8 sr_RS.utf8@latin && echo sim || echo não)" "sim"
  expect "um locale C puro não é UTF-8" "$(names_utf8 C && echo sim || echo não)" "não"

  local crashed_unix='{"phase":"CRASHED","exitCode":null,"source":"event","elapsedMs":9,"unixMs":2}'
  local crashed_windows='{"phase":"CRASHED","exitCode":1,"source":"event","elapsedMs":9,"unixMs":2}'
  PROBLEMS=0; check_crashed_line '{"phase":"CRASHED","exitCode":137,"source":"event","elapsedMs":9,"unixMs":2}' null >/dev/null
  expect "um código de saída inventado falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_crashed_line '{"phase":"CRASHED","exitCode":null,"stderr":"/Users/x","source":"event","elapsedMs":9,"unixMs":2}' null >/dev/null
  expect "um campo a mais em CRASHED falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_crashed_line "$(report_line "$report" event CRASHED)" null >/dev/null
  expect "a linha CRASHED entregue passa" "$PROBLEMS" "0"
  PROBLEMS=0; check_crashed_line "$crashed_unix" null >/dev/null
  expect "Unix: o null de um SIGKILL passa" "$PROBLEMS" "0"
  PROBLEMS=0; check_crashed_line "$crashed_windows" null >/dev/null
  expect "Unix: um código de saída 1 falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_crashed_line "$crashed_windows" 1 >/dev/null
  expect "Windows: o código de saída 1 do taskkill /F passa" "$PROBLEMS" "0"
  PROBLEMS=0; check_crashed_line "$crashed_unix" 1 >/dev/null
  expect "Windows: um código de saída null falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_crashed_line '{"phase":"CRASHED","exitCode":13,"source":"event","elapsedMs":9,"unixMs":2}' 1 >/dev/null
  expect "Windows: 13 não é 1" "$PROBLEMS" "1"

  rm -rf "$dir"
  if [ "$failures" -eq 0 ]; then echo "autoteste: ok"; exit 0; fi
  echo "autoteste: $failures verificação(ões) com resultado inesperado"; exit 1
}

pwsh_run() {
  local script="$1" encoded
  shift
  encoded="$(printf '%s' "$script" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)"
  MSYS_NO_PATHCONV=1 env "$@" pwsh -NoProfile -NonInteractive -EncodedCommand "$encoded" | tr -d '\r'
}

ps_processes() {
  cat <<'PS'
$app = Get-CimInstance Win32_Process -Filter "ProcessId=$env:SMOKE_WINPID"; if ($app.Name -ne $env:SMOKE_APP_NAME) { $app = Get-CimInstance Win32_Process -Filter "ParentProcessId=$env:SMOKE_WINPID AND Name='$env:SMOKE_APP_NAME'" | Select-Object -First 1 }; if ($app) { $java = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($app.ProcessId) AND Name='java.exe'" | Select-Object -First 1; if ($java) { "{0}`t{1}`t{2}`t{3}" -f $app.ProcessId, $java.ProcessId, $java.ExecutablePath, $java.CommandLine } }
PS
}

ps_modules() {
  cat <<'PS'
(Get-Process -Id $env:SMOKE_PID).Modules | ForEach-Object { $_.FileName }
PS
}

ps_known_folders() {
  cat <<'PS'
[Environment]::GetFolderPath('ApplicationData'); [Environment]::GetFolderPath('LocalApplicationData')
PS
}

ps_environment() {
  cat <<'PS'
$os = Get-CimInstance Win32_OperatingSystem; $cs = Get-CimInstance Win32_ComputerSystem; $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1; "$($os.Caption) $($os.Version)"; $cpu.Name.Trim(); $cs.NumberOfLogicalProcessors; $cs.TotalPhysicalMemory
PS
}

windows_long() { cygpath -w -l "$1"; }

windows_alive() {
  local rows
  rows="$(MSYS_NO_PATHCONV=1 tasklist /FI "PID eq $1" /NH /FO CSV 2>/dev/null)" || return 0
  case "$rows" in *",\"$1\","*) return 0 ;; *) return 1 ;; esac
}

windows_app_pid() { printf '%s\n' "${APP_WINPID:-$(cat "/proc/$APP_PID/winpid" 2>/dev/null || true)}"; }

clear_windows_profile() {
  local folders folder
  folders="$(pwsh_run "$(ps_known_folders)")" || die "o PowerShell não conseguiu resolver as pastas conhecidas"
  [ "$(printf '%s\n' "$folders" | sed '/^$/d' | wc -l | tr -d ' ')" -eq 2 ] || die "eram esperadas duas pastas conhecidas, vieram: $folders"
  while IFS= read -r folder; do
    [ -n "$folder" ] || continue
    rm -rf "$(cygpath -u "$folder")/$APP_IDENTIFIER"
  done <<<"$folders"
}

app_alive() { kill -0 "$1" 2>/dev/null; }

analyzer_alive() {
  case "$OS_KIND" in
    windows) windows_alive "$1" ;;
    *) kill -0 "$1" 2>/dev/null ;;
  esac
}

kill_analyzer() {
  case "$OS_KIND" in
    windows) MSYS_NO_PATHCONV=1 taskkill /F /PID "$1" >/dev/null 2>&1 ;;
    *) kill -9 "$1" 2>/dev/null ;;
  esac
}

request_quit() {
  local winpid
  case "$OS_KIND" in
    windows)
      winpid="$(windows_app_pid)"
      [ -z "$winpid" ] || MSYS_NO_PATHCONV=1 taskkill /PID "$winpid" >/dev/null 2>&1 || true
      ;;
    *) kill -TERM "$APP_PID" 2>/dev/null || true ;;
  esac
}

force_quit() {
  local winpid
  if [ "$OS_KIND" = "windows" ]; then
    winpid="$(windows_app_pid)"
    [ -z "$winpid" ] || MSYS_NO_PATHCONV=1 taskkill /F /PID "$winpid" >/dev/null 2>&1 || true
  fi
  kill -KILL "$APP_PID" 2>/dev/null || true
}

cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    force_quit
  fi
  if [ -n "$MOUNT" ] && [ -d "$MOUNT" ]; then
    /usr/bin/hdiutil detach "$MOUNT" -quiet -force 2>/dev/null || true
  fi
  if [ -n "$WORK" ] && [ -d "$WORK" ]; then
    rm -rf "$WORK" 2>/dev/null || echo "smoke-packaged.sh: não foi possível remover $WORK" >&2
  fi
}

deb_bin_entries() {
  awk '$NF ~ /^(\.\/)?usr\/bin\/[^\/]+$/ { sub(/^(\.\/)?usr\/bin\//, "", $NF); print $NF }'
}

names_in() {
  local file
  for file in "$1"/$2; do
    [ -e "$file" ] && printf '%s\n' "${file##*/}"
  done
  return 0
}

install_macos() {
  local dmg app
  dmg="$(find_artifact "$REPO_ROOT/src-tauri/target/release/bundle/dmg" '*.dmg')"
  ARTIFACT="$dmg"
  echo "smoke-packaged: instalando $(basename "$dmg")"
  MOUNT="$WORK/mnt"
  mkdir -p "$MOUNT" "$WORK/Applications"
  /usr/bin/hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$MOUNT" "$dmg" >/dev/null
  /usr/bin/ditto "$MOUNT/Strata.app" "$WORK/Applications/Strata.app"
  /usr/bin/hdiutil detach "$MOUNT" -quiet
  MOUNT=""
  app="$WORK/Applications/Strata.app"
  APP_EXE="$app/Contents/MacOS/strata"
  RESOURCES="$app/Contents/Resources/analyzer"
  check_executables "$(names_in "$app/Contents/MacOS" '*')" "strata"
}

install_linux() {
  local deb listing mode
  deb="$(find_artifact "$REPO_ROOT/src-tauri/target/release/bundle/deb" '*.deb')"
  ARTIFACT="$deb"
  echo "smoke-packaged: extraindo $(basename "$deb")"
  listing="$(dpkg-deb -c "$deb")"
  check_executables "$(printf '%s\n' "$listing" | deb_bin_entries)" "strata"
  mode="$(printf '%s\n' "$listing" | awk '!seen && $NF ~ /\/analyzer\/runtime\/bin\/java$/ { print $1; seen = 1 }')"
  if [ "$mode" = "-rwxr-xr-x" ]; then
    good "o deb entrega runtime/bin/java como -rwxr-xr-x"
  else
    bad "o deb entrega runtime/bin/java como '${mode:-ausente}', e era esperado -rwxr-xr-x"
  fi
  dpkg-deb -x "$deb" "$WORK/root"
  mkdir -p "$WORK/home" "$WORK/config" "$WORK/cache" "$WORK/state"
  APP_EXE="$WORK/root/usr/bin/strata"
  RESOURCES="$WORK/root/usr/lib/Strata/analyzer"
}

install_windows() {
  local setup target
  setup="$(find_artifact "$REPO_ROOT/src-tauri/target/release/bundle/nsis" '*-setup.exe')"
  ARTIFACT="$setup"
  target="$(cygpath -w "$WORK/Strata")"
  case "$target" in *' '*) die "a pasta temporária '$target' tem espaço, que o /D= do NSIS não aceita" ;; esac
  clear_windows_profile
  APP_EXE="$WORK/Strata/strata.exe"
  RESOURCES="$WORK/Strata/analyzer"
  echo "smoke-packaged: instalando $(basename "$setup") em $target"
  if ! MSYS_NO_PATHCONV=1 "$setup" /S "/D=$target"; then
    bad "o instalador NSIS saiu com código diferente de 0"
    return 0
  fi
  check_executables "$(lower "$(names_in "$WORK/Strata" '*.exe')")" "strata.exe,uninstall.exe"
}

check_layout() {
  local before="$PROBLEMS"
  if [ -x "$APP_EXE" ]; then good "o executável do aplicativo $(basename "$APP_EXE") está instalado"; else bad "falta o executável do aplicativo $(basename "$APP_EXE")"; fi
  if [ -x "$RESOURCES/runtime/bin/$JAVA_NAME" ]; then good "runtime/bin/$JAVA_NAME está empacotado e é executável"; else bad "runtime/bin/$JAVA_NAME falta ou não é executável"; fi
  if [ -f "$RESOURCES/analyzer.jar" ]; then good "analyzer.jar está empacotado"; else bad "falta o analyzer.jar"; fi
  if compgen -G "$RESOURCES/lib/*.jar" >/dev/null; then good "lib/ traz os jars das dependências"; else bad "lib/ não tem jars"; fi
  [ "$PROBLEMS" -eq "$before" ]
}

launch() {
  local report="$1" log="$2" native_report
  case "$OS_KIND" in
    macos)
      LAUNCH_MS="$(now_ms)"
      HOME="$WORK/home" CFFIXED_USER_HOME="$WORK/home" STRATA_SMOKE_REPORT="$report" \
        "$APP_EXE" >"$log" 2>&1 &
      ;;
    linux)
      LAUNCH_MS="$(now_ms)"
      env -u LANG -u LC_ALL -u LC_CTYPE \
        HOME="$WORK/home" XDG_DATA_HOME="$WORK/dados-ção" XDG_CONFIG_HOME="$WORK/config" \
        XDG_CACHE_HOME="$WORK/cache" XDG_STATE_HOME="$WORK/state" \
        JAVA_TOOL_OPTIONS=-Dpoison=1 _JAVA_OPTIONS=-Dpoison=1 \
        WEBKIT_DISABLE_DMABUF_RENDERER=1 WEBKIT_DISABLE_COMPOSITING_MODE=1 \
        STRATA_SMOKE_REPORT="$report" "$APP_EXE" >"$log" 2>&1 &
      ;;
    windows)
      native_report="$(cygpath -w "$report")"
      LAUNCH_MS="$(now_ms)"
      STRATA_SMOKE_REPORT="$native_report" "$APP_EXE" >"$log" 2>&1 &
      ;;
  esac
  APP_PID=$!
  APP_WINPID=""
}

wait_gone() {
  local pid="$1" timeout_ms="$2" alive="$3" deadline
  deadline=$(( $(now_ms) + timeout_ms ))
  while "$alive" "$pid"; do
    [ "$(now_ms)" -lt "$deadline" ] || return 1
    sleep "$POLL_SECONDS"
  done
}

quit_app() {
  local analyzer_pid="$1"
  request_quit
  if ! wait_gone "$APP_PID" "$QUIT_WAIT_MS" app_alive; then
    bad "o aplicativo não saiu em até ${QUIT_WAIT_MS} ms depois de $QUIT_SIGNAL"
    force_quit
  fi
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
  APP_WINPID=""
  [ -n "$analyzer_pid" ] || return 0
  if wait_gone "$analyzer_pid" "$CRASH_BUDGET_MS" analyzer_alive; then
    good "nenhum analisador órfão depois que o aplicativo sai"
  else
    bad "o analisador sobreviveu ao aplicativo por ${CRASH_BUDGET_MS} ms (órfão)"
    kill_analyzer "$analyzer_pid" || true
  fi
}

find_analyzer() {
  local row="" app=""
  ANALYZER_PID=""
  ANALYZER_EXE=""
  ANALYZER_ARGS=""
  if [ "$OS_KIND" != "windows" ]; then
    ANALYZER_PID="$(/usr/bin/pgrep -P "$APP_PID" -f "$MAIN_CLASS" | head -1)" || ANALYZER_PID=""
    return 0
  fi
  row="$(pwsh_run "$(ps_processes)" SMOKE_WINPID="$(windows_app_pid)" SMOKE_APP_NAME="$(basename "$APP_EXE")")" || row=""
  [ -n "$row" ] || return 0
  IFS=$'\t' read -r app ANALYZER_PID ANALYZER_EXE ANALYZER_ARGS <<<"$row"
  APP_WINPID="$app"
}

inspect_analyzer() {
  local pid="$1"
  case "$OS_KIND" in
    macos) inspect_macos "$pid" ;;
    linux) inspect_linux "$pid" ;;
    windows) inspect_windows "$pid" ;;
  esac
}

inspect_macos() {
  local pid="$1" mapped args
  if ! mapped="$(/usr/sbin/lsof -a -p "$pid" -d txt -Fn 2>/dev/null | sed -n 's/^n//p')"; then
    bad "o lsof não conseguiu ler o processo do analisador"
    return 0
  fi
  check_bundled_jvm "$mapped" "$(canonical "$RESOURCES/runtime/lib/server/libjvm.dylib")" libjvm.dylib
  if args="$(ps -o args= -p "$pid")"; then
    check_jvm_flags "$args"
  else
    bad "o analisador saiu antes que os argumentos dele pudessem ser lidos"
  fi
}

inspect_linux() {
  local pid="$1" exe mapped args environ
  if ! exe="$(readlink "/proc/$pid/exe")" \
    || ! mapped="$(sed -n 's/^[^ ]* [^ ]* [^ ]* [^ ]* [^ ]* *\(\/.*\)$/\1/p' "/proc/$pid/maps")" \
    || ! args="$(tr '\0' ' ' <"/proc/$pid/cmdline")" \
    || ! environ="$(tr '\0' '\n' <"/proc/$pid/environ")"; then
    bad "não foi possível ler o processo do analisador por /proc/$pid"
    return 0
  fi
  check_analyzer_exe "$exe" "$(canonical "$RESOURCES/runtime/bin/java")"
  check_bundled_jvm "$mapped" "$(canonical "$RESOURCES/runtime/lib/server/libjvm.so")" libjvm.so
  check_jvm_flags "$args"
  check_child_environ "$environ" "$args"
}

inspect_windows() {
  local pid="$1" modules
  if [ -z "$ANALYZER_EXE" ] || ! modules="$(pwsh_run "$(ps_modules)" SMOKE_PID="$pid")"; then
    bad "não foi possível ler o processo do analisador por Win32_Process e pelos módulos dele"
    return 0
  fi
  check_analyzer_exe "$(windows_long "$ANALYZER_EXE")" "$(windows_long "$RESOURCES/runtime/bin/java.exe")" nocase
  modules="$(printf '%s\n' "$modules" | grep -i '[\\/]jvm\.dll$' | cygpath -w -l -f - || true)"
  check_bundled_jvm "$modules" "$(windows_long "$RESOURCES/runtime/bin/server/jvm.dll")" jvm.dll nocase
  check_jvm_flags "$ANALYZER_ARGS"
}

ready_of() {
  local report="$1" deadline event query
  deadline=$(( $(now_ms) + STARTUP_WAIT_MS ))
  while :; do
    if report_line "$report" event CRASHED >/dev/null; then return 1; fi
    if event="$(report_field "$report" event READY unixMs)" && [ -n "$event" ] \
      && query="$(report_field "$report" query '*' unixMs)" && [ -n "$query" ]; then
      later_of "$event" "$query"
      return 0
    fi
    [ "$(now_ms)" -lt "$deadline" ] || return 1
    sleep "$POLL_SECONDS"
  done
}

crash_analyzer() {
  local report="$1" kill_ms crashed_unix
  kill_ms="$(now_ms)"
  if [ -z "$ANALYZER_PID" ] || ! kill_analyzer "$ANALYZER_PID"; then
    bad "nenhum processo do analisador para matar"
    return 0
  fi
  if crashed_unix="$(wait_for_line "$report" event CRASHED "$CRASH_BUDGET_MS")"; then
    CRASH_MS=$(( crashed_unix - kill_ms ))
    EXIT_CODE="$(report_field "$report" event CRASHED exitCode)"
    check_budget "$KILL_LABEL -> CRASHED" "$CRASH_MS" "$CRASH_BUDGET_MS"
    check_crashed_line "$(report_line "$report" event CRASHED)" "$CRASH_EXIT_CODE"
  else
    bad "$KILL_LABEL não apareceu como CRASHED em até ${CRASH_BUDGET_MS} ms"
  fi
}

run_smoke() {
  local run report ready_unix ready_ms label
  WORK="$(CDPATH= cd -P -- "$(mktemp -d "${TMPDIR:-/tmp}/strata-smoke.XXXXXX")" && pwd)"
  "install_$OS_KIND"
  check_layout || return 0

  for run in $(seq 0 "$WARM_RUNS"); do
    report="$WORK/report-$run.jsonl"
    launch "$report" "$WORK/app-$run.log"
    if ! ready_unix="$(ready_of "$report")"; then
      bad "execução $run: sem evento READY e primeira leitura de analyzer_state em até ${STARTUP_WAIT_MS} ms, ou o shell informou CRASHED (relatório: $(tr '\n' ' ' < "$report" 2>/dev/null || echo nenhum))"
      quit_app ""
      return 0
    fi
    ready_ms=$(( ready_unix - LAUNCH_MS ))
    find_analyzer
    if [ "$run" -eq 0 ]; then label="primeira abertura"; else label="abertura a quente $run"; fi
    check_ready "$label" "$ready_ms"

    if [ "$run" -eq 0 ]; then
      FIRST_MS="$ready_ms"
      SHELL_READY_MS="$(report_field "$report" event READY elapsedMs)"
      if [ -n "$ANALYZER_PID" ]; then
        inspect_analyzer "$ANALYZER_PID"
      else
        bad "READY foi informado, mas não existe nenhum analisador filho do aplicativo (pid do app $APP_PID${APP_WINPID:+, pid no Windows $APP_WINPID})"
      fi
    else
      WARM_MS="${WARM_MS:+$WARM_MS }$ready_ms"
    fi

    if [ "$run" -lt "$WARM_RUNS" ]; then
      quit_app "$ANALYZER_PID"
      continue
    fi
    crash_analyzer "$report"
    quit_app ""
  done
  COMPLETED="yes"
}

probe_environment() {
  local os="" cpu="" cores="" ram="" storage="unknown" ssd="" probes=""
  case "$OS_KIND" in
    macos)
      os="$(/usr/bin/sw_vers -productVersion 2>/dev/null)" && os="macOS $os" || os=""
      cpu="$(/usr/sbin/sysctl -n machdep.cpu.brand_string 2>/dev/null)" || cpu=""
      cores="$(/usr/sbin/sysctl -n hw.ncpu 2>/dev/null)" || cores=""
      ram="$(/usr/sbin/sysctl -n hw.memsize 2>/dev/null)" || ram=""
      ssd="$(/usr/sbin/diskutil info / 2>/dev/null | sed -n 's/^ *Solid State: *//p')" || ssd=""
      storage="internal, solid state: ${ssd:-unknown}"
      ;;
    linux)
      os="$(awk -F= '$1 == "PRETTY_NAME" { gsub(/"/, "", $2); print $2 }' /etc/os-release 2>/dev/null)" || os=""
      cpu="$(awk -F': *' '/^model name/ && !seen { print $2; seen = 1 }' /proc/cpuinfo 2>/dev/null)" || cpu=""
      cores="$(nproc 2>/dev/null)" || cores=""
      ram="$(awk '/^MemTotal:/ { printf "%.0f\n", $2 * 1024 }' /proc/meminfo 2>/dev/null)" || ram=""
      ;;
    windows)
      probes="$(pwsh_run "$(ps_environment)" 2>/dev/null)" || probes=""
      os="$(printf '%s\n' "$probes" | sed -n 1p)"
      cpu="$(printf '%s\n' "$probes" | sed -n 2p)"
      cores="$(printf '%s\n' "$probes" | sed -n 3p)"
      ram="$(printf '%s\n' "$probes" | sed -n 4p)"
      ;;
  esac
  printf '%s\n' "${os:-unknown}" "${cpu:-unknown}" "$cores" "$ram" "$storage"
}

record_json() {
  local probes="$1" commit="$2" artifact_bytes="$3" runtime_bytes="$4"
  local warm_csv
  warm_csv="$(printf '%s' "$WARM_MS" | sed 's/ /, /g')"
  cat <<JSON
{
  "schema": 2,
  "measuredAt": $(json_string "$(date -u +%Y-%m-%dT%H:%M:%SZ)"),
  "commit": $(json_string "$commit"),
  "measure": "wall clock from exec of the installed app to the later of the READY event and the WebView's first analyzer_state read",
  "budgetsMs": { "launchToReady": $READY_BUDGET_MS, "killToCrashed": $CRASH_BUDGET_MS },
  "environment": {
    "os": $(json_string "$(printf '%s\n' "$probes" | sed -n 1p)"),
    "arch": $(json_string "$(uname -m)"),
    "cpu": $(json_string "$(printf '%s\n' "$probes" | sed -n 2p)"),
    "logicalCores": $(json_number "$(printf '%s\n' "$probes" | sed -n 3p)"),
    "ramBytes": $(json_number "$(printf '%s\n' "$probes" | sed -n 4p)"),
    "storage": $(json_string "$(printf '%s\n' "$probes" | sed -n 5p)"),
    "path": $(json_string "$PATH")
  },
  "repository": null,
  "repositoryNote": "startup opens no repository, so repository size and commit count do not apply",
  "artifact": { "name": $(json_string "$(basename "$ARTIFACT")"), "bytes": $(json_number "$artifact_bytes"), "runtimeBytes": $(json_number "$runtime_bytes") },
  "launchToReadyMs": { "firstLaunch": $(json_number "$FIRST_MS"), "warm": [$warm_csv], "warmMedian": $(json_number "$(median_of $WARM_MS)"), "warmMax": $(json_number "$(max_of $WARM_MS)") },
  "shellReadyEventMsFirstLaunch": $(json_number "$SHELL_READY_MS"),
  "crash": { "killToCrashedMs": $(json_number "$CRASH_MS"), "exitCode": $(json_number "$EXIT_CODE") }
}
JSON
}

write_record() {
  local file="$1" probes commit artifact_bytes runtime_kb runtime_bytes=""
  probes="$(probe_environment)"
  commit="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null)" || commit="unknown"
  artifact_bytes="$(wc -c <"$ARTIFACT" 2>/dev/null | tr -d ' ')" || artifact_bytes=""
  runtime_kb="$(du -sk "$RESOURCES/runtime" 2>/dev/null | cut -f1)" || runtime_kb=""
  case "$runtime_kb" in '' | *[!0-9]*) ;; *) runtime_bytes=$((runtime_kb * 1024)) ;; esac
  if mkdir -p "$(dirname -- "$file")" && record_json "$probes" "$commit" "$artifact_bytes" "$runtime_bytes" >"$file"; then
    echo "  gravado ${file#"$REPO_ROOT"/}"
  else
    bad "não foi possível gravar a medição em $file"
  fi
}

write_step_summary() {
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  local verdict="todas as verificações passaram" warm="n/d"
  [ "$PROBLEMS" -eq 0 ] || verdict="$PROBLEMS problema(s)"
  [ -z "$WARM_MS" ] || warm="$(printf '%s' "$WARM_MS" | sed 's/ /, /g') ms (mediana $(median_of $WARM_MS), máximo $(max_of $WARM_MS))"
  if ! {
    printf '### Teste de fumaça do app empacotado: %s %s\n\n' "$OS_LABEL" "$(uname -m)"
    printf '| medida | valor |\n|---|---|\n'
    printf '| artefato | %s |\n' "$(basename "${ARTIFACT:-nenhum}")"
    printf '| primeira abertura -> READY | %s ms (meta < %s ms) |\n' "${FIRST_MS:-n/d}" "$READY_BUDGET_MS"
    printf '| aberturas a quente -> READY | %s |\n' "$warm"
    printf '| %s -> CRASHED | %s ms (limite < %s ms), exitCode %s |\n' "$KILL_LABEL" "${CRASH_MS:-n/d}" "$CRASH_BUDGET_MS" "${EXIT_CODE:-n/d}"
    printf '| resultado | %s |\n\n' "$verdict"
  } >>"$GITHUB_STEP_SUMMARY"; then
    echo "smoke-packaged.sh: não foi possível acrescentar ao GITHUB_STEP_SUMMARY" >&2
  fi
}

configure_os() {
  case "$(uname -s)" in
    Darwin)
      OS_KIND="macos"; OS_LABEL="macOS"; JAVA_NAME="java"
      KILL_LABEL="kill -9"; QUIT_SIGNAL="SIGTERM"; CRASH_EXIT_CODE="null"
      ;;
    Linux)
      OS_KIND="linux"; OS_LABEL="Linux"; JAVA_NAME="java"
      KILL_LABEL="kill -9"; QUIT_SIGNAL="SIGTERM (uma parada brusca no GTK)"; CRASH_EXIT_CODE="null"
      [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ] \
        || die "sem DISPLAY nem WAYLAND_DISPLAY; o WebKitGTK precisa de um: rode 'xvfb-run -a bash scripts/smoke-packaged.sh --ci'"
      command -v dpkg-deb >/dev/null || die "o dpkg-deb é necessário para extrair o .deb"
      ;;
    MINGW* | MSYS* | CYGWIN*)
      OS_KIND="windows"; OS_LABEL="Windows"; JAVA_NAME="java.exe"
      KILL_LABEL="taskkill /F"; QUIT_SIGNAL="WM_CLOSE (taskkill sem /F)"; CRASH_EXIT_CODE="1"
      [ "$CI_MODE" = "yes" ] \
        || die "uma execução no Windows não é isolada: ela apaga os dados do Strata em %APPDATA% e %LOCALAPPDATA% e instala por usuário; rode só com --ci em um runner descartável"
      local tool
      for tool in pwsh cygpath taskkill tasklist iconv base64; do
        command -v "$tool" >/dev/null || die "o $tool é necessário no Windows"
      done
      ;;
    *) die "$(uname -s) não é um dos três runners (Darwin, Linux, Windows Git Bash)" ;;
  esac
}

main() {
  local no_record="no" record_to="" target=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --no-record) no_record="yes" ;;
      --ci) CI_MODE="yes" ;;
      --record-to)
        [ "$#" -ge 2 ] && [ -n "$2" ] || { usage >&2; exit 2; }
        record_to="$2"
        shift
        ;;
      --self-test) self_test ;;
      -h | --help) usage; exit 0 ;;
      *) usage >&2; exit 2 ;;
    esac
    shift
  done

  [ -z "$record_to" ] || [ "$no_record" = "no" ] || die "--record-to e --no-record se contradizem"
  target="$record_to"
  if [ -z "$target" ] && [ "$no_record" = "no" ] && [ "$CI_MODE" = "no" ]; then target="$RECORD_FILE"; fi
  if [ "$CI_MODE" = "yes" ] && [ -n "$target" ] && [ "$(canonical "$target")" = "$(canonical "$RECORD_FILE")" ]; then
    die "--ci nunca grava o benchmarks/startup.json versionado; passe --record-to com outro arquivo"
  fi

  configure_os
  trap cleanup EXIT
  run_smoke
  if [ -n "$target" ] && [ "$COMPLETED" = "yes" ]; then write_record "$target"; fi
  write_step_summary

  if [ "$PROBLEMS" -eq 0 ]; then
    echo "smoke-packaged: todas as verificações passaram"
    exit 0
  fi
  echo "smoke-packaged: $PROBLEMS problema(s)"
  exit 1
}

main "$@"
