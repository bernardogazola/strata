#!/usr/bin/env bash

set -euo pipefail

export LC_ALL=C

SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SELF="$SELF_DIR/$(basename -- "$0")"
ROOT=$(CDPATH= cd -- "$SELF_DIR/.." && pwd)

BASELINE_PERMISSIONS="
core:default
core:window:allow-show
core:window:allow-set-focus
core:window:allow-set-background-color
core:webview:allow-set-webview-background-color
"

BASELINE_CAPABILITY_FILES="default.json"

DENIED_NAMESPACES="fs shell dialog"

PLUGIN_TABLE="
    dialog      deny         allow        allow        deny
"

CSP_CONNECT_PROD="'self' ipc: http://ipc.localhost"
CSP_CONNECT_DEV="'self' ipc: http://ipc.localhost ws://localhost:1420 ws://localhost:1421 http://localhost:1420"

CSP_REQUIRED_DIRECTIVES="default-src 'self'|object-src 'none'|frame-src 'none'|frame-ancestors 'none'|base-uri 'none'|form-action 'none'"

AUDIT_ROOT="$ROOT"
SELF_TEST=0

usage() {
  cat <<'USAGE'
uso: audit-capabilities.sh [opções]

  --root <pasta>     raiz do repositório a auditar   (padrão: o repositório deste script)
  --self-test        roda o verificador contra fixtures sintéticas e sai
  --help             mostra este texto

Verificações, em ordem:
  arquivos de capability  a pasta capabilities tem exatamente os arquivos declarados
  permissões              o conjunto concedido é igual à baseline declarada neste script
  namespaces              nenhuma permissão fs:, shell: ou dialog:, diga o que disser a baseline
  forma da capability     sem bloco remote, sem glob de janela, sem entrada com escopo, sem escape
  superfícies de plugin   capability · Cargo.toml · registro em .rs · package.json
  csp                     connect-src exato nas duas políticas; seis diretivas fixadas
  superfície da webview   withGlobalTauri · assetProtocol · freezePrototype · capabilities inline

saída: 0 ok · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --root)      AUDIT_ROOT="${2:-}"; shift 2 ;;
    --self-test) SELF_TEST=1; shift ;;
    --help|-h)   usage; exit 0 ;;
    *)           echo "audit-capabilities: opção desconhecida '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

abspath_dir()  { ( CDPATH= cd -- "$1" >/dev/null 2>&1 && pwd ) || printf '%s\n' "$1"; }
abspath_file() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *)  ( CDPATH= cd -- "$(dirname -- "$1")" >/dev/null 2>&1 &&
          printf '%s/%s\n' "$PWD" "$(basename -- "$1")" ) || printf '%s\n' "$1" ;;
  esac
}

IFS= read -r -d '' AWK_PROG <<'AWK' || true
function trim(s)        { sub(/^[ \t\r\n]+/, "", s); sub(/[ \t\r\n]+$/, "", s); return s }
function base(p,   a,n) { n = split(p, a, "/"); return a[n] }
function bad(tag, m)    { nerr++; err[nerr] = m; etag[tag]++ }
function st(tag)        { return (etag[tag] + 0 == 0 ? "ok   " : "falha") }
function nsof(id,   a)  { if (index(id, ":") == 0) return ""; split(id, a, ":"); return a[1] }

function length_of(a,   k, n) { for (k in a) n++; return n + 0 }

function toset(s, out,   i, n, a, c) {
  n = split(s, a, /[ \t\n]+/)
  for (i = 1; i <= n; i++) if (a[i] != "") { out[a[i]] = 1; c++ }
  return c + 0
}
function sortlist(set, out,   k, n, i, j, t) {
  n = 0
  for (k in set) out[++n] = k
  for (i = 2; i <= n; i++) {
    t = out[i]; j = i - 1
    while (j >= 1 && out[j] > t) { out[j + 1] = out[j]; j-- }
    out[j + 1] = t
  }
  return n
}
function joinset(set,   a, n, i, r) {
  n = sortlist(set, a)
  for (i = 1; i <= n; i++) r = r (i == 1 ? "" : ", ") a[i]
  return r
}
function diffjoin(have, want,   k, d) {
  for (k in have) if (!(k in want)) d[k] = 1
  return joinset(d)
}

function jpath(d,   p) {
  if (d == 0) return ""
  if (ctype[d] == "a") return jpth[d] "[]"
  p = jpth[d]
  return (p == "" ? "" : p ".") pendkey[d]
}
function jflat(f, s,   n, i, j, c, str, hadesc, d, tok, kind, p) {
  n = length(s); i = 1; d = 0
  ctype[0] = "a"; jpth[0] = ""; haskey[0] = 0
  while (i <= n) {
    c = substr(s, i, 1)
    if (c == " " || c == "\t" || c == "\n" || c == "\r") { i++; continue }
    if (c == ":" || c == ",") { i++; continue }
    if (c == "/") {
      bad("shape", base(f) ": um '/' fora de string, no byte " i ". JSON não tem comentários, e o serde_json lê este arquivo como JSON")
      return 0
    }
    if (c == "}" || c == "]") {
      if (d == 0) { bad("shape", base(f) ": um fechamento de chave sem nada aberto"); return 0 }
      d--; i++; continue
    }
    hadesc = 0
    if (c == "\"") {
      j = i + 1; str = ""
      while (j <= n) {
        c = substr(s, j, 1)
        if (c == "\\") { hadesc = 1; str = str c substr(s, j + 1, 1); j += 2; continue }
        if (c == "\"") break
        str = str c; j++
      }
      if (j > n) { bad("shape", base(f) ": uma string não terminada"); return 0 }
      i = j + 1; tok = str; kind = "s"
    } else if (c == "{" || c == "[") {
      tok = ""; kind = (c == "{" ? "o" : "a"); i++
    } else {
      j = i
      while (j <= n && index(" \t\r\n,]}", substr(s, j, 1)) == 0) j++
      tok = substr(s, i, j - i); kind = "l"; i = j
    }
    if (ctype[d] == "o" && !haskey[d]) {
      if (kind != "s") { bad("shape", base(f) ": uma chave de objeto que não é string"); return 0 }
      pendkey[d] = tok
      if (hadesc) escseen[f] = 1
      haskey[d] = 1
      continue
    }
    p = jpath(d)
    jn[f, p]++
    jkind[f, p, jn[f, p]] = kind
    jval[f, p, jn[f, p]]  = tok
    if (hadesc) escseen[f] = 1
    haskey[d] = 0
    if (kind == "o" || kind == "a") { d++; ctype[d] = kind; jpth[d] = p; haskey[d] = 0 }
  }
  if (d != 0) { bad("shape", base(f) ": o documento termina com " d " contêiner(es) ainda aberto(s)"); return 0 }
  return 1
}

function directive(policy, name,   n, a, i, t) {
  n = split(policy, a, ";")
  for (i = 1; i <= n; i++) {
    t = trim(a[i])
    if (t == name) return "\001"
    if (index(t, name " ") == 1) return trim(substr(t, length(name) + 2))
  }
  return ""
}
function reqdirective(label, policy, spec,   sp, dname, dval, got) {
  sp = index(spec, " ")
  dname = substr(spec, 1, sp - 1)
  dval  = trim(substr(spec, sp + 1))
  got = directive(policy, dname)
  if (got == "")   { bad("csp", label ": sem a diretiva \"" dname "\""); return }
  if (got != dval)   bad("csp", label ": " dname " é \"" got "\", e este projeto usa \"" dval "\"")
}
function checkcsp(label, policy, wantconn, devmode,   n, a, i, dv, haveconn, extra, miss) {
  n = split(REQ_DIRECTIVES, a, "|")
  for (i = 1; i <= n; i++) if (trim(a[i]) != "") reqdirective(label, policy, trim(a[i]))

  dv = directive(policy, "connect-src")
  if (dv == "" || dv == "\001") {
    bad("csp", label ": sem connect-src. Aí quem decide é o default-src, e \"zero requisições de saída\" é uma garantia que deve estar escrita onde é lida.")
    return
  }
  toset(dv, haveconn)
  extra = diffjoin(haveconn, wantconn)
  miss  = diffjoin(wantconn, haveconn)
  if (extra != "") bad("csp", label ": connect-src libera " extra ". O app não faz requisições de saída, e é o connect-src que garante isso")
  if (miss  != "") bad("csp", label ": falta no connect-src: " miss)

  if (index(policy, "unsafe-eval")) bad("csp", label ": 'unsafe-eval'")
  if (!devmode && index(policy, "unsafe-inline"))
    bad("csp", label ": 'unsafe-inline' na política de produção. O Tauri injeta as fontes de nonce e hash na compilação; a política de produção não precisa de nenhuma das duas.")
}
function decomment(line, style,   h, q) {
  h = index(line, style)
  if (h == 0) return line
  q = index(line, "\"")
  if (q > 0 && q < h) return line
  return substr(line, 1, h - 1)
}
function normplug(p) { gsub(/_/, "-", p); return p }
function scanplug(line, pat, out,   s, m) {
  s = line
  while (match(s, pat)) {
    m = substr(s, RSTART, RLENGTH)
    sub(/^tauri.plugin./, "", m)
    if (m != "") out[normplug(m)] = 1
    s = substr(s, RSTART + RLENGTH)
  }
}
function addperm(f, id) {
  if (id == "") { bad("shape", base(f) ": um identificador de permissão vazio"); return }
  if (id in perm_set) bad("shape", "a permissão \"" id "\" é concedida mais de uma vez")
  perm_set[id] = 1
}
function npmhit(x,   m) {
  while (match(x, "@tauri-apps/plugin-[a-z0-9-]+")) {
    m = substr(x, RSTART + 19, RLENGTH - 19)
    npm_plug[m] = 1
    x = substr(x, RSTART + RLENGTH)
  }
}
function joinsetpfx(a,   b, k) { for (k in a) b[k ":"] = 1; return joinset(b) }

BEGIN {
  split(" $schema build build.beforeBuildCommand bundle bundle.resources", ovk, " ")
  for (oi in ovk) overlay_ok[ovk[oi]] = 1
  overlay_ok[""] = 1
  nbase  = toset(BASE_PERMS, want_perm)
  ncapb  = toset(BASE_CAPFILES, want_capfile)
  toset(DENY_NS, deny_ns)
  toset(CONNECT_PROD, want_conn_prod)
  toset(CONNECT_DEV,  want_conn_dev)

  nrows = split(PLUGINS, prow, ";")
  for (i = 1; i <= nrows; i++) {
    line = trim(prow[i])
    if (line == "" || substr(line, 1, 1) == "#") continue
    m = split(line, pf, /[ \t]+/)
    if (m != 5) { bad("plugin", "tabela de plugins: a linha \"" line "\" tem " m " colunas, e são esperadas 5"); continue }
    ptab[pf[1], "cap"] = pf[2]; ptab[pf[1], "cargo"] = pf[3]
    ptab[pf[1], "rs"]  = pf[4]; ptab[pf[1], "npm"]   = pf[5]
  }
}

{ jsrc[FILENAME] = jsrc[FILENAME] $0 "\n"; seen[FILENAME] = 1 }

END {
  if (!(CONF  in seen)) bad("files", "ausente ou vazio: " CONF)
  if (!(CARGO in seen)) bad("files", "ausente ou vazio: " CARGO)
  if (!(PKG   in seen)) bad("files", "ausente ou vazio: " PKG)
  for (f in seen) {
    if (index(f, CAPDIR "/") == 1) capf[f] = 1
    else if (f ~ /\.rs$/)          rsf[f]  = 1
  }
  ncapread = sortlist(capf, capl)
  nrsread  = sortlist(rsf,  rsl)
  if (ncapread == 0)
    bad("files", "nenhum arquivo de capability foi lido em " CAPDIR ", então toda verificação abaixo passaria por vacuidade")
  if (nrsread == 0)
    bad("files", "nenhum arquivo .rs foi lido em " ROOTDIR "/src-tauri, então a verificação de registro de plugins passaria por vacuidade")

  nfound = toset(CAPFOUND, found_capfile)
  xf = diffjoin(found_capfile, want_capfile)
  mf = diffjoin(want_capfile, found_capfile)
  if (xf != "")
    bad("files", "pasta de capabilities: " xf " não está declarado neste script. O Tauri mescla todo arquivo de capability que encontra (.json, .toml e .json5), então um arquivo que esta auditoria não consegue ler poderia conceder qualquer coisa.")
  if (mf != "")
    bad("files", "pasta de capabilities: " mf " está declarado neste script e não está no disco")

  for (ci = 1; ci <= ncapread; ci++) {
    cf = capl[ci]
    if (!jflat(cf, jsrc[cf])) { nparsefail++; continue }

    if (escseen[cf])
      bad("shape", base(cf) ": um escape com barra invertida. \"\\u0066s:default\" é fs:default para o serde_json e não é para nenhuma comparação de string daqui, então todo escape reprova.")
    if (jn[cf, ""] != 1)
      bad("shape", base(cf) ": " (jn[cf, ""] + 0) " valores no nível superior, e é esperado exatamente 1")
    if (jn[cf, "identifier"] != 1 || jval[cf, "identifier", 1] == "")
      bad("shape", base(cf) ": sem \"identifier\"")

    if (jn[cf, "permissions"] == 0) { bad("shape", base(cf) ": sem o array \"permissions\""); continue }
    if (jn[cf, "permissions"] > 1) {
      bad("shape", base(cf) ": a chave \"permissions\" aparece " jn[cf, "permissions"] " vezes, e o serde_json fica só com a última.")
      continue
    }
    if (jkind[cf, "permissions", 1] != "a") { bad("shape", base(cf) ": \"permissions\" não é um array"); continue }

    nobj = 0
    for (k = 1; k <= jn[cf, "permissions[]"]; k++) {
      kd = jkind[cf, "permissions[]", k]
      if      (kd == "s") addperm(cf, jval[cf, "permissions[]", k])
      else if (kd == "o") nobj++
      else bad("shape", base(cf) ": a entrada de permissão " k " não é string nem objeto")
    }
    nid = jn[cf, "permissions[].identifier"] + 0
    if (nid != nobj)
      bad("shape", base(cf) ": " nobj " entrada" (nobj == 1 ? "" : "s") " de permissão em forma de objeto, mas " nid " chave(s) \"identifier\"; uma entrada sem identifier reprova")
    for (k = 1; k <= nid; k++) addperm(cf, jval[cf, "permissions[].identifier", k])

    if (jn[cf, "permissions[].allow"] > 0 || jn[cf, "permissions[].deny"] > 0)
      bad("shape", base(cf) ": uma entrada de permissão traz escopo allow/deny. Escopos só valem para namespaces com escopo (fs, http, shell, asset), que este projeto nunca concede.")

    if (jn[cf, "remote"] > 0)
      bad("shape", base(cf) ": um bloco \"remote\" concede estas mesmas permissões a uma origem remota, e nenhum diff da lista de permissões mostraria isso")
    if (jn[cf, "local"] > 0 && jval[cf, "local", 1] != "true")
      bad("shape", base(cf) ": \"local\": " jval[cf, "local", 1] ". Uma capability que não é local só serve a uma origem remota")

    if (jn[cf, "windows"] == 0)
      bad("shape", base(cf) ": sem a chave \"windows\", então a capability vale para toda janela, inclusive as futuras")
    for (k = 1; k <= jn[cf, "windows[]"]; k++)
      if (index(jval[cf, "windows[]", k], "*") > 0)
        bad("shape", base(cf) ": o padrão de janela \"" jval[cf, "windows[]", k] "\" é um glob")
    for (k = 1; k <= jn[cf, "webviews[]"]; k++)
      if (index(jval[cf, "webviews[]", k], "*") > 0)
        bad("shape", base(cf) ": o padrão de webview \"" jval[cf, "webviews[]", k] "\" é um glob")
  }

  if (nparsefail > 0)
    bad("baseline", "o conjunto de permissões não foi comparado: não foi possível interpretar " nparsefail " arquivo(s) de capability")
  else {
    added   = diffjoin(perm_set, want_perm)
    dropped = diffjoin(want_perm, perm_set)
    if (added != "")
      bad("baseline", "permissões concedidas que este script não declara: " added ". Uma permissão nova exige a edição correspondente em BASELINE_PERMISSIONS no mesmo commit.")
    if (dropped != "")
      bad("baseline", "permissões declaradas neste script e não concedidas: " dropped ". Remova-as de BASELINE_PERMISSIONS no mesmo commit.")
  }

  for (id in perm_set) {
    np = nsof(id)
    if (np == "") { bad("deny", "a permissão \"" id "\" não tem namespace"); continue }
    if (np in deny_ns) hit_deny[id] = 1
  }
  if (length_of(hit_deny) > 0)
    bad("deny", "namespace proibido: " joinset(hit_deny) ". A WebView nunca recebe estes namespaces, diga o que disser a baseline.")

  for (id in perm_set) {
    np = nsof(id)
    if (np == "" || np == "core") continue
    cap_plug[np] = 1
    if (ptab[np, "cap"] != "allow")
      bad("plugin", "o arquivo de capability concede \"" id "\", e a tabela de plugins não permite \"" np "\" na superfície de capability. Plugins são negados por padrão.")
  }
  nl = split(jsrc[CARGO], cl, "\n")
  for (i = 1; i <= nl; i++) scanplug(decomment(cl[i], "#"), "tauri-plugin-[a-z0-9-]+", cargo_plug)
  for (k in cargo_plug)
    if (ptab[k, "cargo"] != "allow")
      bad("plugin", "src-tauri/Cargo.toml depende de tauri-plugin-" k ", que a tabela de plugins não permite. Remover um plugin exige quatro edições, e esta é fácil de esquecer.")

  for (ri = 1; ri <= nrsread; ri++) {
    nl = split(jsrc[rsl[ri]], cl, "\n")
    for (i = 1; i <= nl; i++) scanplug(decomment(cl[i], "//"), "tauri_plugin_[a-z0-9_]+", rs_plug)
  }
  for (k in rs_plug)
    if (ptab[k, "rs"] != "allow")
      bad("plugin", "um arquivo .rs em src-tauri/ cita tauri_plugin_" k ", que a tabela de plugins não permite. Um plugin registrado é autoridade mesmo quando nada importa o binding JS dele.")

  if (jflat(PKG, jsrc[PKG])) {
    for (kk in jn) {
      split(kk, ka, SUBSEP)
      if (ka[1] != PKG) continue
      npmhit(ka[2])
      for (i = 1; i <= jn[kk]; i++) npmhit(jval[PKG, ka[2], i])
    }
  } else bad("plugin", "não foi possível interpretar o package.json")
  for (k in npm_plug)
    if (ptab[k, "npm"] != "allow")
      bad("plugin", "package.json declara @tauri-apps/plugin-" k ". O binding JS de um plugin que a WebView não pode chamar parece uma capability concedida.")

  if (jflat(CONF, jsrc[CONF])) {
    if (jn[CONF, "app.security.csp"] != 1)
      bad("csp", "tauri.conf.json não tem app.security.csp. Sem política, a WebView pode buscar qualquer origem.")
    else checkcsp("csp", jval[CONF, "app.security.csp", 1], want_conn_prod, 0)

    if (jn[CONF, "app.security.devCsp"] != 1)
      bad("csp", "tauri.conf.json não tem app.security.devCsp")
    else checkcsp("devCsp", jval[CONF, "app.security.devCsp", 1], want_conn_dev, 1)

    if (jn[CONF, "app.security.capabilities"] > 0)
      bad("surface", "tauri.conf.json define app.security.capabilities inline. Capabilities inline são mescladas com os arquivos de " CAPDIR " e não aparecem em um diff dessa pasta.")
    if (jn[CONF, "app.withGlobalTauri"] > 0 && jval[CONF, "app.withGlobalTauri", 1] == "true")
      bad("surface", "app.withGlobalTauri é true: todo comando permitido fica acessível por window.__TAURI__, fora de src/bridge/, e um único <script> injetado ganha toda a superfície de IPC.")
    if (jn[CONF, "app.security.assetProtocol.enable"] > 0 && jval[CONF, "app.security.assetProtocol.enable", 1] == "true")
      bad("surface", "app.security.assetProtocol.enable é true: o asset:// lê arquivos do disco para a WebView sem nenhuma permissão fs:, e o diff de permissões acima não mostraria isso.")
    if (jn[CONF, "app.security.dangerousDisableAssetCspModification"] > 0 && jval[CONF, "app.security.dangerousDisableAssetCspModification", 1] != "false")
      bad("surface", "dangerousDisableAssetCspModification está ativo: o Tauri deixa de injetar as fontes de nonce e hash, a CSP passa a ser mantida à mão, e o script inline do tema precisa de um hash escrito à mão que vai ficar desatualizado")
    if (jn[CONF, "app.security.freezePrototype"] != 1 || jval[CONF, "app.security.freezePrototype", 1] != "true")
      bad("surface", "app.security.freezePrototype não é true")
    for (kk in jn) {
      split(kk, ka, SUBSEP)
      if (ka[1] != CONF || index(ka[2], "plugins.") != 1) continue
      pn = substr(ka[2], 9); split(pn, pp, "."); pn = pp[1]; sub(/\[\]$/, "", pn)
      if (pn != "" && ptab[pn, "cargo"] != "allow")
        bad("plugin", "tauri.conf.json configura o plugin \"" pn "\", que a tabela de plugins não permite")
    }
  } else bad("csp", "não foi possível interpretar o tauri.conf.json")

  nst = split(STRAY_CONF, sca, " ")
  for (i = 1; i <= nst; i++)
    bad("surface", "src-tauri/" sca[i] " é mesclado ao tauri.conf.json pelo Tauri e esta auditoria não o lê: uma CSP ou uma capability inline ali entraria no pacote sem auditoria")
  if (OVERLAY != "") {
    if (jflat(OVERLAY, jsrc[OVERLAY])) {
      for (kk in jn) {
        split(kk, ka, SUBSEP)
        if (ka[1] != OVERLAY) continue
        if (ka[2] in overlay_ok || index(ka[2], "bundle.resources.") == 1) continue
        bad("surface", "src-tauri/tauri.bundle.conf.json define " ka[2] ". O overlay de empacotamento só pode definir build.beforeBuildCommand e bundle.resources: qualquer outra coisa que ele mescle entra no pacote sem passar por esta auditoria")
      }
    } else bad("surface", "não foi possível interpretar o src-tauri/tauri.bundle.conf.json")
  }

  nuniq = length_of(perm_set)

  printf "audit-capabilities · %d arquivo%s de capability · %d permiss%s · baseline %d\n",
         ncapread, (ncapread == 1 ? "" : "s"), nuniq, (nuniq == 1 ? "ão" : "ões"), nbase
  printf "  %s  arquivos de capability ... %d no disco, %d declarado(s)\n",  st("files"),    nfound, ncapb
  printf "  %s  permissões ............... %d concedida(s), %d na baseline\n", st("baseline"), nuniq, nbase
  printf "  %s  namespaces ............... nenhuma permissão %s em arquivo de capability\n",
         st("deny"), joinsetpfx(deny_ns)
  printf "  %s  forma da capability ...... sem bloco remote, sem glob de janela, sem entrada com escopo, sem escape\n", st("shape")
  printf "  %s  superfícies de plugin .... cap %d · Cargo %d · rs %d · npm %d\n",
         st("plugin"), length_of(cap_plug), length_of(cargo_plug), length_of(rs_plug), length_of(npm_plug)
  printf "  %s  csp ...................... connect-src exato nas duas políticas, 6 diretivas fixadas\n", st("csp")
  printf "  %s  superfície da webview .... globalTauri, assetProtocol, freezePrototype, capabilities inline, configurações mescladas\n", st("surface")

  if (nerr > 0) {
    print ""
    for (i = 1; i <= nerr; i++) print "  falha  " err[i]
    printf "\n%d problema%s. A WebView tem autoridade que este repositório não declara.\n",
           nerr, (nerr == 1 ? "" : "s")
    exit 1
  }
  print "nenhuma autoridade fora do conjunto declarado"
  exit 0
}
AWK

run_audit() {
  local root capdir conf cargo pkg srcdir f rel capfound
  root=$(abspath_dir "$1")
  capdir="$root/src-tauri/capabilities"
  conf="$root/src-tauri/tauri.conf.json"
  cargo="$root/src-tauri/Cargo.toml"
  pkg="$root/package.json"
  srcdir="$root/src-tauri"

  local missing=""
  [ -d "$capdir" ] || missing="$missing src-tauri/capabilities/"
  [ -f "$conf" ]   || missing="$missing src-tauri/tauri.conf.json"
  [ -f "$cargo" ]  || missing="$missing src-tauri/Cargo.toml"
  [ -f "$pkg" ]    || missing="$missing package.json"
  [ -d "$srcdir" ] || missing="$missing src-tauri/"
  if [ -n "$missing" ]; then
    printf 'audit-capabilities · %s\n\n  falha  não é uma árvore do Strata, faltam:%s\n\n1 problema.\n' \
           "$root" "$missing"
    return 1
  fi
  capdir=$(abspath_dir "$capdir")
  conf=$(abspath_file "$conf"); cargo=$(abspath_file "$cargo"); pkg=$(abspath_file "$pkg")

  local overlay="" stray="" name
  if [ -f "$srcdir/tauri.bundle.conf.json" ]; then overlay=$(abspath_file "$srcdir/tauri.bundle.conf.json"); fi
  while IFS= read -r name; do
    case "$name" in tauri.conf.json | tauri.bundle.conf.json) ;; *) stray="$stray $name" ;; esac
  done < <(find "$srcdir" -maxdepth 1 -type f \( -iname 'tauri*.conf.json' -o -iname 'tauri*.conf.json5' -o -iname 'tauri*.toml' \) -exec basename {} \; | LC_ALL=C sort)

  capfound=""
  while IFS= read -r f; do
    rel=${f#"$capdir"/}
    capfound="$capfound $rel"
  done < <(find "$capdir" -type f | LC_ALL=C sort)

  local files=()
  while IFS= read -r f; do files+=("$f"); done \
    < <(find "$capdir" -type f -name '*.json' | LC_ALL=C sort)
  files+=("$conf" "$cargo" "$pkg")
  if [ -n "$overlay" ]; then files+=("$overlay"); fi
  while IFS= read -r f; do files+=("$f"); done \
    < <(find "$srcdir" \( -name target -o -name gen \) -prune -o -type f -name '*.rs' -print | LC_ALL=C sort)

  local base_perms base_capfiles deny_ns plugins
  base_perms=$(printf '%s' "$BASELINE_PERMISSIONS" | tr '\n' ' ')
  base_capfiles=$(printf '%s' "$BASELINE_CAPABILITY_FILES" | tr '\n' ' ')
  deny_ns=$(printf '%s' "$DENIED_NAMESPACES" | tr '\n' ' ')
  plugins=$(printf '%s' "$PLUGIN_TABLE" | tr '\n' ';')

  awk -v ROOTDIR="$root" -v CAPDIR="$capdir" -v CONF="$conf" -v CARGO="$cargo" -v PKG="$pkg" \
      -v CAPFOUND="$capfound" \
      -v OVERLAY="$overlay" -v STRAY_CONF="$stray" \
      -v BASE_PERMS="$base_perms" \
      -v BASE_CAPFILES="$base_capfiles" \
      -v DENY_NS="$deny_ns" \
      -v PLUGINS="$plugins" \
      -v CONNECT_PROD="$CSP_CONNECT_PROD" \
      -v CONNECT_DEV="$CSP_CONNECT_DEV" \
      -v REQ_DIRECTIVES="$CSP_REQUIRED_DIRECTIVES" \
      "$AWK_PROG" ${files[@]+"${files[@]}"}
}

self_test() {
  local pass=0 failed=0
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT

  baseline_body() {
    local p first=1
    for p in $BASELINE_PERMISSIONS; do
      if [ "$first" = 1 ]; then first=0; else printf ',\n'; fi
      printf '    "%s"' "$p"
    done
    printf '\n'
  }

  cap_with() {
    printf '{\n  "$schema": "../gen/schemas/desktop-schema.json",\n  "identifier": "default",\n  "description": "Nao inclui dialog, fs nem shell: o seletor roda no Rust.",\n  "windows": ["main"],\n  "permissions": [\n%s  ]\n}\n' \
           "$1" > "$tmp/src-tauri/capabilities/default.json"
  }

  write_conf() {
    printf '{\n  "app": {\n    "security": {\n      "csp": "%s",\n      "devCsp": "%s",\n      "freezePrototype": true%s\n    }\n  }\n}\n' \
           "$1" "$2" "$3" > "$tmp/src-tauri/tauri.conf.json"
  }

  prod_csp() {
    printf "default-src 'self'; connect-src %s; img-src 'self' data: asset:; font-src 'self'; script-src 'self'; style-src 'self'; worker-src 'self'; object-src 'none'; frame-src 'none'; %sbase-uri 'none'; form-action 'none'" \
           "$CSP_CONNECT_PROD" "${1-frame-ancestors 'none'; }"
  }
  dev_csp() {
    printf "default-src 'self'; connect-src %s; img-src 'self' data: asset:; font-src 'self' data:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; worker-src 'self' blob:; object-src 'none'; frame-src 'none'; %sbase-uri 'none'; form-action 'none'" \
           "$CSP_CONNECT_DEV" "${1-frame-ancestors 'none'; }"
  }

  write_overlay() {
    printf '{\n  "$schema": "https://schema.tauri.app/config/2",\n  "build": { "beforeBuildCommand": "pnpm build" },\n  "bundle": { %s }\n}\n' \
           "$1" > "$tmp/src-tauri/tauri.bundle.conf.json"
  }

  seed() {
    rm -rf "$tmp/src-tauri" "$tmp/package.json"
    mkdir -p "$tmp/src-tauri/capabilities" "$tmp/src-tauri/src"
    cap_with "$(baseline_body)"
    write_conf "$(prod_csp)" "$(dev_csp)" ""

    cat > "$tmp/src-tauri/Cargo.toml" <<'C'
[package]
name = "strata"
edition = "2024"
# O tauri-plugin-opener foi removido. Esta mencao e um comentario e nao
# pode reprovar: autoridade comentada nao e autoridade.
[dependencies]
tauri = { version = "2", features = [] }
serde = { version = "1", features = ["derive"] }
C

    cat > "$tmp/src-tauri/src/lib.rs" <<'R'
pub fn run() {
    tauri::Builder::default()
        // .plugin(tauri_plugin_opener::init())   removido
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
R

    write_overlay '"resources": { "resources/analyzer/": "analyzer/" }'

    cat > "$tmp/package.json" <<'P'
{
  "name": "strata",
  "dependencies": { "@tauri-apps/api": "^2.11.1", "react": "^19.2.8" },
  "devDependencies": { "@tauri-apps/cli": "^2.11.4", "@tanstack/router-plugin": "^1.168.35" }
}
P
  }

  expect() {
    local name="$1" want="$2" needle="$3" out rc
    set +e
    out=$(bash "$SELF" --root "$tmp" 2>&1)
    rc=$?
    set -e
    if [ "$want" = ok ] && [ "$rc" -ne 0 ]; then
      failed=$((failed + 1)); printf '  falha  %s: esperada saída 0, obtida %d\n%s\n' "$name" "$rc" "$out"; return
    fi
    if [ "$want" = fail ] && [ "$rc" -eq 0 ]; then
      failed=$((failed + 1)); printf '  falha  %s: esperada saída diferente de 0, obtida 0\n%s\n' "$name" "$out"; return
    fi
    case "$out" in
      *"$needle"*) pass=$((pass + 1)); printf '  ok     %s\n' "$name" ;;
      *) failed=$((failed + 1)); printf '  falha  %s: a saída não citou "%s"\n%s\n' "$name" "$needle" "$out" ;;
    esac
  }

  echo "audit-capabilities --self-test"

  seed; expect "uma árvore saudável passa"                               ok "nenhuma autoridade fora do conjunto declarado"
  seed; expect "a descrição que cita dialog, fs e shell é só texto"      ok "namespaces ........"
  seed; expect "um plugin citado só em comentário do Cargo.toml"         ok "Cargo 0"
  seed; expect "um registro de plugin comentado"                         ok "rs 0"
  seed; expect "@tauri-apps/api e /cli não são pacotes de plugin"        ok "npm 0"

  seed
  printf 'tauri-plugin-dialog = "2"\n' >> "$tmp/src-tauri/Cargo.toml"
  cat > "$tmp/src-tauri/src/lib.rs" <<'R'
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .run(tauri::generate_context!())
        .expect("error");
}
R
  expect "dialog no Cargo e registrado no Rust passa"                    ok "Cargo 1 · rs 1"
  cap_with "$(baseline_body),
    \"dialog:default\"
"
  expect "...e a mesma árvore falha assim que dialog é concedido"      fail "namespace proibido"

  seed; cap_with "$(baseline_body),
    \"fs:default\"
"
  expect "uma permissão fs: simples"                                   fail "namespace proibido"

  seed; cap_with "$(baseline_body),
    { \"identifier\": \"fs:default\", \"allow\": [{ \"path\": \"HOME/**\" }] }
"
  expect "uma permissão fs: em forma de objeto"                        fail "namespace proibido"

  seed; cap_with "$(baseline_body),
    \"shell:allow-execute\"
"
  expect "uma permissão shell:"                                        fail "namespace proibido"

  seed; cap_with "$(baseline_body),
    \"opener:default\"
"
  expect "opener:default, um plugin removido"                          fail "não permite \"opener\""

  seed; cap_with "$(baseline_body),
    \"updater:default\"
"
  expect "um namespace que ninguém pensou em proibir"                  fail "não permite \"updater\""

  seed; cap_with "$(baseline_body),
    \"core:window:allow-close\"
"
  expect "uma permissão core fora da baseline"                         fail "concedidas que este script não declara"

  seed; cap_with '    "core:default"
'
  expect "uma permissão da baseline removida"                          fail "declaradas neste script e não concedidas"

  seed
  printf '{\n  "identifier": "default",\n  "windows": ["main"],\n  "permissions": ["core:default"],\n  "remote": { "urls": ["https://example.com"] }\n}\n' \
    > "$tmp/src-tauri/capabilities/default.json"
  expect "um bloco remote, invisível para um diff de permissões"       fail "remote"

  seed
  printf '{\n  "identifier": "default",\n  "windows": ["*"],\n  "permissions": [\n%s  ]\n}\n' \
    "$(baseline_body)" > "$tmp/src-tauri/capabilities/default.json"
  expect "um glob de janela"                                           fail "é um glob"

  seed
  printf '{\n  "identifier": "default",\n  "windows": ["main"],\n  "permissions": ["core:default"],\n  "permissions": ["fs:default"]\n}\n' \
    > "$tmp/src-tauri/capabilities/default.json"
  expect "uma chave permissions duplicada, em que o serde fica com a última" fail "aparece 2 vezes"

  seed; cap_with '    "\u0066s:default"
'
  expect "um escape unicode que é fs: para o serde e não para o grep"  fail "escape com barra invertida"

  seed
  printf '{\n  // o opener foi removido\n  "identifier": "default",\n  "windows": ["main"],\n  "permissions": ["core:default"]\n}\n' \
    > "$tmp/src-tauri/capabilities/default.json"
  expect "um comentário em arquivo que o serde_json lê como JSON estrito" fail "fora de string"

  seed; cp "$tmp/src-tauri/capabilities/default.json" "$tmp/src-tauri/capabilities/extra.json"
  expect "um segundo arquivo de capability"                            fail "extra.json não está declarado"

  seed; printf 'identifier = "legacy"\n' > "$tmp/src-tauri/capabilities/legacy.toml"
  expect "uma capability .toml, que o Tauri mescla e esta auditoria não lê" fail "legacy.toml não está declarado"

  seed; rm -f "$tmp"/src-tauri/capabilities/*
  expect "uma pasta de capabilities vazia não passa por vacuidade"     fail "por vacuidade"

  seed; printf 'tauri-plugin-opener = "2"\n' >> "$tmp/src-tauri/Cargo.toml"
  expect "edição 2 de 4: a dependência no Cargo volta"                 fail "Cargo.toml depende de tauri-plugin-opener"

  seed; sed 's|// \.plugin|.plugin|' "$tmp/src-tauri/src/lib.rs" > "$tmp/lib.tmp" && mv "$tmp/lib.tmp" "$tmp/src-tauri/src/lib.rs"
  expect "edição 3 de 4: o registro no Rust volta"                     fail "tauri_plugin_opener"

  seed; sed 's|"@tauri-apps/api"|"@tauri-apps/plugin-opener": "^2.5.4", "@tauri-apps/api"|' "$tmp/package.json" > "$tmp/pkg.tmp" && mv "$tmp/pkg.tmp" "$tmp/package.json"
  expect "edição 4 de 4: o pacote npm volta"                           fail "package.json declara @tauri-apps/plugin-opener"

  seed; sed 's|"@tauri-apps/cli"|"@tauri-apps/plugin-opener": "^2.5.4", "@tauri-apps/cli"|' "$tmp/package.json" > "$tmp/pkg.tmp" && mv "$tmp/pkg.tmp" "$tmp/package.json"
  expect "...e devDependencies não serve de esconderijo"               fail "package.json declara @tauri-apps/plugin-opener"

  seed; sed 's|"react": "\^19.2.8"|"opener": "npm:@tauri-apps/plugin-opener@2"|' "$tmp/package.json" > "$tmp/pkg.tmp" && mv "$tmp/pkg.tmp" "$tmp/package.json"
  expect "...nem um alias npm:"                                        fail "package.json declara @tauri-apps/plugin-opener"

  seed; write_conf "$(prod_csp)" "$(dev_csp '')" ""
  expect "uma política de dev sem frame-ancestors"                     fail "devCsp: sem a diretiva \"frame-ancestors\""

  seed; write_conf "$(prod_csp '')" "$(dev_csp)" ""
  expect "uma política de produção sem frame-ancestors"                fail "csp: sem a diretiva \"frame-ancestors\""

  seed
  write_conf "default-src 'self'; connect-src $CSP_CONNECT_PROD https://api.example.com; object-src 'none'; frame-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" "$(dev_csp)" ""
  expect "uma origem a mais no connect-src, que uma busca por substring não pega" fail "connect-src libera https://api.example.com"

  seed; write_conf "$(prod_csp)" "$(dev_csp)" ',\n      "assetProtocol": { "enable": true }'
  expect "assetProtocol lê arquivos sem permissão fs:"                 fail "assetProtocol"

  seed; write_conf "$(prod_csp)" "$(dev_csp)" ',\n      "capabilities": [{ "identifier": "inline", "permissions": ["fs:default"] }]'
  expect "uma capability declarada inline no tauri.conf.json"          fail "inline"

  seed
  printf '{\n  "app": {\n    "withGlobalTauri": true,\n    "security": {\n      "csp": "%s",\n      "devCsp": "%s",\n      "freezePrototype": true\n    }\n  }\n}\n' \
    "$(prod_csp)" "$(dev_csp)" > "$tmp/src-tauri/tauri.conf.json"
  expect "withGlobalTauri entrega a superfície de IPC a qualquer script" fail "withGlobalTauri"

  seed
  printf '{\n  "app": {\n    "security": {\n      "csp": "%s",\n      "devCsp": "%s",\n      "freezePrototype": false\n    }\n  }\n}\n' \
    "$(prod_csp)" "$(dev_csp)" > "$tmp/src-tauri/tauri.conf.json"
  expect "freezePrototype desligado"                                   fail "freezePrototype"

  seed; expect "o overlay de empacotamento pode incluir os recursos do analisador" ok "configurações mescladas"

  seed; write_overlay '"resources": {} }, "app": { "security": { "csp": "default-src *" }'
  expect "um overlay que define a CSP a leva para o pacote sem auditoria" fail "tauri.bundle.conf.json define app"

  seed; write_overlay '"resources": {}, "macOS": { "entitlements": "x.plist" }'
  expect "um overlay que acrescenta entitlements do macOS"             fail "tauri.bundle.conf.json define bundle.macOS"

  seed; printf '{}\n' > "$tmp/src-tauri/tauri.macos.conf.json"
  expect "uma configuração de plataforma que o Tauri mescla sozinho"   fail "tauri.macos.conf.json é mesclado"

  printf '\n%d ok, %d com falha\n' "$pass" "$failed"
  [ "$failed" -eq 0 ]
}

if [ "$SELF_TEST" = 1 ]; then
  self_test
  exit $?
fi

run_audit "$AUDIT_ROOT"
