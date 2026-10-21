#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SELF="$REPO_ROOT/scripts/make-cloc-goldens.sh"

CLOC_VERSION="2.10"
GOLDEN="cloc-golden.txt"
TAB=$(printf '\t')

usage() {
  cat <<'USAGE'
make-cloc-goldens.sh: grava o cloc-golden.txt de cada fixture com o cloc 2.10

  bash scripts/make-cloc-goldens.sh [--fixtures <pasta>] [--out <pasta>]
  bash scripts/make-cloc-goldens.sh --self-test

Saída: 0 tudo certo · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

require_cloc() {
  local found
  if ! command -v cloc >/dev/null 2>&1; then
    echo "make-cloc-goldens: é preciso o cloc $CLOC_VERSION (brew install cloc); não há nenhum no PATH" >&2
    return 1
  fi
  found=$(cloc --version 2>/dev/null || true)
  if [ "$found" != "$CLOC_VERSION" ]; then
    echo "make-cloc-goldens: é preciso o cloc $CLOC_VERSION, encontrado '$found'" >&2
    return 1
  fi
}

java_files() {
  (cd "$1" && find . -name .git -prune -o -type f -name '*.java' -print) | sed 's|^\./||' | LC_ALL=C sort
}

cloc_rows() {
  local fixture="$1"
  (
    cd "$fixture"
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

write_golden() {
  local fixture="$1" target="$2" name files rows nfiles nrows tmpfile
  name=$(basename "$fixture")
  files=$(java_files "$fixture")
  if [ -z "$files" ]; then
    echo "make-cloc-goldens: $name não tem nenhum arquivo .java" >&2
    return 1
  fi
  case "$files" in
    *"$TAB"*)
      echo "make-cloc-goldens: $name tem um caminho .java com TAB, que o golden não comporta" >&2
      return 1 ;;
  esac
  if ! rows=$(printf '%s\n' "$files" | cloc_rows "$fixture"); then
    echo "make-cloc-goldens: $name: o cloc falhou ou imprimiu um cabeçalho CSV inesperado" >&2
    return 1
  fi
  nfiles=$(printf '%s\n' "$files" | wc -l | tr -d ' ')
  nrows=0
  [ -z "$rows" ] || nrows=$(printf '%s\n' "$rows" | wc -l | tr -d ' ')
  if [ "$nrows" != "$nfiles" ]; then
    echo "make-cloc-goldens: $name: $nrows linhas no golden para $nfiles arquivos .java; o cloc descartou um arquivo (vazio, binário, .config/.venv, duplicado)" >&2
    diff <(printf '%s\n' "$files") <(printf '%s\n' "$rows" | cut -f1) >&2 || true
    return 1
  fi
  if [ "$(printf '%s\n' "$rows" | cut -f1)" != "$files" ]; then
    echo "make-cloc-goldens: $name: os caminhos do golden não são os arquivos .java da fixture" >&2
    diff <(printf '%s\n' "$files") <(printf '%s\n' "$rows" | cut -f1) >&2 || true
    return 1
  fi
  mkdir -p "$(dirname "$target")"
  tmpfile="$target.tmp.$$"
  { printf '# cloc %s\n' "$CLOC_VERSION"; printf '%s\n' "$rows"; } > "$tmpfile"
  mv -f "$tmpfile" "$target"
  printf '  %s %d arquivos, %d linhas de código\n' "$name" "$nrows" \
    "$(printf '%s\n' "$rows" | awk -F'\t' '{ s += $2 } END { print s + 0 }')"
}

generate() {
  local fixtures_dir="$1" out_dir="$2" fixture name target count=0 failed=0
  require_cloc
  [ -d "$fixtures_dir" ] || { echo "make-cloc-goldens: não há pasta de fixtures em $fixtures_dir" >&2; return 1; }
  echo "make-cloc-goldens · cloc $CLOC_VERSION"
  while IFS= read -r fixture; do
    name=$(basename "$fixture")
    target="$fixture/$GOLDEN"
    [ -z "$out_dir" ] || target="$out_dir/$name/$GOLDEN"
    count=$((count + 1))
    write_golden "$fixture" "$target" || failed=$((failed + 1))
  done < <(find "$fixtures_dir" -mindepth 1 -maxdepth 1 -type d | LC_ALL=C sort)
  if [ "$count" -eq 0 ]; then
    echo "make-cloc-goldens: vacuidade: nenhuma fixture em $fixtures_dir" >&2
    return 1
  fi
  if [ "$failed" -gt 0 ]; then
    printf '%d de %d fixtures falharam; nenhum golden foi gravado para elas.\n' "$failed" "$count" >&2
    return 1
  fi
  printf '%d goldens gravados\n' "$count"
}

self_test() {
  local pass=0 failed=0
  require_cloc
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT

  ok() { pass=$((pass + 1)); printf '  ok     %s\n' "$1"; }
  no() { failed=$((failed + 1)); printf '  falha  %s: %s\n' "$1" "$2"; }

  seed() {
    rm -rf "$tmp/fx" "$tmp/out"
    mkdir -p "$tmp/fx/alpha/src/b"
    printf 'class A {}\n' > "$tmp/fx/alpha/src/A.java"
    printf '// c\nclass B {\n}\n' > "$tmp/fx/alpha/src/b/B.java"
  }
  run() { bash "$SELF" --fixtures "$tmp/fx" --out "$tmp/out" >"$tmp/log" 2>&1; }

  echo "make-cloc-goldens --self-test"

  seed
  if run && [ "$(cat "$tmp/out/alpha/$GOLDEN")" = "$(printf '# cloc %s\nsrc/A.java\t1\nsrc/b/B.java\t2' "$CLOC_VERSION")" ]; then
    ok "uma fixture saudável gera a linha de versão e depois caminho TAB código em ordem de code point"
  else
    no "uma fixture saudável gera a linha de versão e depois caminho TAB código em ordem de code point" "$(cat "$tmp/log" "$tmp/out/alpha/$GOLDEN" 2>/dev/null)"
  fi

  seed
  cp "$tmp/fx/alpha/src/A.java" "$tmp/fx/alpha/src/Twin.java"
  if run && grep -q "^src/Twin.java${TAB}1\$" "$tmp/out/alpha/$GOLDEN"; then
    ok "dois arquivos idênticos mantêm duas linhas (--skip-uniqueness)"
  else
    no "dois arquivos idênticos mantêm duas linhas (--skip-uniqueness)" "$(cat "$tmp/log")"
  fi

  seed
  mkdir -p "$tmp/fx/alpha/.git/x"
  printf 'class G {}\n' > "$tmp/fx/alpha/.git/x/G.java"
  if run && ! grep -q 'G.java' "$tmp/out/alpha/$GOLDEN"; then
    ok "um .java dentro de .git não vira arquivo nem linha"
  else
    no "um .java dentro de .git não vira arquivo nem linha" "$(cat "$tmp/log")"
  fi

  seed
  : > "$tmp/fx/alpha/src/Empty.java"
  if ! run && grep -q '2 linhas no golden para 3 arquivos .java' "$tmp/log" && [ ! -e "$tmp/out/alpha/$GOLDEN" ]; then
    ok "um arquivo vazio que o cloc descarta falha e não grava nada"
  else
    no "um arquivo vazio que o cloc descarta falha e não grava nada" "$(cat "$tmp/log")"
  fi

  seed
  mkdir -p "$tmp/fx/alpha/.config"
  printf 'class C {}\n' > "$tmp/fx/alpha/.config/C.java"
  if ! run && grep -q 'linhas no golden para 3 arquivos .java' "$tmp/log"; then
    ok "um arquivo em .config/ que o cloc descarta falha"
  else
    no "um arquivo em .config/ que o cloc descarta falha" "$(cat "$tmp/log")"
  fi

  seed
  mkdir -p "$tmp/bin"
  printf '#!/bin/sh\necho 2.11\n' > "$tmp/bin/cloc"
  chmod +x "$tmp/bin/cloc"
  if ! PATH="$tmp/bin:$PATH" run && grep -q "é preciso o cloc $CLOC_VERSION, encontrado '2.11'" "$tmp/log"; then
    ok "outra versão do cloc é recusada"
  else
    no "outra versão do cloc é recusada" "$(cat "$tmp/log")"
  fi

  rm -rf "$tmp/fx" && mkdir -p "$tmp/fx"
  if ! run && grep -q 'vacuidade' "$tmp/log"; then
    ok "uma raiz sem fixture falha"
  else
    no "uma raiz sem fixture falha" "$(cat "$tmp/log")"
  fi

  printf '\n%d ok, %d com falha\n' "$pass" "$failed"
  [ "$failed" -eq 0 ]
}

FIXTURES_DIR="$REPO_ROOT/fixtures/repositories"
OUT_DIR=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --self-test) self_test; exit $? ;;
    --fixtures) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; FIXTURES_DIR="$2"; shift 2 ;;
    --out) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; OUT_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "make-cloc-goldens: opção desconhecida $1" >&2; usage >&2; exit 2 ;;
  esac
done

generate "$FIXTURES_DIR" "$OUT_DIR"
