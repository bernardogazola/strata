#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MAIN_CLASS="br.pucpr.tcc.strata.analyzer.bootstrap.Main"
PROBLEMS=0

usage() {
  cat <<'USAGE'
smoke-analyzer.sh: abre o analisador empacotado com o stdin fechado e confere o handshake

  bash scripts/smoke-analyzer.sh
  bash scripts/smoke-analyzer.sh --self-test

Saída: 0 tudo certo · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

bad() { echo "  falha  $1"; PROBLEMS=$((PROBLEMS + 1)); }
good() { echo "  ok     $1"; }

check_handshake() {
  local stdout_file="$1" exit_code="$2" lines

  if [ "$exit_code" -eq 0 ]; then
    good "sai com 0 no EOF do stdin"
  else
    bad "esperada saída 0 no EOF do stdin, obtida $exit_code"
  fi

  lines="$(wc -l < "$stdout_file" | tr -d ' ')"
  if [ "$lines" -eq 1 ]; then
    good "o stdout tem exatamente uma linha"
  else
    bad "o stdout tem $lines linha(s), e era esperada exatamente 1"
  fi

  if grep -q '"event":"protocol.ready"' "$stdout_file"; then
    good "a linha é protocol.ready"
  else
    bad "nenhum frame protocol.ready no stdout"
  fi

  if grep -q '"protocol":1' "$stdout_file"; then
    good "o frame declara protocol 1"
  else
    bad "o frame não declara protocol 1"
  fi

  if grep -q '"capabilities":\[\]' "$stdout_file"; then
    good "capabilities é o array vazio"
  else
    bad "capabilities não é o array vazio"
  fi

  if grep -qE '^\[(INFO|WARN|ERROR)\]' "$stdout_file"; then
    bad "uma linha de log chegou ao stdout; a captura do stdout não está funcionando"
  else
    good "nenhuma linha de log no stdout"
  fi
}

self_test() {
  local tmp
  tmp="$(mktemp)"
  echo "sintético: um handshake correto tem de passar"
  printf '{"event":"protocol.ready","kind":"event","payload":{"analyzerVersion":"0.0.0","capabilities":[],"protocol":1},"protocol":1}\n' > "$tmp"
  check_handshake "$tmp" 0
  local after_good=$PROBLEMS

  echo "sintético: uma linha de log vazando para o stdout tem de falhar"
  printf '[INFO] analyzer: hello\n{"event":"protocol.ready","kind":"event","payload":{"analyzerVersion":"0.0.0","capabilities":[],"protocol":1},"protocol":1}\n' > "$tmp"
  PROBLEMS=0
  check_handshake "$tmp" 0
  rm -f "$tmp"

  if [ "$after_good" -eq 0 ] && [ "$PROBLEMS" -gt 0 ]; then
    PROBLEMS=0
    echo "autoteste ok"
    return 0
  fi
  echo "autoteste falhou: o verificador não distingue os casos" >&2
  return 1
}

case "${1-}" in
  --self-test) self_test; exit $? ;;
  -h|--help) usage; exit 0 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

JAR="$(ls "$REPO_ROOT"/analyzer/target/analyzer-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ] || [ ! -d "$REPO_ROOT/analyzer/target/lib" ]; then
  echo "smoke-analyzer: falta o jar ou o target/lib; rode (cd analyzer && ./mvnw verify) antes" >&2
  exit 2
fi

OUT="$(mktemp)"; ERR="$(mktemp)"
set +e
printf '' | java -cp "$JAR:$REPO_ROOT/analyzer/target/lib/*" "$MAIN_CLASS" \
  --data-dir=/tmp/strata-smoke > "$OUT" 2> "$ERR"
CODE=$?
set -e

echo "smoke-analyzer · $(basename "$JAR")"
check_handshake "$OUT" "$CODE"
rm -f "$OUT" "$ERR"

if [ "$PROBLEMS" -gt 0 ]; then
  echo "$PROBLEMS problema(s)."
  exit 1
fi
echo "handshake ok"
