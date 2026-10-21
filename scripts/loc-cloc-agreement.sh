#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

CLOC_VERSION="2.10"
TAB=$(printf '\t')

REPOSITORIES=(commons-lang broken-tree junit5 spring-framework elasticsearch)

usage() {
  cat <<'USAGE'
loc-cloc-agreement.sh: registra a contagem de código do cloc 2.10 para cada arquivo .java do corpus

  bash scripts/loc-cloc-agreement.sh [--corpus <pasta>] [--out <pasta>]

Grava <out>/<repo>.files e <out>/<repo>.tsv para cada um dos cinco clones do corpus;
o <out> padrão é analyzer/target/loc-cloc. Depois rode, em analyzer/:
  ./mvnw -Pspike test -Dtest=LocClocAgreementSpikeIT

Saída: 0 tudo certo · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

require_cloc() {
  local found
  if ! command -v cloc >/dev/null 2>&1; then
    echo "loc-cloc-agreement: é preciso o cloc $CLOC_VERSION (brew install cloc); não há nenhum no PATH" >&2
    return 1
  fi
  found=$(cloc --version 2>/dev/null || true)
  if [ "$found" != "$CLOC_VERSION" ]; then
    echo "loc-cloc-agreement: é preciso o cloc $CLOC_VERSION, encontrado '$found'" >&2
    return 1
  fi
}

java_files() {
  (cd "$1" && find . -name .git -prune -o -type f -name '*.java' -print) | sed 's|^\./||' | LC_ALL=C sort
}

cloc_rows() {
  local clone="$1"
  (
    cd "$clone"
    sed 's|^|./|' | cloc --list-file=- --by-file --skip-uniqueness --timeout=0 --quiet \
      --csv --csv-delimiter="$TAB"
  ) | LC_ALL=C awk -F'\t' '
    NR == 1 {
      if ($1 != "language" || $2 != "filename" || $5 != "code") { bad = 1; exit 1 }
      next
    }
    $1 == "SUM" { next }
    { sub(/^\.\//, "", $2); print $2 "\t" $5 }
    END { if (bad || NR == 0) exit 1 }
  ' | LC_ALL=C sort
}

measure() {
  local clone="$1" out="$2" name files rows nfiles nrows extra started elapsed
  name=$(basename "$clone")
  [ -d "$clone/.git" ] || { echo "loc-cloc-agreement: $name não é um clone em $clone (bash scripts/corpus.sh)" >&2; return 1; }
  files=$(java_files "$clone")
  if [ -z "$files" ]; then
    echo "loc-cloc-agreement: $name não tem nenhum arquivo .java" >&2
    return 1
  fi
  case "$files" in
    *"$TAB"*)
      echo "loc-cloc-agreement: $name tem um caminho .java com TAB, que um .tsv não comporta" >&2
      return 1 ;;
  esac
  started=$(date +%s)
  if ! rows=$(printf '%s\n' "$files" | cloc_rows "$clone"); then
    echo "loc-cloc-agreement: $name: o cloc falhou ou imprimiu um cabeçalho CSV inesperado" >&2
    return 1
  fi
  elapsed=$(( $(date +%s) - started ))
  if [ -n "$(printf '%s\n' "$rows" | cut -f1 | uniq -d)" ]; then
    echo "loc-cloc-agreement: $name: o cloc imprimiu um caminho duas vezes" >&2
    return 1
  fi
  extra=$(LC_ALL=C comm -13 <(printf '%s\n' "$files") <(printf '%s\n' "$rows" | cut -f1))
  if [ -n "$extra" ]; then
    echo "loc-cloc-agreement: $name: linhas do cloc que não são arquivos .java listados:" >&2
    printf '%s\n' "$extra" >&2
    return 1
  fi
  nfiles=$(printf '%s\n' "$files" | wc -l | tr -d ' ')
  nrows=0
  [ -z "$rows" ] || nrows=$(printf '%s\n' "$rows" | wc -l | tr -d ' ')
  mkdir -p "$out"
  printf '%s\n' "$files" > "$out/$name.files.tmp.$$"
  { printf '# cloc %s\n' "$CLOC_VERSION"; [ -z "$rows" ] || printf '%s\n' "$rows"; } > "$out/$name.tsv.tmp.$$"
  mv -f "$out/$name.files.tmp.$$" "$out/$name.files"
  mv -f "$out/$name.tsv.tmp.$$" "$out/$name.tsv"
  printf '  %-17s %6d arquivos, %6d linhas do cloc (%d sem linha), %8d linhas de código, %3d s\n' \
    "$name" "$nfiles" "$nrows" "$((nfiles - nrows))" \
    "$(printf '%s\n' "$rows" | awk -F'\t' '{ s += $2 } END { print s + 0 }')" "$elapsed"
}

CORPUS_DIR="$REPO_ROOT/corpus"
OUT_DIR="$REPO_ROOT/analyzer/target/loc-cloc"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --corpus) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; CORPUS_DIR="$2"; shift 2 ;;
    --out) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; OUT_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "loc-cloc-agreement: opção desconhecida $1" >&2; usage >&2; exit 2 ;;
  esac
done

require_cloc
echo "loc-cloc-agreement · cloc $CLOC_VERSION → $OUT_DIR"
failed=0
for repo in "${REPOSITORIES[@]}"; do
  measure "$CORPUS_DIR/$repo" "$OUT_DIR" || failed=$((failed + 1))
done
if [ "$failed" -gt 0 ]; then
  printf '%d de %d clones falharam; os arquivos deles não foram substituídos.\n' "$failed" "${#REPOSITORIES[@]}" >&2
  exit 1
fi
printf '%d clones medidos\n' "${#REPOSITORIES[@]}"
