#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SELF="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")"
MAIN_CLASS="br.pucpr.tcc.strata.analyzer.bootstrap.Main"
HANDSHAKE_MS=10000
ORDINARY_MS=30000
START_MS=10000
CANCEL_MS=5000
SHUTDOWN_MS=4000
ANALYSIS_MS=120000
FIRST_TICK_BUDGET_MS=250
CANCEL_DEADLINE_MS=2000
STAGES="REPOSITORY_VALIDATION LAYOUT_DISCOVERY SOURCE_ANALYSIS GIT_HISTORY_CHEAP STRUCTURAL_GRAPH GIT_HISTORY_DETAILED EVOLUTIONARY_COUPLING HOTSPOT_PROFILES GUIDED_TOUR PERSISTENCE"
CONFIG='{"historyScope":"ALL","historyScopeCommits":null,"includeTests":true,"rebuildCache":false,"sourceRootIds":null}'
SELECTED_KINDS="MAIN GENERATED TEST"

JAVA=""
ANALYZER_CLASSPATH=""
TIMING="strict"
CANCEL="no"
REPOSITORY=""
REPOSITORY_JSON=""
SCRATCH=""
PID=""
PROBLEMS=0
NEXT_REQUEST=0
REQUEST_ID=""
FRAME=""
FRAME_MS=0
SENT_MS=0
LAST_STAMP_MS=0
REQUEST_SENT_MS=0
RESPONSE_MS=0
DESCRIBED_JAVA_FILES=""
ANALYSIS_ID=""
TERMINAL=""
STRAYS_EXPECTED="no"

usage() {
  cat <<'USAGE'
verify-analysis.sh: roda uma análise no analisador por stdin/stdout reais e confere as mensagens trocadas

  bash scripts/verify-analysis.sh <repositório>
  bash scripts/verify-analysis.sh --cancel-after-first-tick <repositório>
  bash scripts/verify-analysis.sh --ci <repositório>
  bash scripts/verify-analysis.sh --no-latency --java <java> --classpath <cp> <repositório>
  bash scripts/verify-analysis.sh --self-test

  --cancel-after-first-tick  envia analysis.cancel no primeiro tick; a execução tem de terminar em analysis.cancelled
  --no-latency               mostra os tempos como linhas de medida em vez de julgá-los
  --ci                       o mesmo, para um runner compartilhado que não é a máquina-alvo
  --java <caminho>           o java a executar (padrão: $JAVA_HOME/bin/java, depois java_home -v 25, depois o PATH)
  --classpath <cp>           o classpath do analisador (padrão: o único jar em analyzer/target + analyzer/target/lib/*)
  --self-test                prova que o verificador rejeita toda transcrição ruim de um analisador falso (sem JDK)

Saída: 0 tudo certo · 1 alguma verificação falhou · 2 erro de uso ou de ambiente
USAGE
}

die() { echo "verify-analysis.sh: $1" >&2; exit 2; }
bad() { echo "  falha  $1"; PROBLEMS=$((PROBLEMS + 1)); }
good() { echo "  ok     $1"; }
measure() { echo "  medida  $1"; }

if [ -n "${EPOCHREALTIME:-}" ]; then
  now_ms() { local t="${EPOCHREALTIME/[.,]/}"; echo $((10#$t / 1000)); }
elif case "$(date +%3N 2>/dev/null)" in [0-9][0-9][0-9]) true ;; *) false ;; esac; then
  now_ms() { date +%s%3N; }
elif case "$(date +%N 2>/dev/null)" in [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) true ;; *) false ;; esac; then
  now_ms() { local n; n="$(date +%s%N)"; echo "${n%??????}"; }
else
  now_ms() { /usr/bin/perl -MTime::HiRes=time -e 'printf "%d\n", time() * 1000'; }
fi

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then kill -9 "$PID" 2>/dev/null || true; fi
  exec 3>&- 4<&-
  if [ -n "$SCRATCH" ] && [ -d "$SCRATCH" ]; then rm -rf "$SCRATCH"; fi
}

resolve_java() {
  local home=""
  if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
  elif [ "$(uname -s)" = "Darwin" ] && home="$(/usr/libexec/java_home -v 25 2>/dev/null)"; then
    JAVA="$home/bin/java"
  else
    JAVA="$(command -v java || true)"
  fi
  [ -n "$JAVA" ] || die "nenhum java: aponte JAVA_HOME para um JDK 25 ou passe --java"
}

dev_classpath() {
  local jars=() candidate
  for candidate in "$REPO_ROOT"/analyzer/target/analyzer-*.jar; do
    case "$candidate" in
      *-sources.jar | *-javadoc.jar | *-tests.jar) ;;
      *) if [ -f "$candidate" ]; then jars+=("$candidate"); fi ;;
    esac
  done
  [ "${#jars[@]}" -eq 1 ] \
    || die "era esperado exatamente um jar do analisador em analyzer/target, e há ${#jars[@]}; rode (cd analyzer && ./mvnw verify)"
  [ -d "$REPO_ROOT/analyzer/target/lib" ] || die "não há analyzer/target/lib; rode (cd analyzer && ./mvnw verify)"
  ANALYZER_CLASSPATH="${jars[0]}:$REPO_ROOT/analyzer/target/lib/*"
}

json_string() { printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"; }

next_id() { NEXT_REQUEST=$((NEXT_REQUEST + 1)); printf -v REQUEST_ID 'req_%026d' "$NEXT_REQUEST"; }

request_frame() {
  local id="$1" method="$2" payload="$3" path_json="${4:-}" frame
  frame="{\"id\":\"$id\",\"kind\":\"request\",\"method\":\"$method\",\"payload\":$payload,\"protocol\":1"
  if [ -n "$path_json" ]; then frame="$frame,\"repositoryPath\":$path_json"; fi
  printf '%s}' "$frame"
}

send() {
  SENT_MS="$(now_ms)"; LAST_STAMP_MS="$SENT_MS"
  if ! printf '%s\n' "$1" >&3 2>/dev/null; then :; fi
}

next_frame() {
  local deadline_ms="$1" remaining status
  remaining=$(( (deadline_ms - LAST_STAMP_MS + 999) / 1000 ))
  if [ "$remaining" -lt 1 ]; then return 2; fi
  FRAME=""
  if IFS= read -r -t "$remaining" FRAME <&4; then
    FRAME_MS="$(now_ms)"; LAST_STAMP_MS="$FRAME_MS"
    return 0
  fi
  status=$?
  LAST_STAMP_MS="$(now_ms)"
  if [ "$status" -gt 128 ]; then return 2; fi
  return 1
}

why() { if [ "$1" -eq 1 ]; then echo "o analisador fechou o stdout (morreu?)"; else echo "prazo esgotado"; fi; }

str_field() {
  case "$FRAME" in *"\"$1\":\""*) ;; *) REPLY=""; return 0 ;; esac
  local rest="${FRAME#*\"$1\":\"}"
  REPLY="${rest%%\"*}"
}
num_field() {
  case "$FRAME" in *"\"$1\":"*) ;; *) REPLY=""; return 0 ;; esac
  local rest="${FRAME#*\"$1\":}"
  REPLY="${rest%%[,\}]*}"
}
id_field() {
  case "$FRAME" in *'"id":"req_'*) ;; *) REPLY=""; return 0 ;; esac
  local rest="${FRAME#*\"id\":\"req_}"
  REPLY="req_${rest%%\"*}"
}

is_count() { case "$1" in '' | *[!0-9]*) return 1 ;; *) return 0 ;; esac; }

is_analysis_id() {
  local body="${1#an_}"
  if [ "$body" = "$1" ] || [ "${#body}" -ne 26 ]; then return 1; fi
  case "$body" in [01234567]*) ;; *) return 1 ;; esac
  case "$body" in *[!0123456789ABCDEFGHJKMNPQRSTVWXYZ]*) return 1 ;; esac
  return 0
}

frame_name() {
  str_field event
  if [ -n "$REPLY" ]; then printf '%s' "$REPLY"; else printf 'um frame (%s)' "${FRAME:0:120}"; fi
}

launch() {
  local data="$1"
  mkfifo "$SCRATCH/stdin" "$SCRATCH/stdout" || die "não foi possível criar dois FIFOs em $SCRATCH"
  ( cd "$data" && exec env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS -u CLASSPATH "$JAVA" \
      -Xmx2g -XX:+ExitOnOutOfMemoryError -Xss2m \
      -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
      -Duser.language=en -Duser.country=US -Djava.awt.headless=true \
      --enable-native-access=ALL-UNNAMED \
      "-Djava.io.tmpdir=$data" "-Dorg.sqlite.tmpdir=$data" \
      -cp "$ANALYZER_CLASSPATH" "$MAIN_CLASS" "--data-dir=$data" ) \
    < "$SCRATCH/stdin" > "$SCRATCH/stdout" 2> "$SCRATCH/stderr.log" &
  PID=$!
  exec 3> "$SCRATCH/stdin"
  exec 4< "$SCRATCH/stdout"
}

handshake() {
  local spawned="$1" status=0
  next_frame $((spawned + HANDSHAKE_MS)) || status=$?
  if [ "$status" -ne 0 ]; then bad "nenhum protocol.ready em até $((HANDSHAKE_MS / 1000)) s: $(why "$status")"; return 1; fi
  case "$FRAME" in
    *'"event":"protocol.ready"'*'"protocol":1'*) good "protocol.ready em $((FRAME_MS - spawned)) ms desde o spawn" ;;
    *) bad "a primeira linha do stdout não é protocol.ready: ${FRAME:0:200}"; return 1 ;;
  esac
}

unexpected_frame() {
  if [ "$STRAYS_EXPECTED" = yes ]; then return 0; fi
  if [ -n "$TERMINAL" ]; then bad "$(frame_name) chegou depois do evento terminal $TERMINAL"; return 0; fi
  bad "$1: $(frame_name) chegou antes da resposta"
  return 1
}

request() {
  local method="$1" payload="$2" budget="$3" path_json="${4:-}" id status
  next_id; id="$REQUEST_ID"
  send "$(request_frame "$id" "$method" "$payload" "$path_json")"
  REQUEST_SENT_MS="$SENT_MS"
  while :; do
    status=0; next_frame $((REQUEST_SENT_MS + budget)) || status=$?
    if [ "$status" -ne 0 ]; then bad "$method: sem resposta em até $((budget / 1000)) s: $(why "$status")"; return 1; fi
    case "$FRAME" in
      *'"kind":"response"'*)
        id_field
        if [ "$REPLY" = "$id" ]; then RESPONSE_MS="$FRAME_MS"; return 0; fi ;;
    esac
    unexpected_frame "$method" || return 1
  done
}

describe() {
  local roots summary total_file_count
  request repository.describe '{}' "$ORDINARY_MS" "$REPOSITORY_JSON" || return 1
  case "$FRAME" in
    *'"ok":true'*) ;;
    *) str_field code; bad "repository.describe recusado com ${REPLY:-um erro ilegível}"; return 1 ;;
  esac
  roots="$(printf '%s\n' "$FRAME" | grep -o '"javaFileCount":[0-9]*,"kind":"[A-Z]*"' || true)"
  if [ -z "$roots" ]; then bad "repository.describe não informou nenhuma raiz de código que este script consiga ler: ${FRAME:0:200}"; return 1; fi
  summary="$(printf '%s\n' "$roots" | sed 's/^"javaFileCount":\([0-9]*\),"kind":"\([A-Z]*\)"$/\1 \2/' \
    | awk -v kinds=" $SELECTED_KINDS " '{ roots++; if (index(kinds, " " $2 " ") > 0) selected += $1 } END { printf "%d %d\n", roots, selected }')"
  DESCRIBED_JAVA_FILES="${summary#* }"
  total_file_count="$(printf '%s\n' "$FRAME" | grep -o '"totalFileCount":[0-9]*' | tail -1 | sed 's/.*://' || true)"
  good "repository.describe ok em $((RESPONSE_MS - REQUEST_SENT_MS)) ms: ${summary% *} raiz(es), $DESCRIBED_JAVA_FILES .java selecionados, totalFileCount ${total_file_count:-?}"
}

accept_start_response() {
  case "$FRAME" in
    *'"ok":true'*) ;;
    *'"code":"PROTO_UNKNOWN_METHOD"'*) bad "analysis.start desconhecido por este analisador (PROTO_UNKNOWN_METHOD)"; return 1 ;;
    *) str_field code; bad "analysis.start recusado com ${REPLY:-um erro ilegível}"; return 1 ;;
  esac
  str_field analysisId; ANALYSIS_ID="$REPLY"
  if ! is_analysis_id "$ANALYSIS_ID"; then bad "analysis.start não respondeu com um analysisId válido: ${FRAME:0:200}"; return 1; fi
  good "analysis.start respondeu em $((RUN_RESPONSE_MS - RUN_T0)) ms com $ANALYSIS_ID"
  case "$FRAME" in
    *'"reusedCache":false'*) good "reusedCache é false em uma pasta de dados nova" ;;
    *) bad "reusedCache não é false em uma pasta de dados nova: ${FRAME:0:200}" ;;
  esac
}

send_cancel() {
  next_id; CANCEL_ID="$REQUEST_ID"
  send "$(request_frame "$CANCEL_ID" analysis.cancel "{\"analysisId\":\"$ANALYSIS_ID\"}")"
  CANCEL_SENT_MS="$SENT_MS"
}

on_response() {
  id_field
  if [ -z "$CANCEL_ID" ] || [ "$REPLY" != "$CANCEL_ID" ] || [ -n "$CANCEL_ANSWER" ]; then
    bad "uma resposta que ninguém esperava: ${FRAME:0:200}"; return 0
  fi
  if [ -n "$TERMINAL" ]; then CANCEL_AFTER_TERMINAL=yes; fi
  local took=$((FRAME_MS - CANCEL_SENT_MS))
  case "$FRAME" in
    *'"cancelled":true'*) CANCEL_ANSWER=true; good "analysis.cancel -> {cancelled: true} em $took ms" ;;
    *'"cancelled":false'*) CANCEL_ANSWER=false; bad "analysis.cancel -> {cancelled: false}: a execução não estava rodando quando o cancelamento chegou" ;;
    *) CANCEL_ANSWER=error; str_field code; bad "analysis.cancel recusado com ${REPLY:-um erro ilegível}" ;;
  esac
  if [ "$took" -gt "$CANCEL_MS" ]; then bad "analysis.cancel respondeu em $took ms, acima do limite de $CANCEL_MS ms"; fi
}

on_progress() {
  local stage completed total
  str_field stage; stage="$REPLY"
  case " $RUN_STAGES " in *" $stage "*) bad "um tick de $stage depois do analysis.stage.completed da etapa" ;; esac
  if [ -z "$RUN_FIRST_TICK_MS" ]; then
    RUN_FIRST_TICK_MS="$FRAME_MS"; RUN_FIRST_STAGE="$stage"
    if [ "$CANCEL" = yes ]; then send_cancel; fi
  fi
  if [ "$stage" != SOURCE_ANALYSIS ]; then return 0; fi
  str_field unit
  if [ "$REPLY" != JAVA_FILES ]; then bad "um tick de SOURCE_ANALYSIS conta ${REPLY:-nada}, e não JAVA_FILES"; fi
  num_field completed; completed="$REPLY"; num_field total; total="$REPLY"
  if ! is_count "$completed" || ! is_count "$total"; then bad "um tick de SOURCE_ANALYSIS sem completed e total numéricos: ${FRAME:0:200}"; return 0; fi
  if [ -n "$RUN_SOURCE_TOTAL" ] && [ "$total" != "$RUN_SOURCE_TOTAL" ]; then bad "o total de SOURCE_ANALYSIS mudou: $RUN_SOURCE_TOTAL -> $total"; fi
  if [ -n "$RUN_SOURCE_LAST" ] && [ "$completed" -lt "$RUN_SOURCE_LAST" ]; then bad "o progresso de SOURCE_ANALYSIS voltou: $RUN_SOURCE_LAST -> $completed"; fi
  if [ "$completed" -gt "$total" ]; then bad "um tick de SOURCE_ANALYSIS além do total: $completed de $total"; fi
  RUN_SOURCE_LAST="$completed"; RUN_SOURCE_TOTAL="$total"
}

on_stage_completed() {
  local stage status
  str_field stage; stage="$REPLY"; str_field status; status="$REPLY"
  case " $STAGES " in *" $stage "*) ;; *) bad "analysis.stage.completed de uma etapa desconhecida '$stage'"; return 0 ;; esac
  case " $RUN_STAGES " in *" $stage "*) bad "um segundo analysis.stage.completed para $stage" ;; esac
  RUN_STAGES="${RUN_STAGES:+$RUN_STAGES }$stage"
  case "$status" in
    COMPLETED | SKIPPED) ;;
    CANCELLED) if [ "$CANCEL" != yes ]; then bad "$stage terminou com CANCELLED, e ninguém cancelou"; fi ;;
    *) bad "$stage terminou com ${status:-status vazio}" ;;
  esac
}

on_event() {
  local event
  str_field event; event="$REPLY"
  case "$event" in
    analysis.*)
      str_field analysisId
      if [ "$REPLY" != "$ANALYSIS_ID" ]; then bad "$event traz analysisId '$REPLY', e não $ANALYSIS_ID"; fi ;;
  esac
  if [ -z "$RUN_STARTED_MS" ] && [ "$event" != analysis.started ]; then bad "$(frame_name) chegou antes de analysis.started"; fi
  case "$event" in
    analysis.started)
      if [ -n "$RUN_STARTED_MS" ]; then bad "um segundo analysis.started"; else RUN_STARTED_MS="$FRAME_MS"; fi ;;
    analysis.progress) on_progress ;;
    analysis.stage.completed) on_stage_completed ;;
    analysis.warning | cache.invalidated) ;;
    analysis.completed | analysis.cancelled | analysis.failed)
      TERMINAL="$event"; RUN_TERMINAL_MS="$FRAME_MS"
      num_field totalFiles; RUN_TOTAL_FILES="$REPLY"
      if [ "$event" = analysis.failed ]; then str_field code; RUN_ERROR="$REPLY"; fi ;;
    *) bad "um frame inesperado durante a análise: ${FRAME:0:200}" ;;
  esac
}

await_cancel_answer() {
  local status
  if [ "$CANCEL" != yes ] || [ -z "$CANCEL_ID" ] || [ -n "$CANCEL_ANSWER" ]; then return 0; fi
  while [ -z "$CANCEL_ANSWER" ]; do
    status=0; next_frame $((CANCEL_SENT_MS + CANCEL_MS)) || status=$?
    if [ "$status" -ne 0 ]; then bad "analysis.cancel: sem resposta em até $((CANCEL_MS / 1000)) s: $(why "$status")"; return 1; fi
    case "$FRAME" in
      *'"kind":"response"'*) on_response ;;
      *) bad "$(frame_name) chegou depois do evento terminal $TERMINAL" ;;
    esac
  done
}

report_first_tick() {
  if [ -z "$RUN_FIRST_TICK_MS" ]; then bad "nenhum analysis.progress"; return 0; fi
  local latency=$((RUN_FIRST_TICK_MS - RUN_T0)) started="n/d"
  if [ -n "$RUN_STARTED_MS" ]; then started="$((RUN_STARTED_MS - RUN_T0)) ms"; fi
  measure "primeiro analysis.progress ($RUN_FIRST_STAGE) $latency ms depois da escrita do analysis.start (meta < $FIRST_TICK_BUDGET_MS ms) · resposta $((RUN_RESPONSE_MS - RUN_T0)) ms · started $started"
  if [ "$TIMING" != strict ]; then return 0; fi
  if [ "$latency" -lt "$FIRST_TICK_BUDGET_MS" ]; then
    good "primeiro tick abaixo de $FIRST_TICK_BUDGET_MS ms"
  else
    bad "o primeiro tick levou $latency ms, e a meta é < $FIRST_TICK_BUDGET_MS ms"
  fi
}

judge_cancelled_run() {
  if [ "$CANCEL_ANSWER" = true ] && [ "${CANCEL_AFTER_TERMINAL:-no}" = yes ]; then
    bad "a resposta {cancelled: true} chegou depois do evento terminal $TERMINAL"
  fi
  if [ "$TERMINAL" != analysis.cancelled ]; then
    bad "a execução terminou com $TERMINAL${RUN_ERROR:+ ($RUN_ERROR)}, e não com analysis.cancelled"; return 0
  fi
  local took=$((RUN_TERMINAL_MS - CANCEL_SENT_MS))
  measure "analysis.cancelled $took ms depois da escrita do analysis.cancel (prazo $CANCEL_DEADLINE_MS ms)"
  if [ "$TIMING" != strict ]; then return 0; fi
  if [ "$took" -le "$CANCEL_DEADLINE_MS" ]; then
    good "cancelado dentro do prazo de $CANCEL_DEADLINE_MS ms"
  else
    bad "o cancelamento levou $took ms, e o prazo é $CANCEL_DEADLINE_MS ms"
  fi
}

judge_stages() {
  local stage count=0 ordered="" expected="" layout_done=no sibling_ok=no
  for stage in $STAGES; do
    if [ "$stage" != GIT_HISTORY_CHEAP ]; then expected="${expected:+$expected }$stage"; fi
  done
  for stage in $RUN_STAGES; do
    count=$((count + 1))
    case "$stage" in
      LAYOUT_DISCOVERY) layout_done=yes ;;
      GIT_HISTORY_CHEAP) sibling_ok="$layout_done" ;;
      *) ;;
    esac
    if [ "$stage" != GIT_HISTORY_CHEAP ]; then ordered="${ordered:+$ordered }$stage"; fi
  done
  if [ "$count" -ne 10 ]; then bad "$count analysis.stage.completed, e eram esperados exatamente dez: $RUN_STAGES"; return 0; fi
  if [ "$ordered" != "$expected" ]; then bad "as etapas terminaram fora da ordem de AnalysisStage: $RUN_STAGES"; return 0; fi
  if [ "$sibling_ok" != yes ]; then bad "GIT_HISTORY_CHEAP terminou antes de LAYOUT_DISCOVERY: $RUN_STAGES"; return 0; fi
  good "dez analysis.stage.completed na ordem de AnalysisStage, com GIT_HISTORY_CHEAP entre eles"
}

judge_completed_run() {
  if [ "$TERMINAL" != analysis.completed ]; then bad "a análise terminou com $TERMINAL${RUN_ERROR:+ ($RUN_ERROR)}"; return 0; fi
  good "analysis.completed $((RUN_TERMINAL_MS - RUN_T0)) ms depois da escrita do analysis.start"
  judge_stages
  if [ "$RUN_TOTAL_FILES" = "$DESCRIBED_JAVA_FILES" ]; then
    good "completeness.totalFiles $RUN_TOTAL_FILES = a soma de javaFileCount nas raízes selecionadas do describe da mesma sessão"
  else
    bad "completeness.totalFiles é '$RUN_TOTAL_FILES', mas o describe da mesma sessão conta $DESCRIBED_JAVA_FILES nas raízes selecionadas"
  fi
  if [ "$RUN_SOURCE_TOTAL" = "$RUN_TOTAL_FILES" ] && [ "$RUN_SOURCE_LAST" = "$RUN_TOTAL_FILES" ]; then
    good "SOURCE_ANALYSIS conta JAVA_FILES de forma monotônica, terminando em $RUN_SOURCE_LAST de $RUN_SOURCE_TOTAL"
  else
    bad "os ticks de SOURCE_ANALYSIS terminam em '${RUN_SOURCE_LAST:-nenhum}' de '${RUN_SOURCE_TOTAL:-nenhum}', totalFiles '$RUN_TOTAL_FILES'"
  fi
}

analyze() {
  local deadline status
  request analysis.start "$CONFIG" "$START_MS" "$REPOSITORY_JSON" || return 1
  RUN_T0="$REQUEST_SENT_MS"; RUN_RESPONSE_MS="$RESPONSE_MS"
  accept_start_response || return 1
  RUN_STARTED_MS=""; RUN_FIRST_TICK_MS=""; RUN_FIRST_STAGE=""; RUN_STAGES=""; RUN_ERROR=""
  RUN_SOURCE_LAST=""; RUN_SOURCE_TOTAL=""; RUN_TOTAL_FILES=""; RUN_TERMINAL_MS=""
  CANCEL_ID=""; CANCEL_SENT_MS=0; CANCEL_ANSWER=""; CANCEL_AFTER_TERMINAL=no
  deadline=$((RUN_T0 + ANALYSIS_MS))
  while [ -z "$TERMINAL" ]; do
    status=0; next_frame "$deadline" || status=$?
    if [ "$status" -ne 0 ]; then bad "nenhum evento terminal da análise em até $((ANALYSIS_MS / 1000)) s: $(why "$status")"; return 1; fi
    case "$FRAME" in
      *'"kind":"response"'*) on_response ;;
      *) on_event ;;
    esac
  done
  await_cancel_answer || return 1
  report_first_tick
  if [ "$CANCEL" = yes ]; then judge_cancelled_run; else judge_completed_run; fi
}

drain_after_shutdown() {
  local deadline="$1" status
  while :; do
    status=0; next_frame "$deadline" || status=$?
    if [ "$status" -ne 0 ]; then return 0; fi
    if [ "$STRAYS_EXPECTED" = yes ]; then continue; fi
    if [ -n "$TERMINAL" ]; then
      bad "$(frame_name) chegou depois do evento terminal $TERMINAL"
    else
      bad "$(frame_name) chegou depois da resposta ao system.shutdown"
    fi
  done
}

shutdown_analyzer() {
  local code=0 deadline
  if [ -z "$PID" ]; then return 0; fi
  if ! kill -0 "$PID" 2>/dev/null; then
    wait "$PID" || code=$?; PID=""
    bad "o analisador já tinha saído ($code) antes do system.shutdown"; return 1
  fi
  request system.shutdown '{}' "$ORDINARY_MS" || return 1
  deadline=$((REQUEST_SENT_MS + SHUTDOWN_MS))
  exec 3>&-
  drain_after_shutdown "$deadline"
  while kill -0 "$PID" 2>/dev/null && [ "$(now_ms)" -lt "$deadline" ]; do sleep 0.1; done
  if kill -0 "$PID" 2>/dev/null; then bad "o analisador não saiu em até $((SHUTDOWN_MS / 1000)) s depois do system.shutdown"; return 1; fi
  wait "$PID" || code=$?; PID=""
  if [ "$code" -eq 0 ]; then good "system.shutdown -> saída 0"; else bad "saída $code depois do system.shutdown"; fi
}

FAKE_ID="an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W"
FAKE_DESCRIBE='{"buildSystem":"MAVEN","cachedAnalysisId":null,"declaredModules":["core","api"],"defaultBranch":"main","displayName":"multi-module","headCommitId":"fc5c752e7e9610c39de3b7b7caf2a74b7dfe6249","isBare":false,"isGitRepository":true,"isShallow":false,"sourceRoots":[{"id":"sr_sqbycfms","javaFileCount":2,"kind":"MAIN","label":"api/src/main/java"},{"id":"sr_3gwc7d6d","javaFileCount":6,"kind":"MAIN","label":"core/src/main/java"},{"id":"sr_jc51sw4p","javaFileCount":1,"kind":"TEST","label":"core/src/test/java"},{"id":"sr_3sscth34","javaFileCount":1,"kind":"GENERATED","label":"core/target/generated-sources/annotations"}],"totalFileCount":14}'
FAKE_REQUEST_ID=""
FAKE_METHOD=""

fake_emit() { printf '%s\n' "$1"; }
fake_event() { fake_emit "{\"event\":\"$1\",\"kind\":\"event\",\"payload\":$2,\"protocol\":1}"; }
fake_respond() { fake_emit "{\"id\":\"$1\",\"kind\":\"response\",\"ok\":true,\"payload\":$2,\"protocol\":1}"; }
fake_tick() { fake_event analysis.progress "{\"analysisId\":\"${5:-$FAKE_ID}\",\"completed\":$2,\"stage\":\"$1\",\"total\":$3,\"unit\":\"$4\"}"; }
fake_stage() { fake_event analysis.stage.completed "{\"analysisId\":\"$FAKE_ID\",\"counters\":{},\"durationMs\":1,\"stage\":\"$1\",\"status\":\"$2\"}"; }
fake_terminal() {
  fake_event "$1" "{\"analysisId\":\"$FAKE_ID\",\"completeness\":{\"historyTruncatedAtCommits\":null,\"parseFailures\":0,\"shallowClone\":false,\"skippedFiles\":0,\"totalFiles\":$2}}"
}
fake_warning() {
  fake_event analysis.warning "{\"analysisId\":\"$FAKE_ID\",\"error\":{\"code\":\"SOURCE_PARSE_FAILED\",\"origin\":\"analyzer\",\"params\":{\"filePath\":\"core/src/main/java/br/pucpr/legacy/core/Kind.java\"}}}"
}

fake_fields() {
  local rest
  FAKE_REQUEST_ID=""; FAKE_METHOD=""
  case "$1" in *'"id":"'*) rest="${1#*\"id\":\"}"; FAKE_REQUEST_ID="${rest%%\"*}" ;; esac
  case "$1" in *'"method":"'*) rest="${1#*\"method\":\"}"; FAKE_METHOD="${rest%%\"*}" ;; esac
}

fake_rest() {
  local mode="$1" source_status=COMPLETED total=10 stage
  local later="STRUCTURAL_GRAPH GIT_HISTORY_DETAILED EVOLUTIONARY_COUPLING HOTSPOT_PROFILES GUIDED_TOUR"
  fake_tick REPOSITORY_VALIDATION 10 10 JAVA_FILES
  fake_warning
  if [ "$mode" = early-sibling ]; then fake_stage GIT_HISTORY_CHEAP SKIPPED; fi
  fake_stage REPOSITORY_VALIDATION COMPLETED
  fake_stage LAYOUT_DISCOVERY COMPLETED
  fake_tick SOURCE_ANALYSIS 0 10 JAVA_FILES
  case "$mode" in
    wrong-unit) fake_tick SOURCE_ANALYSIS 6 10 COMMITS ;;
    foreign-id) fake_tick SOURCE_ANALYSIS 6 10 JAVA_FILES an_7ZZZZZZZZZZZZZZZZZZZZZZZZZ ;;
    ticks-go-back) fake_tick SOURCE_ANALYSIS 6 10 JAVA_FILES; fake_tick SOURCE_ANALYSIS 4 10 JAVA_FILES ;;
    *) fake_tick SOURCE_ANALYSIS 6 10 JAVA_FILES ;;
  esac
  case "$mode" in early-sibling | late-sibling) ;; *) fake_stage GIT_HISTORY_CHEAP SKIPPED ;; esac
  case "$mode" in short-ticks | tick-after-stage) ;; *) fake_tick SOURCE_ANALYSIS 10 10 JAVA_FILES ;; esac
  case "$mode" in stage-failed | analysis-failed) source_status=FAILED ;; esac
  fake_stage SOURCE_ANALYSIS "$source_status"
  if [ "$mode" = tick-after-stage ]; then fake_tick SOURCE_ANALYSIS 10 10 JAVA_FILES; fi
  if [ "$mode" = analysis-failed ]; then
    fake_event analysis.failed "{\"analysisId\":\"$FAKE_ID\",\"error\":{\"code\":\"INTERNAL_UNEXPECTED\",\"detail\":\"java.lang.IllegalStateException\",\"origin\":\"analyzer\",\"params\":{}}}"
    return 0
  fi
  case "$mode" in
    stage-missing) later="STRUCTURAL_GRAPH GIT_HISTORY_DETAILED EVOLUTIONARY_COUPLING HOTSPOT_PROFILES" ;;
    stages-out-of-order) later="STRUCTURAL_GRAPH GIT_HISTORY_DETAILED EVOLUTIONARY_COUPLING GUIDED_TOUR HOTSPOT_PROFILES" ;;
  esac
  for stage in $later; do fake_stage "$stage" SKIPPED; done
  if [ "$mode" = late-sibling ]; then fake_stage GIT_HISTORY_CHEAP SKIPPED; fi
  fake_stage PERSISTENCE COMPLETED
  if [ "$mode" = wrong-total ]; then total=9; fi
  fake_terminal analysis.completed "$total"
  if [ "$mode" = event-after-terminal ]; then fake_warning; fi
}

fake_cancel() {
  local mode="$1" line
  IFS= read -r line || exit 0
  fake_fields "$line"
  case "$mode" in
    cancelable) fake_respond "$FAKE_REQUEST_ID" '{"cancelled":true}'
      fake_stage REPOSITORY_VALIDATION CANCELLED; fake_terminal analysis.cancelled 0 ;;
    slow-cancel) fake_respond "$FAKE_REQUEST_ID" '{"cancelled":true}'; sleep 2.2
      fake_stage REPOSITORY_VALIDATION CANCELLED; fake_terminal analysis.cancelled 0 ;;
    cancelled-before-response) fake_stage REPOSITORY_VALIDATION CANCELLED; fake_terminal analysis.cancelled 0
      fake_respond "$FAKE_REQUEST_ID" '{"cancelled":true}' ;;
    cancel-refused) fake_respond "$FAKE_REQUEST_ID" '{"cancelled":false}'; fake_rest ok ;;
    cancel-ignored) fake_respond "$FAKE_REQUEST_ID" '{"cancelled":true}'; fake_rest ok ;;
  esac
}

fake_start() {
  local mode="$1" id="$2" reused=false
  if [ "$mode" = unknown-method ]; then
    fake_emit "{\"error\":{\"code\":\"PROTO_UNKNOWN_METHOD\",\"detail\":\"nenhum handler está registrado para o método pedido\",\"origin\":\"analyzer\",\"params\":{\"method\":\"analysis.start\"}},\"id\":\"$id\",\"kind\":\"response\",\"ok\":false,\"protocol\":1}"
    return 0
  fi
  if [ "$mode" = event-before-response ]; then fake_event analysis.started "{\"analysisId\":\"$FAKE_ID\"}"; fi
  if [ "$mode" = reused-cache ]; then reused=true; fi
  fake_respond "$id" "{\"analysisId\":\"$FAKE_ID\",\"reusedCache\":$reused}"
  if [ "$mode" = progress-before-started ]; then fake_tick REPOSITORY_VALIDATION 0 null JAVA_FILES; fi
  if [ "$mode" != event-before-response ]; then fake_event analysis.started "{\"analysisId\":\"$FAKE_ID\"}"; fi
  if [ "$mode" = slow-first-tick ]; then sleep 0.4; fi
  fake_tick REPOSITORY_VALIDATION 0 null JAVA_FILES
  case "$mode" in
    cancel* | slow-cancel) fake_cancel "$mode"; return 0 ;;
    dies) exit 137 ;;
  esac
  fake_rest "$mode"
}

fake_analyzer() {
  local mode="$1" line
  fake_emit '{"event":"protocol.ready","kind":"event","payload":{"analyzerVersion":"0.0.0-fake","capabilities":[],"protocol":1},"protocol":1}'
  while IFS= read -r line; do
    fake_fields "$line"
    case "$FAKE_METHOD" in
      repository.describe) fake_respond "$FAKE_REQUEST_ID" "$FAKE_DESCRIBE" ;;
      analysis.start) fake_start "$mode" "$FAKE_REQUEST_ID" ;;
      analysis.cancel) fake_respond "$FAKE_REQUEST_ID" '{"cancelled":false}' ;;
      system.shutdown)
        fake_respond "$FAKE_REQUEST_ID" '{}'
        case "$mode" in
          bad-exit) exit 3 ;;
          no-exit) exec 1>&-; for _ in 1 2 3 4 5 6 7 8; do sleep 1; done; exit 0 ;;
          *) exit 0 ;;
        esac ;;
      *) ;;
    esac
  done
  exit 0
}

self_test_case() {
  local dir="$1" verdict="$2" mode="$3" flags="$4" reason="$5" shim="$1/java" output status=0 line
  local label="$mode${flags:+ ($flags)}"
  printf '#!/usr/bin/env bash\nVERIFY_ANALYSIS_FAKE=%q exec %q %q "$@"\n' "$mode" "$BASH" "$SELF" > "$shim"
  chmod +x "$shim"
  output="$(env -u VERIFY_ANALYSIS_FAKE "$BASH" "$SELF" $flags --java "$shim" --classpath fake.jar \
    "$dir/repository" </dev/null 2>&1)" || status=$?
  if [ "$verdict" = accept ] && [ "$status" -eq 0 ]; then
    case "$output" in *"$reason"*) echo "  aceito     $label"; return 0 ;; esac
  fi
  if [ "$verdict" = reject ] && [ "$status" -eq 1 ]; then
    line="$(printf '%s\n' "$output" | grep -F -- '  falha  ' | grep -F -- "$reason" | head -1 || true)"
    if [ -n "$line" ]; then echo "  rejeitado  $label: ${line#  falha  }"; return 0; fi
  fi
  echo "  errado     $label: saída $status, esperado $verdict com \"$reason\""
  printf '%s\n' "$output" | sed 's/^/      | /'
  return 1
}

self_test() {
  local verdict mode flags reason cases=0 wrong=0
  command -v mkfifo >/dev/null 2>&1 || die "o mkfifo é necessário"
  SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/verify-analysis-self-test.XXXXXX")"
  SCRATCH="$(CDPATH= cd -- "$SCRATCH" && pwd -P)"
  trap cleanup EXIT
  mkdir "$SCRATCH/repository"
  echo "verify-analysis --self-test · o verificador contra um analisador falso"
  while IFS='|' read -r verdict mode flags reason; do
    cases=$((cases + 1))
    if ! self_test_case "$SCRATCH" "$verdict" "$mode" "$flags" "$reason"; then wrong=$((wrong + 1)); fi
  done <<'CASES'
accept|ok||análise verificada
accept|late-sibling||análise verificada
accept|cancelable|--cancel-after-first-tick|análise verificada
accept|slow-first-tick|--no-latency|medida  primeiro analysis.progress
accept|slow-first-tick|--ci|medida  primeiro analysis.progress
reject|unknown-method||analysis.start desconhecido por este analisador
reject|event-before-response||analysis.started chegou antes da resposta
reject|reused-cache||reusedCache não é false
reject|progress-before-started||analysis.progress chegou antes de analysis.started
reject|foreign-id||traz analysisId 'an_7ZZZZZZZZZZZZZZZZZZZZZZZZZ'
reject|slow-first-tick||o primeiro tick levou
reject|wrong-unit||conta COMMITS, e não JAVA_FILES
reject|ticks-go-back||o progresso de SOURCE_ANALYSIS voltou: 6 -> 4
reject|short-ticks||os ticks de SOURCE_ANALYSIS terminam em '6' de '10'
reject|tick-after-stage||um tick de SOURCE_ANALYSIS depois do analysis.stage.completed da etapa
reject|stage-failed||SOURCE_ANALYSIS terminou com FAILED
reject|analysis-failed||a análise terminou com analysis.failed (INTERNAL_UNEXPECTED)
reject|stage-missing||9 analysis.stage.completed, e eram esperados exatamente dez
reject|stages-out-of-order||fora da ordem de AnalysisStage
reject|early-sibling||GIT_HISTORY_CHEAP terminou antes de LAYOUT_DISCOVERY
reject|event-after-terminal||analysis.warning chegou depois do evento terminal analysis.completed
reject|wrong-total||completeness.totalFiles é '9'
reject|dies||o analisador fechou o stdout
reject|bad-exit||saída 3 depois do system.shutdown
reject|no-exit||não saiu em até 4 s depois do system.shutdown
reject|cancel-refused|--cancel-after-first-tick|{cancelled: false}
reject|cancel-ignored|--cancel-after-first-tick|terminou com analysis.completed, e não com analysis.cancelled
reject|cancelled-before-response|--cancel-after-first-tick|resposta {cancelled: true} chegou depois do evento terminal analysis.cancelled
reject|slow-cancel|--cancel-after-first-tick|o cancelamento levou
CASES
  if [ "$wrong" -eq 0 ]; then echo "autoteste: ok ($cases casos)"; exit 0; fi
  echo "autoteste: $wrong de $cases caso(s) com resultado inesperado"
  exit 1
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --java | --classpath)
        [ $# -ge 2 ] || { usage >&2; exit 2; }
        if [ "$1" = --java ]; then JAVA="$2"; else ANALYZER_CLASSPATH="$2"; fi
        shift 2 ;;
      --no-latency | --ci) TIMING="measure"; shift ;;
      --cancel-after-first-tick) CANCEL="yes"; shift ;;
      --self-test) self_test ;;
      -h | --help) usage; exit 0 ;;
      -*) usage >&2; exit 2 ;;
      *) [ -z "$REPOSITORY" ] || { usage >&2; exit 2; }; REPOSITORY="$1"; shift ;;
    esac
  done
  [ -n "$REPOSITORY" ] || { usage >&2; exit 2; }
}

main() {
  local spawned
  parse_args "$@"
  case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) die "só macOS e Linux; no Windows, quem roda a análise é o build-analyzer.ps1" ;; esac
  [ -d "$REPOSITORY" ] || die "$REPOSITORY não é uma pasta"
  REPOSITORY="$(CDPATH= cd -- "$REPOSITORY" && pwd -P)" || die "não foi possível resolver $REPOSITORY"
  case "$REPOSITORY" in *[[:cntrl:]]*) die "o caminho do repositório tem um caractere de controle" ;; esac
  REPOSITORY_JSON="$(json_string "$REPOSITORY")"
  command -v mkfifo >/dev/null 2>&1 || die "o mkfifo é necessário"
  if [ -z "$JAVA" ]; then resolve_java; fi
  [ -x "$JAVA" ] || die "$JAVA não é um java executável"
  if [ -z "$ANALYZER_CLASSPATH" ]; then dev_classpath; fi

  SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/verify-analysis.XXXXXX")"
  trap cleanup EXIT
  SCRATCH="$(CDPATH= cd -- "$SCRATCH" && pwd -P)"
  mkdir "$SCRATCH/data"
  echo "verify-analysis · $(basename -- "$REPOSITORY") · $JAVA"
  spawned="$(now_ms)"; LAST_STAMP_MS="$spawned"
  launch "$SCRATCH/data"
  trap '' PIPE
  if handshake "$spawned" && describe; then
    if ! analyze; then STRAYS_EXPECTED=yes; fi
  else
    STRAYS_EXPECTED=yes
  fi
  shutdown_analyzer || true
  if [ "$PROBLEMS" -gt 0 ]; then
    echo "$PROBLEMS problema(s). Final do stderr do analisador:"
    tail -20 "$SCRATCH/stderr.log" | sed 's/^/    /'
    exit 1
  fi
  echo "análise verificada"
}

if [ -n "${VERIFY_ANALYSIS_FAKE:-}" ]; then fake_analyzer "$VERIFY_ANALYSIS_FAKE"; fi
main "$@"
