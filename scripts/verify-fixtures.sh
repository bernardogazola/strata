#!/usr/bin/env bash

set -euo pipefail

export LC_ALL=C

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
FIXTURES_DIR="$ROOT/protocol/fixtures"
REPOSITORIES_DIR="$ROOT/fixtures/repositories"
EXPECT_REPOSITORIES=0
COUNT_ONLY=""
MAX_FRAME_BYTES=16777216

usage() {
  cat <<'USAGE'
uso: verify-fixtures.sh [opções]

  --fixtures <pasta>     fixtures de protocolo    (padrão: <repo>/protocol/fixtures)
  --repositories <pasta> fixtures de repositório  (padrão: <repo>/fixtures/repositories)
  --expect-repositories <n>  quantas fixtures de repositório devem existir (padrão: 0)
  --count <classe>       mostra só a contagem de "protocol" ou de "repositories"
  --max-frame <n>        tamanho máximo de frame em bytes (padrão: 16777216)
  --self-test            roda o verificador contra fixtures sintéticas e sai
  --help                 mostra este texto

saída: 0 ok · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --fixtures)     FIXTURES_DIR="${2:-}"; shift 2 ;;
    --repositories) REPOSITORIES_DIR="${2:-}"; shift 2 ;;
    --expect-repositories) EXPECT_REPOSITORIES="${2:-}"; shift 2 ;;
    --count)        COUNT_ONLY="${2:-}"; shift 2 ;;
    --max-frame)    MAX_FRAME_BYTES="${2:-}"; shift 2 ;;
    --self-test)    SELF_TEST=1; shift ;;
    --help|-h)      usage; exit 0 ;;
    *)              echo "verify-fixtures: opção desconhecida '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

case "${COUNT_ONLY:-}" in
  ""|protocol|repositories) ;;
  *) echo "verify-fixtures: --count aceita 'protocol' ou 'repositories'" >&2; exit 2 ;;
esac

IFS= read -r -d '' AWK_PROG <<'AWK' || true
function bad(tag, m) { nerr++; err[nerr] = m; etag[tag] = 1 }
function st(tag)     { return (etag[tag] + 0 == 0 ? "ok    " : "falha ") }
function base(p,  a, n) { n = split(p, a, "/"); return a[n] }

BEGIN { nframes = 0; longest = 0; SEP = sprintf("%c", 31) }

/^[ \t]*$/ {
  bad("framing", base(FILENAME) ":" FNR ": frames: linha em branco")
  next
}

{
  nframes++
  if (length($0) > longest) longest = length($0)
  if (length($0) > MAXFRAME)
    bad("framing", base(FILENAME) ":" FNR ": frames: frame acima do limite, " length($0) " B de " MAXFRAME)

  if ($0 ~ /"\//)
    bad("paths", base(FILENAME) ":" FNR ": caminho absoluto: uma string começa com /")
  if ($0 ~ /"[A-Za-z]:(\\\\|\/)/)
    bad("paths", base(FILENAME) ":" FNR ": caminho absoluto: uma letra de unidade do Windows")
  if ($0 ~ /"\\\\\\\\/)
    bad("paths", base(FILENAME) ":" FNR ": caminho absoluto: um compartilhamento UNC")
  if ($0 ~ /\/(Users|home)\//)
    bad("paths", base(FILENAME) ":" FNR ": caminho absoluto: uma pasta pessoal")

  skel = $0
  gsub(/"(\\.|[^"\\])*"/, "S", skel)

  if (skel ~ /"/)
    bad("framing", base(FILENAME) ":" FNR ": frames: string não terminada")

  if (substr(skel, 1, 1) != "{")
    bad("framing", base(FILENAME) ":" FNR ": frames: um frame deve ser um objeto JSON")

  depth = 0; bracket = 0; ok = 1
  n = length(skel)
  for (i = 1; i <= n; i++) {
    c = substr(skel, i, 1)
    if (c == "{") depth++
    else if (c == "}") { depth--; if (depth < 0) ok = 0 }
    else if (c == "[") bracket++
    else if (c == "]") { bracket--; if (bracket < 0) ok = 0 }
  }
  if (depth != 0 || bracket != 0 || ok == 0)
    bad("framing", base(FILENAME) ":" FNR ": frames: { } ou [ ] desbalanceado")
}

END {
  printf "verify-fixtures · %d fixture%s de protocolo · %d frame%s · %d fixture%s de repositório\n",
         NFILES, (NFILES == 1 ? "" : "s"), nframes, (nframes == 1 ? "" : "s"),
         NREPOS, (NREPOS == 1 ? "" : "s")

  printf "  %s  inventário .............. %d .jsonl, %d README.md, %d fora do padrão, nenhum vazio\n",
         (INVENTORY == "" ? "ok    " : "falha "), NFILES, NREADME, NSTRAY
  printf "  %s  nomes ................... <tópico>[.<caso>].jsonl\n", (NAMING == "" ? "ok    " : "falha ")
  printf "  %s  codificação ............. UTF-8 válido, sem BOM, sem CR, com quebra de linha no fim\n",
         (ENCODING == "" ? "ok    " : "falha ")
  printf "  %s  frames .................. %d linha%s, a maior com %d B de um limite de %d B\n",
         st("framing"), nframes, (nframes == 1 ? "" : "s"), longest, MAXFRAME
  printf "  %s  caminhos absolutos ...... nenhum em %d frame%s\n",
         st("paths"), nframes, (nframes == 1 ? "" : "s")
  printf "  %s  repositórios ............ %d de %d esperados\n",
         (NREPOS == EXPECTREPOS ? "ok    " : "falha "), NREPOS, EXPECTREPOS

  if (INVENTORY != "") { split(INVENTORY, iv, SEP); for (i in iv) if (iv[i] != "") bad("inventory", iv[i]) }
  if (NAMING != "")    { split(NAMING, nv, SEP);    for (i in nv) if (nv[i] != "") bad("naming", nv[i]) }
  if (ENCODING != "")  { split(ENCODING, ev, SEP);  for (i in ev) if (ev[i] != "") bad("encoding", ev[i]) }
  if (NREPOS != EXPECTREPOS)
    bad("repositories", sprintf("repositórios: %d encontrados, %d esperados", NREPOS, EXPECTREPOS))

  if (nerr > 0) {
    print ""
    for (i = 1; i <= nerr; i++) print "  falha  " err[i]
    printf "\n%d problema%s.\n", nerr, (nerr == 1 ? "" : "s")
    exit 1
  }

  printf "fixtures de protocolo: %d\n", NFILES
  printf "repositórios: %d\n", NREPOS
  print "nenhuma fixture fora do formato"
  exit 0
}
AWK

name_is_valid() {
  case "$1" in
    *.jsonl) : ;;
    *) return 1 ;;
  esac
  printf '%s' "$1" | grep -Eq '^[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9-]*)*\.jsonl$'
}

run_check() {
  local dir="$1" repos="$2" expect="$3" count_only="$4"
  local inventory="" naming="" encoding=""
  local US
  US=$(printf '\037')
  local nfiles=0 nreadme=0 nstray=0 nrepos=0
  local files=() entry name

  if [ ! -d "$dir" ]; then
    if [ -n "$count_only" ]; then echo 0; return 1; fi
    echo "verify-fixtures: pasta inexistente: $dir" >&2
    echo "  falha  vacuidade: a pasta de fixtures não existe"
    echo "1 problema."
    return 1
  fi

  while IFS= read -r entry; do
    name=$(basename "$entry")
    case "$name" in
      .*) continue ;;
      README.md) nreadme=$((nreadme + 1)); continue ;;
    esac
    if ! name_is_valid "$name"; then
      nstray=$((nstray + 1))
      naming="${naming}nomes: ${name} não segue <tópico>[.<caso>].jsonl"$US
      continue
    fi
    files+=("$entry")
    nfiles=$((nfiles + 1))
  done < <(find "$dir" -maxdepth 1 -type f | LC_ALL=C sort)

  [ -d "$repos" ] && nrepos=$(find "$repos" -maxdepth 1 -mindepth 1 -type d | wc -l | tr -d ' ')

  if [ -n "$count_only" ]; then
    case "$count_only" in
      protocol)     echo "$nfiles" ;;
      repositories) echo "$nrepos" ;;
    esac
    return 0
  fi

  [ "$nreadme" -eq 1 ] || inventory="${inventory}inventário: era esperado exatamente um README.md, e há ${nreadme}"$US

  if [ "$nfiles" -eq 0 ]; then
    echo "verify-fixtures · 0 fixtures de protocolo"
    echo "  falha  vacuidade: nenhuma fixture encontrada em $(basename "$(dirname "$dir")")/$(basename "$dir")"
    echo ""
    echo "1 problema."
    return 1
  fi

  for entry in "${files[@]}"; do
    name=$(basename "$entry")
    if [ ! -s "$entry" ]; then
      encoding="${encoding}vacuidade: ${name} está vazio"$US
      continue
    fi
    if [ "$(head -c 3 "$entry" | od -An -tx1 | tr -d ' \n')" = "efbbbf" ]; then
      encoding="${encoding}codificação: ${name} começa com uma marca de ordem de bytes (BOM)"$US
    fi
    if LC_ALL=C grep -q "$(printf '\r')" "$entry"; then
      encoding="${encoding}codificação: ${name} contém um retorno de carro (CR)"$US
    fi
    if LC_ALL=C grep -q "$(printf '[\001-\010\013\014\016-\037]')" "$entry"; then
      encoding="${encoding}codificação: ${name} contém um byte de controle"$US
    fi
    if ! iconv -f UTF-8 -t UTF-8 < "$entry" > /dev/null 2>&1; then
      encoding="${encoding}codificação: ${name} não é UTF-8 válido"$US
    fi
    if [ "$(tail -c 1 "$entry" | wc -l | tr -d ' ')" -ne 1 ]; then
      encoding="${encoding}codificação: ${name} não termina com quebra de linha"$US
    fi
  done

  awk -v NFILES="$nfiles" -v NREADME="$nreadme" -v NSTRAY="$nstray" \
      -v NREPOS="$nrepos" -v EXPECTREPOS="$expect" -v MAXFRAME="$MAX_FRAME_BYTES" \
      -v INVENTORY="$inventory" -v NAMING="$naming" -v ENCODING="$encoding" \
      "$AWK_PROG" "${files[@]}"
}

self_test() {
  local pass=0 failed=0
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT

  seed() {
    rm -rf "$tmp/fixtures"
    mkdir -p "$tmp/fixtures"
    printf '# Protocol fixtures\n' > "$tmp/fixtures/README.md"
    printf '%s\n' '{"kind":"event","event":"protocol.ready","payload":{"capabilities":[],"protocol":1},"protocol":1}' \
      > "$tmp/fixtures/protocol.ready.mvp.jsonl"
    printf '%s\n%s\n' \
      '{"id":"req_1","kind":"request","method":"system.ping","payload":{},"protocol":1}' \
      '{"id":"req_1","kind":"response","ok":true,"payload":{"uptimeMillis":0},"protocol":1}' \
      > "$tmp/fixtures/system.ping.ok.jsonl"
    printf '%s\n' '{"kind":"event","event":"analysis.started","payload":{"analysisId":"an_1"},"protocol":1}' \
      > "$tmp/fixtures/analysis.started.ok.jsonl"
  }

  expect() {
    local name="$1" want="$2" needle="$3"; shift 3
    local out rc
    set +e
    out=$(bash "$0" --fixtures "$tmp/fixtures" --repositories "$tmp/absent" "$@" 2>&1)
    rc=$?
    set -e
    case "$want" in
      ok)    [ "$rc" -eq 0 ] || { failed=$((failed+1)); printf '  falha  %s: esperada saída 0, obtida %d\n%s\n' "$name" "$rc" "$out"; return; } ;;
      fail)  [ "$rc" -eq 1 ] || { failed=$((failed+1)); printf '  falha  %s: esperada saída 1, obtida %d\n%s\n' "$name" "$rc" "$out"; return; } ;;
      usage) [ "$rc" -eq 2 ] || { failed=$((failed+1)); printf '  falha  %s: esperada saída 2, obtida %d\n%s\n' "$name" "$rc" "$out"; return; } ;;
    esac
    case "$out" in
      *"$needle"*) pass=$((pass+1)); printf '  ok     %s\n' "$name" ;;
      *) failed=$((failed+1)); printf '  falha  %s: a saída não citou "%s"\n%s\n' "$name" "$needle" "$out" ;;
    esac
  }

  echo "verify-fixtures --self-test"

  seed
  expect "uma árvore saudável passa"                    ok "nenhuma fixture fora do formato"

  seed
  printf '%s\n' '{"kind":"event","event":"analysis.warning","payload":{"subject":"corrige o } solto"},"protocol":1}' \
    > "$tmp/fixtures/analysis.warning.brace.jsonl"
  expect "uma chave dentro de string não desbalanceia"  ok "nenhuma fixture fora do formato"

  seed
  printf '%s\n' '{"kind":"event","event":"analysis.warning","payload":{"filePath":"src/main/java/X.java"},"protocol":1}' \
    > "$tmp/fixtures/analysis.warning.relative.jsonl"
  expect "a busca de caminhos poupa um caminho relativo" ok "nenhuma fixture fora do formato"

  seed
  printf '%s\n' '{"id":"req_home123","kind":"response","ok":true,"payload":{},"protocol":1}' \
    > "$tmp/fixtures/system.shutdown.ok.jsonl"
  expect "a busca de caminhos é ancorada, não por substring" ok "nenhuma fixture fora do formato"

  seed
  expect "--count protocol mostra só o número"          ok "3" --count protocol

  seed
  printf 'x' > "$tmp/fixtures/.DS_Store"
  expect "um dotfile não conta como fora do padrão"     ok "nenhuma fixture fora do formato"

  seed
  mv "$tmp/fixtures/system.ping.ok.jsonl" "$tmp/fixtures/analysis-start.jsonl"
  expect "um hífen no primeiro segmento"                fail "nomes"

  seed
  mv "$tmp/fixtures/system.ping.ok.jsonl" "$tmp/fixtures/System.Ping.jsonl"
  expect "um primeiro segmento com maiúscula"           fail "nomes"

  seed
  printf 'nota\n' > "$tmp/fixtures/notes.txt"
  expect "um arquivo avulso que não é fixture"          fail "nomes"

  seed
  mv "$tmp/fixtures/README.md" "$tmp/fixtures/README.txt"
  expect "README.md renomeado"                          fail "inventário"

  seed
  printf '\357\273\277%s\n' '{"kind":"event","event":"analysis.started","payload":{},"protocol":1}' \
    > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "uma marca de ordem de bytes"                  fail "marca de ordem de bytes"

  seed
  printf '%s\r\n' '{"kind":"event","event":"analysis.started","payload":{},"protocol":1}' \
    > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "fins de linha CRLF"                           fail "retorno de carro"

  seed
  printf '{"a":"\377"}\n' > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "um byte que não é UTF-8 válido"               fail "não é UTF-8 válido"

  seed
  printf '%s' '{"kind":"event","event":"analysis.started","payload":{},"protocol":1}' \
    > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "sem quebra de linha no fim"                   fail "não termina com quebra de linha"

  seed
  printf '%s\n\n%s\n' \
    '{"kind":"event","event":"analysis.started","payload":{},"protocol":1}' \
    '{"kind":"event","event":"analysis.started","payload":{},"protocol":1}' \
    > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "uma linha em branco entre frames"             fail "linha em branco"

  seed
  printf '%s\n' 'nao e um objeto' > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "uma linha que não é objeto JSON"              fail "deve ser um objeto JSON"

  seed
  printf '%s\n' '{"kind":"event","event":"analysis.started","payload":{}' \
    > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "uma chave desbalanceada"                      fail "desbalanceado"

  seed
  printf '%s\n' '{"kind":"event","event":"analysis.started}' \
    > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "uma string não terminada"                     fail "string não terminada"

  seed
  printf '{"a":"b\001c"}\n' > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "um byte de controle cru"                      fail "byte de controle"

  seed
  printf '%s\n' '{"kind":"event","event":"analysis.warning","payload":{"filePath":"/Users/alguem/x"},"protocol":1}' \
    > "$tmp/fixtures/analysis.warning.leak.jsonl"
  expect "um caminho absoluto POSIX"                    fail "caminho absoluto"

  seed
  printf '%s\n' '{"kind":"event","event":"analysis.warning","payload":{"filePath":"C:\\\\Users\\\\x"},"protocol":1}' \
    > "$tmp/fixtures/analysis.warning.leak.jsonl"
  expect "uma letra de unidade do Windows"              fail "caminho absoluto"

  seed
  printf '%s\n' '{"kind":"event","event":"analysis.warning","payload":{"filePath":"\\\\\\\\srv\\\\share"},"protocol":1}' \
    > "$tmp/fixtures/analysis.warning.leak.jsonl"
  expect "um compartilhamento UNC"                      fail "caminho absoluto"

  seed
  rm -f "$tmp"/fixtures/*.jsonl
  expect "uma pasta sem nenhuma fixture"                fail "vacuidade: nenhuma fixture"

  seed
  : > "$tmp/fixtures/analysis.started.ok.jsonl"
  expect "uma fixture vazia"                            fail "está vazio"

  seed
  expect "uma contagem de repositórios que não confere" fail "repositórios" --expect-repositories 7

  seed
  expect "um frame acima do limite declarado"           fail "frame acima do limite" --max-frame 50

  seed
  expect "uma opção desconhecida é erro de uso"         usage "opção desconhecida" --frobnicate

  printf '\n%d ok, %d com falha\n' "$pass" "$failed"
  [ "$failed" -eq 0 ]
}

if [ "${SELF_TEST:-0}" = 1 ]; then
  self_test
  exit $?
fi

run_check "$FIXTURES_DIR" "$REPOSITORIES_DIR" "$EXPECT_REPOSITORIES" "$COUNT_ONLY"
