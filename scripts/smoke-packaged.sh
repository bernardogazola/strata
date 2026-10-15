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

report_field() {
  local file="$1" phase="$2" field="$3" line
  line="$(grep -m1 "\"phase\":\"$phase\"" "$file" 2>/dev/null || true)"
  [ -n "$line" ] || return 1
  printf '%s\n' "$line" | sed -n "s/.*\"$field\":\([^,}]*\).*/\1/p"
}

wait_for_phase() {
  local file="$1" phase="$2" timeout_ms="$3" deadline value
  deadline=$(( $(now_ms) + timeout_ms ))
  while :; do
    if value="$(report_field "$file" "$phase" unixMs)" && [ -n "$value" ]; then
      printf '%s\n' "$value"
      return 0
    fi
    [ "$(now_ms)" -lt "$deadline" ] || return 1
    sleep "$POLL_SECONDS"
  done
}

check_budget() {
  local label="$1" measured="$2" budget="$3"
  if [ "$measured" -lt "$budget" ]; then
    good "$label: ${measured} ms (< ${budget} ms)"
  else
    bad "$label: ${measured} ms, e o limite é ${budget} ms"
  fi
}

check_runtime_path() {
  local executable expected="$2"
  executable="$(canonical "$1")"
  if [ "$executable" = "$expected" ]; then
    good "o processo do analisador é o do runtime empacotado"
  else
    bad "o processo do analisador roda '$executable', e não o runtime empacotado"
  fi
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

self_test() {
  local dir report failures=0
  dir="$(mktemp -d "${TMPDIR:-/tmp}/smoke-packaged-self-test.XXXXXX")"
  report="$dir/report.jsonl"
  printf '%s\n' \
    '{"phase":"STARTING","elapsedMs":3,"unixMs":1000}' \
    '{"phase":"READY","protocolVersion":1,"elapsedMs":410,"unixMs":1410}' \
    '{"phase":"CRASHED","exitCode":null,"elapsedMs":900,"unixMs":1900}' > "$report"

  expect() {
    if [ "$2" = "$3" ]; then echo "  ok     $1"; else echo "  falha  $1 (obtido '$2', esperado '$3')"; failures=$((failures + 1)); fi
  }
  expect "o unixMs de READY é lido" "$(report_field "$report" READY unixMs)" "1410"
  expect "a versão do protocolo é lida" "$(report_field "$report" READY protocolVersion)" "1"
  expect "um código de saída null é lido como null" "$(report_field "$report" CRASHED exitCode)" "null"
  expect "uma fase ausente é erro" "$(report_field "$report" INCOMPATIBLE unixMs || echo ausente)" "ausente"
  expect "esperar uma fase ausente estoura o tempo" "$(wait_for_phase "$report" INCOMPATIBLE 60 || echo timeout)" "timeout"

  PROBLEMS=0; check_budget "acima do limite" 1500 1500 >/dev/null
  expect "uma execução no limite falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_runtime_path "/usr/bin/java" "/apps/Strata.app/x/java" >/dev/null
  expect "um runtime de fora do bundle falha" "$PROBLEMS" "1"
  PROBLEMS=0; check_jvm_flags "-Xmx2g -XX:+HeapDumpOnOutOfMemoryError" >/dev/null
  expect "uma flag de heap dump e a falta de ExitOnOOM falham duas vezes" "$PROBLEMS" "2"
  PROBLEMS=0; check_jvm_flags "-Xmx2g -XX:+ExitOnOutOfMemoryError" >/dev/null
  expect "as flags entregues passam" "$PROBLEMS" "0"

  rm -rf "$dir"
  if [ "$failures" -eq 0 ]; then echo "autoteste: ok"; exit 0; fi
  echo "autoteste: $failures verificação(ões) com resultado inesperado"; exit 1
}

cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill -TERM "$APP_PID" 2>/dev/null || true
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
  [ -e "${dmgs[0]}" ] || die "nenhum .dmg em src-tauri/target/release/bundle/dmg; rode 'pnpm tauri build' antes"
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
  HOME="$WORK/home" STRATA_SMOKE_REPORT="$report" "$APP_EXE" >"$log" 2>&1 &
  APP_PID=$!
}

quit_app() {
  local analyzer_pid="$1" deadline
  kill -TERM "$APP_PID" 2>/dev/null || true
  deadline=$(( $(now_ms) + QUIT_WAIT_MS ))
  while kill -0 "$APP_PID" 2>/dev/null; do
    [ "$(now_ms)" -lt "$deadline" ] || { bad "o aplicativo não saiu em até ${QUIT_WAIT_MS} ms depois de SIGTERM"; break; }
    sleep "$POLL_SECONDS"
  done
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
  [ -n "$analyzer_pid" ] || return 0
  deadline=$(( $(now_ms) + CRASH_BUDGET_MS ))
  while kill -0 "$analyzer_pid" 2>/dev/null; do
    if [ "$(now_ms)" -ge "$deadline" ]; then
      bad "o analisador sobreviveu ao aplicativo por ${CRASH_BUDGET_MS} ms (órfão)"
      kill -9 "$analyzer_pid" 2>/dev/null || true
      return 0
    fi
    sleep "$POLL_SECONDS"
  done
  good "nenhum analisador órfão depois que o aplicativo sai"
}

analyzer_pid_of() {
  /usr/bin/pgrep -P "$APP_PID" -f "$MAIN_CLASS" | head -1
}

json_string() {
  local value="${1//\\/\\\\}"
  printf '"%s"' "${value//\"/\\\"}"
}

write_record() {
  local cold="$1" warm_csv="$2" warm_median="$3" warm_max="$4" crash_ms="$5" exit_code="$6"
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
  "schema": 1,
  "measuredAt": $(json_string "$(date -u +%Y-%m-%dT%H:%M:%SZ)"),
  "commit": $(json_string "$commit"),
  "measure": "wall clock from exec of the installed app to the shell handing READY to the WebView",
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
  "launchToReadyMs": { "cold": $cold, "warm": [$warm_csv], "warmMedian": $warm_median, "warmMax": $warm_max },
  "shellToReadyMsCold": $shell_ready,
  "crash": { "killToCrashedMs": $crash_ms, "exitCode": $exit_code }
}
JSON
  echo "  gravado benchmarks/startup.json"
}

median_of() {
  printf '%s\n' "$@" | sort -n | awk '{ v[NR] = $1 } END { print (NR % 2) ? v[(NR + 1) / 2] : int((v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}

run_macos() {
  local record="$1" dmg resources run report ready_unix ready_ms analyzer_pid executable args
  local cold="" shell_ready="" warm=() kill_ms crashed_unix crash_ms exit_code

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
    if ! ready_unix="$(wait_for_phase "$report" READY "$HANDSHAKE_WAIT_MS")"; then
      bad "execução $run: sem READY em até ${HANDSHAKE_WAIT_MS} ms (relatório: $(tr '\n' ' ' < "$report" 2>/dev/null || echo nenhum))"
      quit_app ""
      return 0
    fi
    ready_ms=$(( ready_unix - LAUNCH_MS ))
    analyzer_pid="$(analyzer_pid_of || true)"

    if [ "$run" -eq 0 ]; then
      cold="$ready_ms"
      shell_ready="$(report_field "$report" READY elapsedMs)"
      check_budget "abertura a frio -> READY" "$ready_ms" "$READY_BUDGET_MS"
      if [ -z "$analyzer_pid" ]; then
        bad "READY foi informado, mas não existe nenhum analisador filho do aplicativo"
      else
        executable="$(ps -o comm= -p "$analyzer_pid")"
        args="$(ps -o args= -p "$analyzer_pid")"
        check_runtime_path "$executable" "$resources/runtime/bin/java"
        check_jvm_flags "$args"
      fi
    else
      warm+=("$ready_ms")
      check_budget "abertura a quente $run -> READY" "$ready_ms" "$READY_BUDGET_MS"
    fi

    if [ "$run" -lt "$WARM_RUNS" ]; then
      quit_app "$analyzer_pid"
      continue
    fi

    if [ -z "$analyzer_pid" ]; then
      bad "nenhum processo do analisador para matar"
      quit_app ""
      return 0
    fi
    kill_ms="$(now_ms)"
    kill -9 "$analyzer_pid"
    if crashed_unix="$(wait_for_phase "$report" CRASHED "$CRASH_BUDGET_MS")"; then
      crash_ms=$(( crashed_unix - kill_ms ))
      exit_code="$(report_field "$report" CRASHED exitCode)"
      check_budget "kill -9 -> CRASHED" "$crash_ms" "$CRASH_BUDGET_MS"
      good "CRASHED traz só o código de saída ($exit_code)"
    else
      bad "kill -9 não apareceu como CRASHED em até ${CRASH_BUDGET_MS} ms"
      crash_ms=null
      exit_code=null
    fi
    quit_app ""
  done

  if [ "$record" = "yes" ]; then
    local warm_csv
    warm_csv="$(IFS=,; printf '%s' "${warm[*]}")"
    write_record "$cold" "${warm_csv//,/, }" "$(median_of "${warm[@]}")" \
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
