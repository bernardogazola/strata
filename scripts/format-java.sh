#!/usr/bin/env bash
# O lint-staged anexa os arquivos ao fim do comando, e o Maven leria cada um como goal;
# por isso eles viram -DspotlessFiles=<regex>, cada caminho entre \Q...\E.

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

usage() {
  cat <<'USAGE'
format-java.sh: roda o google-java-format nos arquivos Java indicados

  bash scripts/format-java.sh <arquivo.java>...
  bash scripts/format-java.sh --self-test

Saída: 0 tudo certo · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

spotless_files_argument() {
  local joined=""
  local path
  for path in "$@"; do
    case "$path" in
      *,*) echo "format-java: caminho com vírgula não é suportado: $path" >&2; return 1 ;;
    esac
    if [ -n "$joined" ]; then joined="$joined,"; fi
    joined="$joined\\Q$path\\E"
  done
  printf '%s' "$joined"
}

self_test() {
  local failures=0
  local got

  got="$(spotless_files_argument /a/B.java)"
  if [ "$got" != '\Q/a/B.java\E' ]; then
    echo "falha  caminho único: obtido [$got]" >&2; failures=$((failures + 1))
  else
    echo "ok     caminho único fica entre \\Q...\\E"
  fi

  got="$(spotless_files_argument /a/B.java /c/D.java)"
  if [ "$got" != '\Q/a/B.java\E,\Q/c/D.java\E' ]; then
    echo "falha  dois caminhos: obtido [$got]" >&2; failures=$((failures + 1))
  else
    echo "ok     dois caminhos são unidos por vírgula"
  fi

  if spotless_files_argument "/a/with,comma.java" >/dev/null 2>&1; then
    echo "falha  um caminho com vírgula deveria ter sido recusado" >&2; failures=$((failures + 1))
  else
    echo "ok     um caminho com vírgula é recusado em vez de cortado sem aviso"
  fi

  if [ "$failures" -gt 0 ]; then
    echo "$failures problema(s)."; return 1
  fi
  echo "autoteste ok"
}

if [ "${1-}" = "--self-test" ]; then
  self_test
  exit $?
fi
if [ "${1-}" = "-h" ] || [ "${1-}" = "--help" ]; then
  usage
  exit 0
fi
if [ "$#" -eq 0 ]; then
  exit 0
fi

FILES="$(spotless_files_argument "$@")"
exec "$REPO_ROOT/analyzer/mvnw" -q -f "$REPO_ROOT/analyzer/pom.xml" spotless:apply \
  -DspotlessFiles="$FILES"
