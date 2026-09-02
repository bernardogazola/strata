#!/usr/bin/env bash

set -euo pipefail

export LC_ALL=C

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
FIXTURES_DIR="$ROOT/protocol/fixtures"
REPOSITORIES_DIR="$ROOT/fixtures/repositories"
EXPECT_REPOSITORIES=7
EXPECT_GIT=0
COUNT_ONLY=""
MAX_FRAME_BYTES=16777216

usage() {
  cat <<'USAGE'
uso: verify-fixtures.sh [opções]

  --fixtures <pasta>     fixtures de protocolo    (padrão: <repo>/protocol/fixtures)
  --repositories <pasta> fixtures de repositório  (padrão: <repo>/fixtures/repositories)
  --expect-repositories <n>  quantas fixtures de repositório devem existir (padrão: 7)
  --expect-git           exige também que cada fixture seja um repositório git gerado
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
    --expect-git)   EXPECT_GIT=1; shift ;;
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

  if (NREPOS == 0) {
    printf "  pulado  inventário do corpus .... nenhuma fixture de repositório para examinar\n"
    printf "  pulado  codificação do golden ... nenhum expected.json para ler\n"
    printf "  pulado  forma do golden ......... nenhum expected.json para ler\n"
    printf "  pulado  índice externo .......... nenhuma fixture de repositório para versionar\n"
    printf "  pulado  git das fixtures ........ nenhuma fixture de repositório para gerar\n"
  } else {
    printf "  %s  inventário do corpus .... %d fixture%s com expected.json, README.md, .java\n",
           (CORPUSINV == "" ? "ok    " : "falha "), NREPOS, (NREPOS == 1 ? "" : "s")
    printf "  %s  codificação do golden ... UTF-8 válido, sem BOM, sem CR, com quebra de linha no fim\n",
           (GOLDENENC == "" ? "ok    " : "falha ")
    printf "  %s  forma do golden ......... %d golden%s, chaves presentes, nada vazio\n",
           (GOLDENSHAPE == "" ? "ok    " : "falha "), NGOLDEN, (NGOLDEN == 1 ? "" : "s")
    if (INDEXSKIP != "")
      printf "  pulado  índice externo .......... %s\n", INDEXSKIP
    else
      printf "  %s  índice externo .......... %d gitlink%s, %d caminho%s versionado%s\n",
             (OUTERINDEX == "" ? "ok    " : "falha "), NGITLINK, (NGITLINK == 1 ? "" : "s"),
             NTRACKED, (NTRACKED == 1 ? "" : "s"), (NTRACKED == 1 ? "" : "s")
    if (EXPECTGIT + 0 == 0)
      printf "  pulado  git das fixtures ........ não pedido; passe --expect-git depois de gerar\n"
    else
      printf "  %s  git das fixtures ........ %d de %d geradas no branch main\n",
             (FIXTUREGIT == "" ? "ok    " : "falha "), NGITREPOS, NREPOS
  }

  if (INVENTORY != "") { split(INVENTORY, iv, SEP); for (i in iv) if (iv[i] != "") bad("inventory", iv[i]) }
  if (NAMING != "")    { split(NAMING, nv, SEP);    for (i in nv) if (nv[i] != "") bad("naming", nv[i]) }
  if (ENCODING != "")  { split(ENCODING, ev, SEP);  for (i in ev) if (ev[i] != "") bad("encoding", ev[i]) }
  if (NREPOS != EXPECTREPOS)
    bad("repositories", sprintf("repositórios: %d encontrados, %d esperados", NREPOS, EXPECTREPOS))
  if (CORPUSINV != "")   { split(CORPUSINV, cv, SEP);   for (i in cv) if (cv[i] != "") bad("corpus", cv[i]) }
  if (GOLDENENC != "")   { split(GOLDENENC, gv, SEP);   for (i in gv) if (gv[i] != "") bad("golden", gv[i]) }
  if (GOLDENSHAPE != "") { split(GOLDENSHAPE, sv, SEP); for (i in sv) if (sv[i] != "") bad("golden", sv[i]) }
  if (OUTERINDEX != "")  { split(OUTERINDEX, ov, SEP);  for (i in ov) if (ov[i] != "") bad("index", ov[i]) }
  if (FIXTUREGIT != "")  { split(FIXTUREGIT, fv, SEP);  for (i in fv) if (fv[i] != "") bad("fixgit", fv[i]) }

  if (nerr > 0) {
    print ""
    for (i = 1; i <= nerr; i++) print "  falha  " err[i]
    printf "\n%d problema%s.\n", nerr, (nerr == 1 ? "" : "s")
    exit 1
  }

  printf "fixtures de protocolo: %d\n", NFILES
  printf "repositórios: %d\n", NREPOS
  printf "goldens: %d\n", NGOLDEN
  print "nenhuma fixture fora do formato"
  exit 0
}
AWK

IFS= read -r -d '' AWK_GOLDEN <<'AWK' || true
function bad(m) { NG++; G[NG] = m }
function base(p,  a, n) { n = split(p, a, "/"); return (n > 1 ? a[n-1] "/" a[n] : a[n]) }

FNR == 1 { depth = 0; bracket = 0; sane = 1; haveversion = 0; havefixture = 0; haveclaims = 0 }

{
  if ($0 ~ /"[^"]*[sS][cC][oO][rR][eE][^"]*"[ \t]*:/)
    bad(base(FILENAME) ":" FNR ": golden: um nome de campo casa com /score/i")
  if ($0 ~ /"\//)
    bad(base(FILENAME) ":" FNR ": golden: caminho absoluto, uma string começa com /")
  if ($0 ~ /"[A-Za-z]:(\\\\|\/)/)
    bad(base(FILENAME) ":" FNR ": golden: caminho absoluto, uma letra de unidade do Windows")
  if ($0 ~ /"\\\\\\\\/)
    bad(base(FILENAME) ":" FNR ": golden: caminho absoluto, um compartilhamento UNC")

  if ($0 ~ /^  "[A-Za-z]+": (\[\]|\{\}|""|null),?$/)
    bad(base(FILENAME) ":" FNR ": golden: uma chave do nível superior está presente, mas vazia")

  if ($0 ~ /^  "version":/)  haveversion = 1
  if ($0 ~ /^  "fixture":/)  havefixture = 1
  if ($0 ~ /^  "claims":/)   haveclaims = 1

  skel = $0
  gsub(/"(\\.|[^"\\])*"/, "S", skel)
  if (skel ~ /"/)
    bad(base(FILENAME) ":" FNR ": golden: string não terminada")

  n = length(skel)
  for (i = 1; i <= n; i++) {
    c = substr(skel, i, 1)
    if (c == "{") depth++
    else if (c == "}") { depth--; if (depth < 0) sane = 0 }
    else if (c == "[") bracket++
    else if (c == "]") { bracket--; if (bracket < 0) sane = 0 }
  }
}

END {
  if (depth != 0 || bracket != 0 || sane == 0)
    bad(base(FILENAME) ": golden: { } ou [ ] desbalanceado")
  if (!haveversion) bad(base(FILENAME) ": golden: sem a chave \"version\" no nível superior")
  if (!havefixture) bad(base(FILENAME) ": golden: sem a chave \"fixture\" no nível superior")
  if (!haveclaims)  bad(base(FILENAME) ": golden: sem a chave \"claims\" no nível superior")
  for (i = 1; i <= NG; i++) print G[i]
}
AWK

scan_corpus() {
  local repos="$1" expectgit="$2"
  local US; US=$(printf '\037')
  local dir name golden readme njava toplevel head declared sidecars

  corpusinv=""; goldenenc=""; goldenshape=""; outerindex=""; fixturegit=""
  ngolden=0; ntracked=0; ngitlink=0; ngitrepos=0; indexskip=""

  [ -d "$repos" ] || return 0

  while IFS= read -r dir; do
    name=$(basename "$dir")
    golden="$dir/expected.json"
    readme="$dir/README.md"

    if [ ! -s "$golden" ]; then
      corpusinv="${corpusinv}corpus: ${name}/expected.json ausente ou vazio"$US
    else
      ngolden=$((ngolden + 1))
      if [ "$(head -c 3 "$golden" | od -An -tx1 | tr -d ' \n')" = "efbbbf" ]; then
        goldenenc="${goldenenc}golden: ${name}/expected.json começa com uma marca de ordem de bytes (BOM)"$US
      fi
      if LC_ALL=C grep -q "$(printf '\r')" "$golden"; then
        goldenenc="${goldenenc}golden: ${name}/expected.json contém um retorno de carro (CR)"$US
      fi
      if LC_ALL=C grep -q "$(printf '[\001-\010\013\014\016-\037]')" "$golden"; then
        goldenenc="${goldenenc}golden: ${name}/expected.json contém um byte de controle"$US
      fi
      if ! iconv -f UTF-8 -t UTF-8 < "$golden" > /dev/null 2>&1; then
        goldenenc="${goldenenc}golden: ${name}/expected.json não é UTF-8 válido"$US
      fi
      if [ "$(tail -c 1 "$golden" | wc -l | tr -d ' ')" -ne 1 ]; then
        goldenenc="${goldenenc}golden: ${name}/expected.json não termina com quebra de linha"$US
      fi
      while IFS= read -r line; do
        [ -n "$line" ] && goldenshape="${goldenshape}${line}"$US
      done < <(awk "$AWK_GOLDEN" "$golden")
    fi

    [ -s "$readme" ] || corpusinv="${corpusinv}corpus: ${name}/README.md ausente ou vazio"$US

    njava=$(find "$dir" -name .git -prune -o -type f -name '*.java' -print | wc -l | tr -d ' ')
    [ "$njava" -gt 0 ] || corpusinv="${corpusinv}corpus: ${name} não tem nenhum arquivo .java"$US

    if [ "$expectgit" = 1 ]; then
      if [ ! -d "$dir/.git" ]; then
        fixturegit="${fixturegit}git da fixture: ${name} não tem .git; rode bash scripts/make-fixtures.sh"$US
        continue
      fi
      ngitrepos=$((ngitrepos + 1))
      if [ "$(git -C "$dir" symbolic-ref --quiet --short HEAD 2>/dev/null || echo '?')" != "main" ]; then
        fixturegit="${fixturegit}git da fixture: ${name} não está no branch main"$US
      fi
      sidecars=$(git -C "$dir" ls-files 2>/dev/null | grep -c -E '^(expected\.json|README\.md|cloc-golden\.txt)$' || true)
      if [ "$sidecars" != "0" ]; then
        fixturegit="${fixturegit}git da fixture: ${name} versiona um sidecar no próprio histórico"$US
      fi
      head=$(git -C "$dir" rev-parse HEAD 2>/dev/null || echo "")
      declared=$(awk -v n="$name" '$1 == n { print $2 }' "$repos/commits.txt" 2>/dev/null || true)
      if [ -z "$declared" ]; then
        fixturegit="${fixturegit}git da fixture: ${name} não tem linha em commits.txt"$US
      elif [ "$declared" != "$head" ]; then
        fixturegit="${fixturegit}git da fixture: ${name} está em $head, e o commits.txt diz $declared"$US
      fi
    fi
  done < <(find "$repos" -maxdepth 1 -mindepth 1 -type d | LC_ALL=C sort)

  if [ "$expectgit" = 1 ] && [ ! -s "$repos/commits.txt" ]; then
    corpusinv="${corpusinv}corpus: commits.txt ausente ou vazio"$US
  fi

  toplevel=$(git -C "$repos" rev-parse --show-toplevel 2>/dev/null || true)
  if [ -z "$toplevel" ]; then
    indexskip="o corpus não está dentro de uma work tree do git"
    return 0
  fi
  ngitlink=$(git -C "$repos" ls-files -s -- . 2>/dev/null | awk '$1 == "160000"' | wc -l | tr -d ' ')
  ntracked=$(git -C "$repos" ls-files -- . 2>/dev/null | wc -l | tr -d ' ')
  if [ "$ngitlink" != "0" ]; then
    outerindex="${outerindex}índice: $ngitlink gitlink(s); os fontes não estão no repositório externo"$US
  fi
  if [ "$ntracked" = "0" ]; then
    outerindex="${outerindex}índice: vacuidade: nada dentro do corpus é versionado, então um clone novo não receberia nada"$US
  fi
}

name_is_valid() {
  case "$1" in
    *.jsonl) : ;;
    *) return 1 ;;
  esac
  printf '%s' "$1" | grep -Eq '^[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9-]*)*\.jsonl$'
}

run_check() {
  local dir="$1" repos="$2" expect="$3" count_only="$4" expectgit="${5:-0}"
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

  scan_corpus "$repos" "$expectgit"

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
      -v EXPECTGIT="$expectgit" -v NGOLDEN="$ngolden" -v NTRACKED="$ntracked" \
      -v NGITLINK="$ngitlink" -v NGITREPOS="$ngitrepos" -v INDEXSKIP="$indexskip" \
      -v CORPUSINV="$corpusinv" -v GOLDENENC="$goldenenc" -v GOLDENSHAPE="$goldenshape" \
      -v OUTERINDEX="$outerindex" -v FIXTUREGIT="$fixturegit" \
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
    REPOS_ARG="$tmp/absent"
    EXPECT_ARG=0
  }

  seed_repos() {
    rm -rf "$tmp/outer"
    mkdir -p "$tmp/outer/repositories/alpha/src/main/java"
    printf '# corpus\n' > "$tmp/outer/repositories/README.md"
    printf 'class A {}\n' > "$tmp/outer/repositories/alpha/src/main/java/A.java"
    printf '# alpha\n' > "$tmp/outer/repositories/alpha/README.md"
    cat > "$tmp/outer/repositories/alpha/expected.json" <<'GOLDEN'
{
  "version": 1,
  "fixture": "alpha",
  "claims": ["sourceRoots"],
  "sourceRoots": [
    {"path": "src/main/java", "kind": "MAIN", "javaFileCount": 1}
  ]
}
GOLDEN
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c init.defaultBranch=main init -q "$tmp/outer"
    REPOS_ARG="$tmp/outer/repositories"
    EXPECT_ARG=1
  }

  outer_commit() {
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -C "$tmp/outer" -c core.excludesFile=/dev/null add -A >/dev/null 2>&1
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t \
      GIT_AUTHOR_DATE="2020-01-01T00:00:00+0000" GIT_COMMITTER_DATE="2020-01-01T00:00:00+0000" \
      git -C "$tmp/outer" -c user.name=t -c user.email=t@t commit -q --no-verify -m seed
  }

  expect() {
    local name="$1" want="$2" needle="$3"; shift 3
    local out rc
    set +e
    out=$(bash "$0" --fixtures "$tmp/fixtures" \
            --repositories "${REPOS_ARG:-$tmp/absent}" \
            --expect-repositories "${EXPECT_ARG:-0}" "$@" 2>&1)
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

  seed; seed_repos; outer_commit
  expect "um corpus saudável passa"                     ok "nenhuma fixture fora do formato"

  seed; seed_repos
  mkdir -p "$tmp/outer/repositories/alpha/src/main/java/com/x/home"
  printf 'class Home {}\n' > "$tmp/outer/repositories/alpha/src/main/java/com/x/home/Home.java"
  sed 's|"claims": \["sourceRoots"\]|"claims": ["files", "sourceRoots"],\n  "files": ["file:src/main/java/com/x/home/Home.java"]|' \
    "$tmp/outer/repositories/alpha/expected.json" > "$tmp/g" && mv "$tmp/g" "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "um segmento de pacote chamado home não é caminho" ok "nenhuma fixture fora do formato"

  seed; seed_repos; outer_commit
  expect "--count repositories mostra só o número"      ok "1" --count repositories

  seed; seed_repos; outer_commit
  rm -f "$tmp/outer/repositories/alpha/expected.json"
  expect "uma fixture sem expected.json"                fail "expected.json ausente"

  seed; seed_repos; outer_commit
  rm -f "$tmp/outer/repositories/alpha/README.md"
  expect "uma fixture sem README.md"                    fail "README.md ausente"

  seed; seed_repos; outer_commit
  rm -f "$tmp/outer/repositories/alpha/src/main/java/A.java"
  expect "uma fixture sem arquivo .java"                fail "não tem nenhum arquivo .java"

  seed; seed_repos
  sed 's|"claims": \["sourceRoots"\]|"claims": []|' "$tmp/outer/repositories/alpha/expected.json" \
    > "$tmp/g" && mv "$tmp/g" "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "uma chave do nível superior presente, mas vazia" fail "presente, mas vazia"

  seed; seed_repos
  grep -v '"claims"' "$tmp/outer/repositories/alpha/expected.json" > "$tmp/g" \
    && mv "$tmp/g" "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "um golden sem a chave claims"                 fail 'sem a chave "claims" no nível superior'

  seed; seed_repos
  sed 's|"javaFileCount"|"bridgeScore"|' "$tmp/outer/repositories/alpha/expected.json" \
    > "$tmp/g" && mv "$tmp/g" "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "um nome de campo que casa com /score/i"       fail "casa com /score/i"

  seed; seed_repos
  sed 's|"src/main/java"|"/Users/alguem/src"|' "$tmp/outer/repositories/alpha/expected.json" \
    > "$tmp/g" && mv "$tmp/g" "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "um golden com caminho absoluto"               fail "golden: caminho absoluto"

  seed; seed_repos
  awk '{ printf "%s\r\n", $0 }' "$tmp/outer/repositories/alpha/expected.json" > "$tmp/g" \
    && mv "$tmp/g" "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "um golden em CRLF"                            fail "golden: alpha/expected.json contém um retorno de carro"

  seed; seed_repos
  printf '\357\273\277' > "$tmp/g"
  cat "$tmp/outer/repositories/alpha/expected.json" >> "$tmp/g"
  mv "$tmp/g" "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "um golden com marca de ordem de bytes"        fail "marca de ordem de bytes"

  seed; seed_repos
  printf '%s' "$(cat "$tmp/outer/repositories/alpha/expected.json")" > "$tmp/g" \
    && mv "$tmp/g" "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "um golden sem quebra de linha no fim"         fail "não termina com quebra de linha"

  seed; seed_repos
  printf '{\n  "version": 1,\n  "fixture": "alpha",\n  "claims": ["sourceRoots"\n}\n' \
    > "$tmp/outer/repositories/alpha/expected.json"
  outer_commit
  expect "um golden com colchetes desbalanceados"       fail "desbalanceado"

  seed; seed_repos
  GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    git -c init.defaultBranch=main init -q "$tmp/outer/repositories/alpha"
  GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t \
    GIT_AUTHOR_DATE="2020-01-01T00:00:00+0000" GIT_COMMITTER_DATE="2020-01-01T00:00:00+0000" \
    sh -c 'git -C "$1" add -A && git -C "$1" -c user.name=t -c user.email=t@t commit -q -m c1' \
    _ "$tmp/outer/repositories/alpha" >/dev/null 2>&1
  outer_commit
  expect "um gitlink commitado no lugar dos fontes"     fail "gitlink"

  seed; seed_repos
  expect "um corpus que não está versionado em lugar nenhum" fail "um clone novo não receberia nada"

  seed; seed_repos; outer_commit
  expect "--expect-git sem .git na fixture"             fail "não tem .git" --expect-git

  seed
  expect "uma opção desconhecida é erro de uso"         usage "opção desconhecida" --frobnicate

  printf '\n%d ok, %d com falha\n' "$pass" "$failed"
  [ "$failed" -eq 0 ]
}

if [ "${SELF_TEST:-0}" = 1 ]; then
  self_test
  exit $?
fi

run_check "$FIXTURES_DIR" "$REPOSITORIES_DIR" "$EXPECT_REPOSITORIES" "$COUNT_ONLY" "$EXPECT_GIT"
