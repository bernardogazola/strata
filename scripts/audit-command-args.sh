#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC="$ROOT/src-tauri/src"
SELF_TEST=0

BASELINE_COMMANDS="
analyzer_cancel
analyzer_request
analyzer_restart
analyzer_state
analyzer_stderr_tail
close_repository
export_diagnostics
open_repository
"

DENIED_TYPES="PathBuf Path OsString OsStr"
DENIED_FIELDS="path dir root file location"

usage() {
  cat <<'USAGE'
uso: audit-command-args.sh [opções]

  --src <pasta>    pasta a varrer   (padrão: <repo>/src-tauri/src)
  --self-test      roda contra fixtures sintéticas e sai
  --help           mostra este texto

saída: 0 ok · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --src)       SRC="${2:-}"; shift 2 ;;
    --self-test) SELF_TEST=1; shift ;;
    --help|-h)   usage; exit 0 ;;
    *) echo "audit-command-args: opção desconhecida '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

AWK_PROG=$(cat <<'AWK'
function bad(m) { nerr++; err[nerr] = m }

function offends(sig,   i, j, pattern) {
  for (i = 1; i <= ntypes; i++) if (index(sig, denied_type[i]) > 0) return denied_type[i]

  for (i = 1; i <= nfields; i++) {
    pattern = "(^|,)[ \t]*[a-z_]*" denied_field[i] "[a-z_]*:[ \t]*(Option<)?(Vec<)?(String|&str)"
    if (sig ~ pattern) return denied_field[i]
  }
  return ""
}

BEGIN {
  ntypes = split(TYPES, denied_type, " ")
  nfields = split(FIELDS, denied_field, " ")
  n = split(BASE, base_list, " ")
  for (i = 1; i <= n; i++) if (base_list[i] != "") declared[base_list[i]] = 1
}

$0 ~ /^[ \t]*\/\// { next }
/#\[tauri::command\]/ { pending = 1; sig = ""; next }

pending {
  sig = sig " " $0
  if (index($0, "{") == 0 && index($0, ";") == 0) next
  pending = 0
  nseen++
  if (match(sig, /fn[ \t]+[a-z_]+[ \t]*\(/)) {
    name = substr(sig, RSTART, RLENGTH)
    sub(/^fn[ \t]+/, "", name); sub(/[ \t]*\($/, "", name)
  } else {
    bad("um #[tauri::command] cuja assinatura não pôde ser lida: " sig)
    next
  }
  found[name] = 1
  args = substr(sig, index(sig, "(") + 1)
  hit = offends(args)
  if (hit != "") bad(name "(): os argumentos citam \"" hit "\", que pode carregar um caminho do sistema de arquivos")
  if (!(name in declared)) bad(name "(): comando fora da baseline declarada")
}

END {
  for (name in declared) if (!(name in found)) bad(name "(): declarado na baseline e não encontrado no código")
  if (nseen == 0) bad("nenhum #[tauri::command] encontrado; a varredura passaria por vacuidade")

  printf "audit-command-args · %d comandos varridos · baseline %d\n", nseen, length(declared)
  printf "  %s  tipos de argumento .. nenhum %s em assinatura de comando\n", (nerr == 0 ? "ok   " : "falha"), TYPES
  printf "  %s  nomes de argumento .. nenhum parâmetro %s em assinatura de comando\n", (nerr == 0 ? "ok   " : "falha"), FIELDS
  printf "  %s  baseline ............ todo comando declarado, toda declaração encontrada\n", (nerr == 0 ? "ok   " : "falha")

  if (nerr > 0) {
    print ""
    for (i = 1; i <= nerr; i++) print "  falha  " err[i]
    printf "\n%d problema%s.\n", nerr, (nerr == 1 ? "" : "s")
    exit 1
  }
  print "nenhum caminho do sistema de arquivos pode ser passado como argumento de comando"
  exit 0
}
AWK
)

run_audit() {
  local src="$1" base="$2"
  [ -d "$src" ] || { echo "audit-command-args: pasta inexistente: $src" >&2; return 2; }
  base=$(printf '%s' "$base" | tr '\n' ' ')
  # shellcheck disable=SC2086
  find "$src" -name '*.rs' -type f -print0 \
    | xargs -0 awk -v TYPES="$DENIED_TYPES" -v FIELDS="$DENIED_FIELDS" -v BASE="$base" "$AWK_PROG"
}

self_test() {
  local pass=0 failed=0
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  mkdir -p "$tmp/src"

  seed() {
    cat > "$tmp/src/commands.rs" <<'R'
#[tauri::command]
pub fn close_repository(handle: RepositoryHandle, runtime: Runtime<'_>) {}

#[tauri::command]
pub async fn analyzer_request(
    handle: Option<RepositoryHandle>,
    method: String,
    payload: Box<RawValue>,
) -> Result<Box<RawValue>, CommandError> {
}
R
  }

  expect() {
    local name="$1" want="$2" needle="$3" out rc
    set +e
    out=$(run_audit "$tmp/src" "close_repository analyzer_request" 2>&1)
    rc=$?
    set -e
    if [ "$want" = ok ] && [ "$rc" -ne 0 ]; then
      failed=$((failed + 1)); printf '  falha  %s: esperado 0, obtido %d\n%s\n' "$name" "$rc" "$out"; return
    fi
    if [ "$want" = fail ] && [ "$rc" -eq 0 ]; then
      failed=$((failed + 1)); printf '  falha  %s: esperado diferente de 0, obtido 0\n%s\n' "$name" "$out"; return
    fi
    case "$out" in
      *"$needle"*) pass=$((pass + 1)); printf '  ok     %s\n' "$name" ;;
      *) failed=$((failed + 1)); printf '  falha  %s: a saída não citou "%s"\n%s\n' "$name" "$needle" "$out" ;;
    esac
  }

  echo "audit-command-args --self-test"

  seed
  expect "uma superfície de comandos limpa passa" ok "nenhum caminho do sistema de arquivos pode ser passado"

  seed
  printf '\n#[tauri::command]\npub fn close_repository(path: PathBuf) {}\n' >> "$tmp/src/commands.rs"
  expect "um argumento PathBuf" fail 'citam "PathBuf"'

  seed
  printf '\n#[tauri::command]\npub fn analyzer_request(repository_path: String) {}\n' >> "$tmp/src/commands.rs"
  expect "um argumento String com nome de caminho" fail 'citam "path"'

  seed
  printf '\n#[tauri::command]\npub fn open_repository() {}\n' >> "$tmp/src/commands.rs"
  expect "um comando fora da baseline" fail "fora da baseline declarada"

  : > "$tmp/src/commands.rs"
  expect "uma árvore de código vazia falha em vez de passar" fail "vacuidade"

  printf '\n%d ok, %d com falha\n' "$pass" "$failed"
  [ "$failed" -eq 0 ]
}

if [ "$SELF_TEST" = 1 ]; then
  self_test
  exit $?
fi

run_audit "$SRC" "$BASELINE_COMMANDS"
