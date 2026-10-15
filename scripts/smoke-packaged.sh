#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MAIN_CLASS="br.pucpr.tcc.strata.analyzer.bootstrap.Main"
READY_BUDGET_MS=1500
CRASH_BUDGET_MS=2000
HANDSHAKE_WAIT_MS=12000
QUIT_WAIT_MS=5000
WARM_RUNS=5
POLL_SECONDS=0.025
RECORD_FILE="$REPO_ROOT/benchmarks/startup.json"

PROBLEMS=0
WORK=""
MOUNT=""
APP_PID=""

usage() {
  cat <<'USAGE'
smoke-packaged.sh: instala o app empacotado, abre o app e prova que o runtime empacotado responde

  bash scripts/smoke-packaged.sh
  bash scripts/smoke-packaged.sh --no-record
  bash scripts/smoke-packaged.sh --self-test

Saída: 0 tudo certo · 1 alguma verificação falhou · 2 erro de uso ou de ambiente
USAGE
}

bad() { echo "  falha  $1"; PROBLEMS=$((PROBLEMS + 1)); }
good() { echo "  ok     $1"; }
die() { echo "smoke-packaged.sh: $1" >&2; exit 2; }

now_ms() { /usr/bin/perl -MTime::HiRes=time -e 'printf "%d\n", time() * 1000'; }

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

check_bundled_jvm() {
  local mapped="$1" expected="$2" path
  while IFS= read -r path; do
    case "$path" in */libjvm.dylib) ;; *) continue ;; esac
    if [ "$(canonical "$path")" = "$expected" ]; then
      good "a JVM do analisador é a do runtime empacotado (libjvm.dylib mapeada do bundle)"
    else
      bad "o analisador mapeia '$path', e não a libjvm.dylib empacotada"
    fi
    return 0
  done <<<"$mapped"
  bad "o processo do analisador não mapeia nenhuma libjvm.dylib"
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

check_crashed_line() {
  local line="$1" fields
  fields="$(printf '%s\n' "$line" | grep -o '"[A-Za-z]*":' | tr -d '":' | sort | paste -sd, -)"
  if [ "$fields" = "elapsedMs,exitCode,phase,source,unixMs" ]; then
    good "CRASHED traz só o código de saída"
  else
    bad "CRASHED traz os campos [$fields], e era esperado só o código de saída"
  fi
  case "$line" in
    *'"exitCode":null'*) good "o código de saída de um SIGKILL é null, sem nada inventado" ;;
    *) bad "um SIGKILL gerou um CRASHED com código de saída diferente de null: $line" ;;
  esac
}

self_test() {
  local dir report failures=0
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
  expect "o unixMs do evento READY é lido" "$(report_field "$report" event READY unixMs)" "1410"
  expect "a linha da consulta é distinguida da do evento" "$(report_field "$report" query '*' unixMs)" "1520"
  expect "a versão do protocolo é lida" "$(report_field "$report" event READY protocolVersion)" "1"
  expect "um código de saída null é lido como null" "$(report_field "$report" event CRASHED exitCode)" "null"
  expect "uma fase ausente é erro" "$(report_field "$report" event INCOMPATIBLE unixMs || echo ausente)" "ausente"
  expect "esperar uma linha ausente estoura o tempo" "$(wait_for_line "$report" query CRASHED 60 || echo timeout)" "timeout"
  expect "READY é o mais tardio entre evento e consulta" "$(later_of 1410 1520)" "1520"

  PROBLEMS=0; check_budget "acima do limite" 1500 1500 >/dev/null
  expect "uma execução no limite falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_bundled_jvm $'/usr/lib/dyld\n/Library/Java/x/lib/server/libjvm.dylib' "/apps/Strata.app/lib/server/libjvm.dylib" >/dev/null
  expect "uma JVM de fora do bundle falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_bundled_jvm "/usr/lib/dyld" "/apps/x/libjvm.dylib" >/dev/null
  expect "nenhuma libjvm mapeada falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_jvm_flags "-Xmx2g -XX:+HeapDumpOnOutOfMemoryError" >/dev/null
  expect "uma flag de heap dump e a falta de ExitOnOOM falham duas vezes" "$PROBLEMS" "2"
  PROBLEMS=0; check_jvm_flags "-Xmx2g -XX:+ExitOnOutOfMemoryError" >/dev/null
  expect "as flags entregues passam" "$PROBLEMS" "0"
  PROBLEMS=0; check_crashed_line '{"phase":"CRASHED","exitCode":137,"source":"event","elapsedMs":9,"unixMs":2}' >/dev/null
  expect "um código de saída inventado falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_crashed_line '{"phase":"CRASHED","exitCode":null,"stderr":"/Users/x","source":"event","elapsedMs":9,"unixMs":2}' >/dev/null
  expect "um campo a mais em CRASHED falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_crashed_line "$(report_line "$report" event CRASHED)" >/dev/null
  expect "a linha CRASHED entregue passa" "$PROBLEMS" "0"

  rm -rf "$dir"
  if [ "$failures" -eq 0 ]; then echo "autoteste: ok"; exit 0; fi
  echo "autoteste: $failures verificação(ões) com resultado inesperado"; exit 1
}

cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill -KILL "$APP_PID" 2>/dev/null || true
  fi
  if [ -n "$MOUNT" ] && [ -d "$MOUNT" ]; then
    /usr/bin/hdiutil detach "$MOUNT" -quiet -force 2>/dev/null || true
  fi
  if [ -n "$WORK" ] && [ -d "$WORK" ]; then
    rm -rf "$WORK"
  fi
}

find_dmg() {
  local dmgs=("$REPO_ROOT"/src-tauri/target/release/bundle/dmg/*.dmg)
  [ -e "${dmgs[0]}" ] || die "nenhum .dmg em src-tauri/target/release/bundle/dmg; rode 'pnpm package' antes"
  [ "${#dmgs[@]}" -eq 1 ] || die "${#dmgs[@]} arquivos .dmg na pasta do bundle; era esperado exatamente um"
  printf '%s\n' "${dmgs[0]}"
}

install_dmg() {
  local dmg="$1"
  MOUNT="$WORK/mnt"
  mkdir -p "$MOUNT" "$WORK/Applications"
  /usr/bin/hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$MOUNT" "$dmg" >/dev/null
  /usr/bin/ditto "$MOUNT/Strata.app" "$WORK/Applications/Strata.app"
  /usr/bin/hdiutil detach "$MOUNT" -quiet
  MOUNT=""
}

check_layout() {
  local resources="$1"
  if [ -x "$resources/runtime/bin/java" ]; then good "runtime/bin/java está empacotado e é executável"; else bad "runtime/bin/java falta ou não é executável"; fi
  if [ -f "$resources/analyzer.jar" ]; then good "analyzer.jar está empacotado"; else bad "falta o analyzer.jar"; fi
  if compgen -G "$resources/lib/*.jar" >/dev/null; then good "lib/ traz os jars das dependências"; else bad "lib/ não tem jars"; fi
}

launch() {
  local report="$1" log="$2"
  LAUNCH_MS="$(now_ms)"
  HOME="$WORK/home" CFFIXED_USER_HOME="$WORK/home" STRATA_SMOKE_REPORT="$report" \
    "$APP_EXE" >"$log" 2>&1 &
  APP_PID=$!
}

wait_gone() {
  local pid="$1" timeout_ms="$2" deadline
  deadline=$(( $(now_ms) + timeout_ms ))
  while kill -0 "$pid" 2>/dev/null; do
    [ "$(now_ms)" -lt "$deadline" ] || return 1
    sleep "$POLL_SECONDS"
  done
}

quit_app() {
  local analyzer_pid="$1"
  kill -TERM "$APP_PID" 2>/dev/null || true
  if ! wait_gone "$APP_PID" "$QUIT_WAIT_MS"; then
    bad "o aplicativo não saiu em até ${QUIT_WAIT_MS} ms depois de SIGTERM"
    kill -KILL "$APP_PID" 2>/dev/null || true
  fi
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
  [ -n "$analyzer_pid" ] || return 0
  if wait_gone "$analyzer_pid" "$CRASH_BUDGET_MS"; then
    good "nenhum analisador órfão depois que o aplicativo sai"
  else
    bad "o analisador sobreviveu ao aplicativo por ${CRASH_BUDGET_MS} ms (órfão)"
    kill -KILL "$analyzer_pid" 2>/dev/null || true
  fi
}

analyzer_pid_of() {
  /usr/bin/pgrep -P "$APP_PID" -f "$MAIN_CLASS" | head -1
}

ready_of() {
  local report="$1" event query
  event="$(wait_for_line "$report" event READY "$HANDSHAKE_WAIT_MS")" || return 1
  query="$(wait_for_line "$report" query '*' "$HANDSHAKE_WAIT_MS")" || return 1
  later_of "$event" "$query"
}

inspect_analyzer() {
  local analyzer_pid="$1" resources="$2" mapped args
  if ! mapped="$(/usr/sbin/lsof -a -p "$analyzer_pid" -d txt -Fn 2>/dev/null | sed -n 's/^n//p')"; then
    bad "o lsof não conseguiu ler o processo do analisador"
    return 0
  fi
  check_bundled_jvm "$mapped" "$(canonical "$resources/runtime/lib/server/libjvm.dylib")"
  if args="$(ps -o args= -p "$analyzer_pid")"; then
    check_jvm_flags "$args"
  else
    bad "o analisador saiu antes que os argumentos dele pudessem ser lidos"
  fi
}

json_string() {
  local value="${1//\\/\\\\}"
  printf '"%s"' "${value//\"/\\\"}"
}

write_record() {
  local first="$1" warm_csv="$2" warm_median="$3" warm_max="$4" crash_ms="$5" exit_code="$6"
  local dmg_name="$7" dmg_bytes="$8" runtime_bytes="$9" shell_ready="${10}"
  local os cpu cores ram_bytes ssd commit
  os="macOS $(/usr/bin/sw_vers -productVersion)"
  cpu="$(/usr/sbin/sysctl -n machdep.cpu.brand_string)"
  cores="$(/usr/sbin/sysctl -n hw.ncpu)"
  ram_bytes="$(/usr/sbin/sysctl -n hw.memsize)"
  ssd="$(/usr/sbin/diskutil info / | sed -n 's/^ *Solid State: *//p')"
  commit="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  mkdir -p "$(dirname "$RECORD_FILE")"
  cat > "$RECORD_FILE" <<JSON
{
  "schema": 2,
  "measuredAt": $(json_string "$(date -u +%Y-%m-%dT%H:%M:%SZ)"),
  "commit": $(json_string "$commit"),
  "measure": "wall clock from exec of the installed app to the later of the READY event and the WebView's first analyzer_state read",
  "budgetsMs": { "launchToReady": $READY_BUDGET_MS, "killToCrashed": $CRASH_BUDGET_MS },
  "environment": {
    "os": $(json_string "$os"),
    "arch": $(json_string "$(uname -m)"),
    "cpu": $(json_string "$cpu"),
    "logicalCores": $cores,
    "ramBytes": $ram_bytes,
    "storage": $(json_string "internal, solid state: ${ssd:-unknown}"),
    "path": $(json_string "$PATH")
  },
  "repository": null,
  "repositoryNote": "startup opens no repository, so repository size and commit count do not apply",
  "artifact": { "name": $(json_string "$dmg_name"), "bytes": $dmg_bytes, "runtimeBytes": $runtime_bytes },
  "launchToReadyMs": { "firstLaunch": $first, "warm": [$warm_csv], "warmMedian": $warm_median, "warmMax": $warm_max },
  "shellReadyEventMsFirstLaunch": $shell_ready,
  "crash": { "killToCrashedMs": $crash_ms, "exitCode": $exit_code }
}
JSON
  echo "  gravado benchmarks/startup.json"
}

median_of() {
  printf '%s\n' "$@" | sort -n | awk '{ v[NR] = $1 } END { print (NR % 2) ? v[(NR + 1) / 2] : int((v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}

run_macos() {
  local record="$1" dmg resources run report ready_unix ready_ms analyzer_pid label
  local first="" shell_ready="" warm=() kill_ms crashed_unix crash_ms="null" exit_code="null"

  dmg="$(find_dmg)"
  WORK="$(CDPATH= cd -P -- "$(mktemp -d "${TMPDIR:-/tmp}/strata-smoke.XXXXXX")" && pwd)"
  echo "smoke-packaged: instalando $(basename "$dmg")"
  install_dmg "$dmg"
  APP_EXE="$WORK/Applications/Strata.app/Contents/MacOS/strata"
  resources="$WORK/Applications/Strata.app/Contents/Resources/analyzer"
  check_layout "$resources"
  [ "$PROBLEMS" -eq 0 ] || return 0

  for run in $(seq 0 "$WARM_RUNS"); do
    report="$WORK/report-$run.jsonl"
    launch "$report" "$WORK/app-$run.log"
    if ! ready_unix="$(ready_of "$report")"; then
      bad "execução $run: sem evento READY e primeira leitura de analyzer_state em até ${HANDSHAKE_WAIT_MS} ms (relatório: $(tr '\n' ' ' < "$report" 2>/dev/null || echo nenhum))"
      quit_app ""
      return 0
    fi
    ready_ms=$(( ready_unix - LAUNCH_MS ))
    analyzer_pid="$(analyzer_pid_of || true)"
    if [ "$run" -eq 0 ]; then label="primeira abertura depois da instalação"; else label="abertura a quente $run"; fi
    check_budget "$label -> READY" "$ready_ms" "$READY_BUDGET_MS"

    if [ "$run" -eq 0 ]; then
      first="$ready_ms"
      shell_ready="$(report_field "$report" event READY elapsedMs)"
      if [ -n "$analyzer_pid" ]; then
        inspect_analyzer "$analyzer_pid" "$resources"
      else
        bad "READY foi informado, mas não existe nenhum analisador filho do aplicativo"
      fi
    else
      warm+=("$ready_ms")
    fi

    if [ "$run" -lt "$WARM_RUNS" ]; then
      quit_app "$analyzer_pid"
      continue
    fi

    kill_ms="$(now_ms)"
    if [ -z "$analyzer_pid" ] || ! kill -9 "$analyzer_pid" 2>/dev/null; then
      bad "nenhum processo do analisador para matar"
      quit_app ""
      return 0
    fi
    if crashed_unix="$(wait_for_line "$report" event CRASHED "$CRASH_BUDGET_MS")"; then
      crash_ms=$(( crashed_unix - kill_ms ))
      exit_code="$(report_field "$report" event CRASHED exitCode)"
      check_budget "kill -9 -> CRASHED" "$crash_ms" "$CRASH_BUDGET_MS"
      check_crashed_line "$(report_line "$report" event CRASHED)"
    else
      bad "kill -9 não apareceu como CRASHED em até ${CRASH_BUDGET_MS} ms"
    fi
    quit_app ""
  done

  if [ "$record" = "yes" ]; then
    local warm_csv
    warm_csv="$(IFS=,; printf '%s' "${warm[*]}")"
    write_record "$first" "${warm_csv//,/, }" "$(median_of "${warm[@]}")" \
      "$(printf '%s\n' "${warm[@]}" | sort -n | tail -1)" "$crash_ms" "$exit_code" \
      "$(basename "$dmg")" "$(stat -f %z "$dmg")" \
      "$(( $(du -sk "$resources/runtime" | cut -f1) * 1024 ))" "${shell_ready:-null}"
  fi
}

main() {
  local record="yes"
  case "${1:-}" in
    "") ;;
    --no-record) record="no" ;;
    --self-test) self_test ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac

  case "$(uname -s)" in
    Darwin) ;;
    *) die "$(uname -s) ainda não é suportado: este script só roda no macOS" ;;
  esac

  trap cleanup EXIT
  run_macos "$record"

  if [ "$PROBLEMS" -eq 0 ]; then
    echo "smoke-packaged: todas as verificações passaram"
    exit 0
  fi
  echo "smoke-packaged: $PROBLEMS problema(s)"
  exit 1
}

main "$@"
