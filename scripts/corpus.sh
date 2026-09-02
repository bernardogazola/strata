#!/usr/bin/env bash

set -euo pipefail

export LC_ALL=C
export GIT_TERMINAL_PROMPT=0

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SELF=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")
OUT_DIR="$ROOT/corpus"
DRY_RUN=0
ONLY=""

PINS=$(cat <<'PINS'
elasticsearch    https://github.com/elastic/elasticsearch.git        b42549c72e6e040825b13e5d8ebf7ff63886b24d v9.5.2
spring-framework https://github.com/spring-projects/spring-framework.git 82a6b40b9366ec181ededdad282b307ee5381a52 v7.0.9
junit5           https://github.com/junit-team/junit5.git             f59f60d2cebdf2224235d81f781b1f310cbc8138 r6.1.3
commons-lang     https://github.com/apache/commons-lang.git           598dfc163b8b410fb3bb8794521206ec8dcec82a rel/commons-lang-3.20.0
PINS
)

DERIVED_NAME="broken-tree"
DERIVED_FROM="commons-lang"

usage() {
  cat <<'USAGE'
uso: corpus.sh [opções]

  --out <pasta>          onde o corpus é clonado (padrão: <repo>/corpus)
  --dry-run              mostra os SHAs fixados e não clona nada
  --only <nome>          clona só um repositório
  --self-test            roda o verificador contra uma entrada sintética e sai
  --help                 mostra este texto

saída: 0 ok · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out)        OUT_DIR="${2:-}"; shift 2 ;;
    --dry-run)    DRY_RUN=1; shift ;;
    --only)       ONLY="${2:-}"; shift 2 ;;
    --self-test)  SELF_TEST=1; shift ;;
    --help|-h)    usage; exit 0 ;;
    *)            echo "corpus: opção desconhecida '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

validate_out_dir() {
  local candidate="$1"
  case "$candidate" in
    ""|/|/*/..*) echo "corpus: recusando --out '$candidate'" >&2; exit 2 ;;
  esac
  if [ "$candidate" = "$HOME" ] || [ "$candidate" = "$ROOT" ] || [ "$candidate" = "/" ]; then
    echo "corpus: recusando gravar o corpus em '$candidate'" >&2
    exit 2
  fi
}

all_names() {
  printf '%s\n' "$PINS" | awk 'NF { print $1 }'
  printf '%s\n' "$DERIVED_NAME"
}

validate_only() {
  local wanted="$1" name
  [ -z "$wanted" ] && return 0
  while IFS= read -r name; do
    [ "$name" = "$wanted" ] && return 0
  done <<EOF
$(all_names)
EOF
  echo "corpus: repositório desconhecido '$wanted'" >&2
  exit 2
}

wanted() {
  [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]
}

pins_table() {
  printf '%s\n' "$PINS" | awk 'NF { printf "  %-17s %s  %s\n", $1, $3, $4 }'
  printf '  %-17s %s  %s\n' "$DERIVED_NAME" "derivado de $DERIVED_FROM" "mutações por script"
}

clone_pinned() {
  local dir="$1" url="$2" sha="$3"
  rm -rf -- "$dir"
  mkdir -p -- "$dir"
  git -C "$dir" init -q
  git -C "$dir" remote add origin "$url"
  if git -C "$dir" fetch -q --depth 1 origin "$sha" 2>/dev/null; then
    git -C "$dir" checkout -q --detach FETCH_HEAD
  else
    echo "  ...o servidor recusou o fetch por SHA; usando um clone sem blobs"
    git -C "$dir" fetch -q --filter=blob:none origin
    git -C "$dir" checkout -q --detach "$sha"
  fi
}

assert_pin() {
  local name="$1" dir="$2" sha="$3" head
  head=$(git -C "$dir" rev-parse HEAD)
  if [ "$head" != "$sha" ]; then
    echo "corpus: $name está em $head, mas o pino diz $sha" >&2
    return 1
  fi
}

derive_broken_tree() {
  local source="$1" dir="$2" list nth path size
  rm -rf -- "$dir"
  mkdir -p -- "$dir"
  (cd "$source" && tar cf - --exclude .git .) | (cd "$dir" && tar xf -)

  list=$(cd "$dir" && find . -type f -name '*.java' | LC_ALL=C sort)
  : > "$dir/MUTATIONS.txt"
  {
    printf '# Mutações determinísticas aplicadas a %s.\n' "$DERIVED_FROM"
    printf '# Reproduzível: o pino fixa a árvore, e a ordem é `find -name "*.java" | LC_ALL=C sort`.\n'
  } >> "$dir/MUTATIONS.txt"

  for nth in 1 50 100; do
    path=$(printf '%s\n' "$list" | sed -n "${nth}p")
    [ -n "$path" ] || continue
    case "$nth" in
      1)
        size=$(wc -c < "$dir/$path" | tr -d ' ')
        dd if="$dir/$path" of="$dir/$path.cut" bs=1 count=$((size / 2)) 2>/dev/null
        mv -- "$dir/$path.cut" "$dir/$path"
        printf 'truncate-half  %s\n' "$path" >> "$dir/MUTATIONS.txt"
        ;;
      50)
        printf '\n}\n{\n' >> "$dir/$path"
        printf 'unbalanced     %s\n' "$path" >> "$dir/MUTATIONS.txt"
        ;;
      100)
        printf 'unresolvable   %s\n' "$(dirname "$path")/StrataUnresolvable.java" \
          >> "$dir/MUTATIONS.txt"
        cat > "$dir/$(dirname "$path")/StrataUnresolvable.java" <<'JAVA'
import com.example.absolutely.nonexistent.Thing;

public class StrataUnresolvable {
    Thing broken( {
}
JAVA
        ;;
    esac
  done
}

run() {
  local out="$1" name url sha tag dir failed=0
  validate_out_dir "$out"
  validate_only "$ONLY"

  if [ "$DRY_RUN" = 1 ]; then
    echo "corpus · 5 repositórios fixados · nada foi clonado"
    pins_table
    return 0
  fi

  mkdir -p -- "$out"
  echo "corpus · clonando em $out"
  while read -r name url sha tag; do
    [ -n "$name" ] || continue
    wanted "$name" || continue
    dir="$out/$name"
    echo "  $name $tag"
    clone_pinned "$dir" "$url" "$sha"
    assert_pin "$name" "$dir" "$sha" || failed=$((failed + 1))
  done <<EOF
$(printf '%s\n' "$PINS")
EOF

  if wanted "$DERIVED_NAME"; then
    if [ -d "$out/$DERIVED_FROM" ]; then
      echo "  $DERIVED_NAME derivado de $DERIVED_FROM"
      derive_broken_tree "$out/$DERIVED_FROM" "$out/$DERIVED_NAME"
    else
      echo "corpus: $DERIVED_NAME precisa de $DERIVED_FROM, que não foi clonado" >&2
      failed=$((failed + 1))
    fi
  fi

  if [ "$failed" -ne 0 ]; then
    printf '\n%d problema(s).\n' "$failed"
    return 1
  fi
  echo "todo repositório está no seu pino"
}

self_test() {
  local pass=0 failed=0 out
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT

  ok() { pass=$((pass + 1)); printf '  ok     %s\n' "$1"; }
  no() { failed=$((failed + 1)); printf '  falha  %s: %s\n' "$1" "$2"; }

  echo "corpus --self-test"

  if [ "$(printf '%s\n' "$PINS" | awk 'NF' | wc -l | tr -d ' ')" = 4 ] &&
     [ "$(printf '%s\n' "$PINS" | awk 'NF && $3 ~ /^[0-9a-f]{40}$/' | wc -l | tr -d ' ')" = 4 ] &&
     [ "$(printf '%s\n' "$PINS" | awk 'NF && $2 ~ /^https:\/\//' | wc -l | tr -d ' ')" = 4 ]; then
    ok "quatro pinos, cada um com um sha de quarenta hex e uma url https"
  else
    no "quatro pinos, cada um com um sha de quarenta hex e uma url https" "$PINS"
  fi

  if [ "$(all_names | wc -l | tr -d ' ')" = 5 ] &&
     [ "$(all_names | LC_ALL=C sort -u | wc -l | tr -d ' ')" = 5 ]; then
    ok "cinco repositórios, sem nome repetido"
  else
    no "cinco repositórios, sem nome repetido" "$(all_names | tr '\n' ' ')"
  fi

  out=$(bash "$SELF" --dry-run --out "$tmp/absent" 2>"$tmp/err")
  if [ "$(printf '%s\n' "$out" | grep -c -E '^  [a-z0-9-]+ +[0-9a-f]{40}')" = 4 ] &&
     [ "$(printf '%s\n' "$out" | grep -c -E "^  $DERIVED_NAME")" = 1 ] &&
     [ ! -d "$tmp/absent" ]; then
    ok "--dry-run mostra cinco linhas fixadas e não cria nada"
  else
    no "--dry-run mostra cinco linhas fixadas e não cria nada" "$out"
  fi

  git -c init.defaultBranch=main init -q "$tmp/local"
  : > "$tmp/local/a.txt"
  env GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t \
    GIT_AUTHOR_DATE="1704164645 +0000" GIT_COMMITTER_DATE="1704164645 +0000" \
    git -C "$tmp/local" -c commit.gpgsign=false -c user.name=t -c user.email=t@t \
        commit -q --allow-empty -m seed
  set +e
  assert_pin local "$tmp/local" 0000000000000000000000000000000000000000 2>/dev/null
  local rc_pin=$?
  assert_pin local "$tmp/local" "$(git -C "$tmp/local" rev-parse HEAD)" 2>/dev/null
  local rc_good=$?
  set -e
  if [ "$rc_pin" != 0 ] && [ "$rc_good" = 0 ]; then
    ok "um HEAD que diverge do pino é recusado, e um que confere não"
  else
    no "um HEAD que diverge do pino é recusado" "divergente=$rc_pin igual=$rc_good"
  fi

  set +e
  bash "$SELF" --out "$HOME" --dry-run >/dev/null 2>&1;   local rc_home=$?
  bash "$SELF" --out "$ROOT" --dry-run >/dev/null 2>&1;   local rc_root=$?
  bash "$SELF" --only nope --dry-run >/dev/null 2>&1;     local rc_only=$?
  bash "$SELF" --frobnicate >/dev/null 2>&1;              local rc_opt=$?
  set -e
  [ "$rc_home" = 2 ] && ok "--out \$HOME é recusado" || no "--out \$HOME é recusado" "saída $rc_home"
  [ "$rc_root" = 2 ] && ok "--out com a raiz do repositório é recusado" || no "--out com a raiz do repositório é recusado" "saída $rc_root"
  [ "$rc_only" = 2 ] && ok "--only com um nome desconhecido é erro de uso" || no "--only com um nome desconhecido é erro de uso" "saída $rc_only"
  [ "$rc_opt" = 2 ]  && ok "uma opção desconhecida é erro de uso" || no "uma opção desconhecida é erro de uso" "saída $rc_opt"

  printf '\n%d ok, %d com falha\n' "$pass" "$failed"
  [ "$failed" -eq 0 ]
}

if [ "${SELF_TEST:-0}" = 1 ]; then
  self_test
  exit $?
fi

run "$OUT_DIR"
