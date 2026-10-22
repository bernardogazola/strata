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
core:window:allow-start-dragging
core:window:allow-minimize
core:window:allow-toggle-maximize
core:window:allow-close
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

DENIED_ENV="TAURI_CONFIG TAURI_APP_PATH TAURI_FRONTEND_PATH"

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
  workflows               .github/workflows, .github/actions, scripts/, .cargo/config:
                          tauri --config só com o overlay, nenhuma expansão depois de uma
                          chamada ao tauri, sem TAURI_CONFIG, sem tauri-action, sem um
                          segundo projeto Tauri

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
function tauriconfig(name, cmd,   ns, seg, si, nt, t, ti, v) {
  ns = split(cmd, seg, /&&|\|\||;|\|/)
  for (si = 1; si <= ns; si++) {
    nt = split(seg[si], t, /[ \t]+/)
    v = 0
    for (ti = 1; ti <= nt; ti++) if (t[ti] == "tauri") v = 1
    if (!v) continue
    for (ti = 1; ti <= nt; ti++) {
      v = ""
      if (t[ti] == "--config" || t[ti] == "-c") v = t[ti + 1]
      else if (index(t[ti], "--config=") == 1) v = substr(t[ti], 10)
      else if (t[ti] ~ /^-c./) v = substr(t[ti], 3)
      else continue
      if (v != OVERLAY_REL)
        bad("surface", "o script npm \"" name "\" mescla \"" v "\" na configuração do Tauri. Só o " OVERLAY_REL " é auditado; qualquer outra coisa entra no pacote sem auditoria")
    }
  }
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

function envline(key, where, s, tag,   u, i) {
  u = toupper(s)
  for (i = 1; i <= nenv; i++) {
    if (((key, denyenv[i]) in envhit) || index(u, denyenv[i]) == 0) continue
    envhit[key, denyenv[i]] = 1
    bad(tag, where " cita " denyenv[i] ", que " (denyenv[i] == "TAURI_CONFIG" ? "mescla configuração que esta auditoria nunca lê" : "aponta a CLI do Tauri para um projeto que esta auditoria nunca lê"))
  }
}

function rel(f) { return (index(f, ROOTDIR "/") == 1 ? substr(f, length(ROOTDIR) + 2) : f) }

function untok(t) {
  while (t != "" && index("'\"`([,", substr(t, 1, 1)) > 0) t = substr(t, 2)
  while (t != "" && index("'\"`)],;", substr(t, length(t), 1)) > 0) t = substr(t, 1, length(t) - 1)
  return t
}
function argv(seg, t, whole,   nw, w, i, u, np, pc, j, p, n, first) {
  split("", t); split("", whole)
  nw = split(seg, w, /[ \t]+/)
  for (i = 1; i <= nw; i++) {
    u = untok(w[i])
    np = split(u, pc, /[][(),]/)
    if (np < 2) { t[++n] = u; continue }
    first = n + 1
    for (j = 1; j <= np; j++) if ((p = untok(pc[j])) != "") t[++n] = p
    if (n >= first) whole[first] = u
  }
  return n + 0
}
function cfgat(u,   p) {
  if (u == "--config") return 1
  if (index(u, "--config=") == 1) return 10
  if (u !~ /^-[A-Za-z]/ || (p = index(u, "c")) == 0) return 0
  return (p < length(u) ? p + 1 : 1)
}
function cmdname(t,   b) {
  b = tolower(t)
  sub(/^.*[\/\\]/, "", b)
  sub(/@.*$/, "", b)
  sub(/\.(exe|cmd|bat|ps1|js|cjs|mjs)$/, "", b)
  return b
}
function findcall(t, nt,   k, u, c, pm) {
  for (k = 1; k <= nt; k++) {
    u = t[k]; c = cmdname(u)
    if (c == "tauri" || c == "cargo-tauri" || index(tolower(u), "@tauri-apps/cli") == 1) return k
    if (pm && (u in tscript)) return k
    if (c ~ /^(pnpm|npm|yarn|bun)$/) pm = 1
  }
  return 0
}
function reaches(cmd,   ns, seg, si, nt, t, wh) {
  ns = split(cmd, seg, SHSEP)
  for (si = 1; si <= ns; si++) {
    nt = argv(seg[si], t, wh)
    if (findcall(t, nt) > 0) return 1
  }
  return 0
}
function checkcall(where, line, seps,   ns, seg, si, nt, t, wh, ci, k, u, v, at, xp) {
  ns = split(line, seg, seps)
  for (si = 1; si <= ns; si++) {
    nt = argv(seg[si], t, wh)
    ci = findcall(t, nt)
    if (ci == 0) continue
    xp = 0
    for (k = ci + 1; k <= nt; k++) {
      u = t[k]
      if (!xp && (index(u, "$") > 0 || u ~ /%[A-Za-z_][A-Za-z0-9_]*%/)) {
        xp = 1
        bad("workflow", where ": a CLI do Tauri recebe \"" u "\". O --config pode viajar dentro de uma expansão, e esta auditoria lê texto, não valores: escreva os argumentos literalmente, um passo por sistema operacional")
      }
      if ((at = cfgat(u)) == 0) continue
      if (at == 1) v = (((k + 1) in wh) ? wh[k + 1] : t[k + 1])
      else v = substr(((k in wh) ? wh[k] : u), at)
      if (v != OVERLAY_REL)
        bad("workflow", where " mescla \"" v "\" na configuração do Tauri. Só o " OVERLAY_REL " é auditado; qualquer outra coisa entra no pacote sem auditoria")
    }
  }
}
function checkuses(where, line,   v) {
  if (!match(line, /(^|[ \t{,])uses[ \t]*:/)) return
  v = trim(substr(line, RSTART + RLENGTH)); sub(/[ \t].*$/, "", v); v = untok(v)
  if (substr(v, 1, 1) != ".") return
  if (index(v, "..") == 0 && (index(v, "./.github/actions/") == 1 || index(v, "./.github/workflows/") == 1)) return
  bad("workflow", where " roda a action local \"" v "\". Esta auditoria só lê .github/actions/ e .github/workflows/; uma action em outro lugar pode rodar tauri build com qualquer configuração")
}

function iscomment(s, cm) { sub(/^[ \t]+/, "", s); return (index(s, cm) == 1) }
function contjoin(src, out, outn, cm,   n, L, i, s, cur, curn, nout, open) {
  n = split(src, L, "\n")
  for (i = 1; i <= n; i++) {
    s = L[i]; sub(/\r$/, "", s)
    if (!open && cm != "" && iscomment(s, cm)) continue
    if (open) cur = cur " " s
    else { cur = s; curn = i }
    open = (cur ~ /[\\`^][ \t]*$/)
    if (open) sub(/[\\`^][ \t]*$/, "", cur)
    else { out[++nout] = cur; outn[nout] = curn }
  }
  if (open) { out[++nout] = cur; outn[nout] = curn }
  return nout + 0
}
function yamlkey(c) {
  return (c ~ /^[A-Za-z0-9_.$\/-]+[ \t]*:([ \t]|$)/ || c ~ /^"[^"]*"[ \t]*:([ \t]|$)/ || c ~ /^'[^']*'[ \t]*:([ \t]|$)/)
}
function yamlnode(c) { return (c ~ /^-([ \t]|$)/ || yamlkey(c)) }
function litjoin(cur, c) {
  if (cur !~ /[\\`^][ \t]*$/) return cur " ; " c
  sub(/[\\`^][ \t]*$/, "", cur)
  return cur " " c
}
function yamljoin(src, out, outn,   n, L, i, s, c, c0, ind, col, v, cur, curn, own, mode, nout) {
  n = split(src, L, "\n"); own = -1
  for (i = 1; i <= n; i++) {
    s = L[i]; sub(/\r$/, "", s)
    c = s; sub(/^[ \t]+/, "", c); ind = length(s) - length(c)
    if (c == "") continue
    if (own >= 0 && ind > own && (mode != "open" || !yamlnode(c))) {
      cur = (mode == "lit" ? litjoin(cur, c) : cur " " c)
      if (mode == "open") mode = "plain"
      continue
    }
    if (substr(c, 1, 1) == "#") continue
    if (cur != "") { out[++nout] = cur; outn[nout] = curn }
    cur = c; curn = i; col = ind; own = ind; mode = "open"
    while (c ~ /^-([ \t]|$)/) { c0 = c; sub(/^-[ \t]*/, "", c); col += length(c0) - length(c) }
    if (yamlkey(c)) {
      own = col
      v = c; sub(/^("[^"]*"|'[^']*'|[^:]*)[ \t]*:[ \t]*/, "", v)
    } else if (col > ind) v = c
    else continue
    if (substr(v, 1, 1) == "|") mode = "lit"
    else if (substr(v, 1, 1) == ">") mode = "fold"
    else if (v != "" && v !~ /^#/ && v !~ /^[&!][^ \t]*([ \t]+#.*)?$/) mode = "plain"
  }
  if (cur != "") { out[++nout] = cur; outn[nout] = curn }
  return nout + 0
}

function jsline(src,   n, L, i, r, sep) {
  n = split(src, L, "\n")
  for (i = 1; i <= n; i++) {
    if (iscomment(L[i], "//")) continue
    r = r sep L[i]; sep = " "
  }
  return r
}

function checkpipe(f, kind,   n, L, i, ll, lln, nl, gh, lf, where) {
  gh = (kind == "workflow" || kind == "action")
  n = split(jsrc[f], L, "\n")
  for (i = 1; i <= n; i++) {
    envline(f, rel(f) ":" i, L[i], "workflow")
    if (gh && !(f in tahit) && index(tolower(L[i]), "/tauri-action") > 0) {
      tahit[f] = 1
      bad("workflow", rel(f) ":" i " usa tauri-action. Os inputs args e projectPath dela mesclam configuração sem que nenhuma linha diga tauri, seja o que for que digam hoje: empacote com pnpm package")
    }
  }
  if (kind == "cargo") return
  split("", ll); split("", lln)
  lf = tolower(f)
  if (lf ~ /\.[cm]?[jt]s$/) {
    checkcall(rel(f), jsline(jsrc[f]), ";")
    return
  }
  if (lf ~ /\.ya?ml$/) nl = yamljoin(jsrc[f], ll, lln)
  else nl = contjoin(jsrc[f], ll, lln, (lf ~ /\.(sh|ps1)$/ ? "#" : ""))
  for (i = 1; i <= nl; i++) {
    where = rel(f) ":" lln[i]
    checkcall(where, ll[i], SHSEP)
    if (gh) checkuses(where, ll[i])
  }
}

BEGIN {
  split(" $schema build build.beforeBuildCommand bundle bundle.resources", ovk, " ")
  for (oi in ovk) overlay_ok[ovk[oi]] = 1
  overlay_ok[""] = 1
  nbase  = toset(BASE_PERMS, want_perm)
  ncapb  = toset(BASE_CAPFILES, want_capfile)
  toset(DENY_NS, deny_ns)
  toset(CONNECT_PROD, want_conn_prod)
  toset(CONNECT_DEV,  want_conn_dev)
  nenv  = split(DENY_ENV, denyenv, " ")
  SHSEP = "&&|[|][|]|;|[|]|&"

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
    if (index(f, CAPDIR "/") == 1)      capf[f] = 1
    else if (index(f, WFDIR "/") == 1)  pipe[f] = "workflow"
    else if (index(f, ACTDIR "/") == 1) pipe[f] = "action"
    else if (index(f, SCRDIR "/") == 1) pipe[f] = "script"
    else if (f ~ /\/\.cargo\/config(\.toml)?$/) pipe[f] = "cargo"
    else if (f ~ /\.rs$/)               rsf[f]  = 1
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
  envline(PKG, "package.json", jsrc[PKG], "surface")
  for (kk in jn) {
    split(kk, ka, SUBSEP)
    if (ka[1] != PKG || index(ka[2], "scripts.") != 1) continue
    for (i = 1; i <= jn[kk]; i++) tauriconfig(substr(ka[2], 9), jval[PKG, ka[2], i])
  }
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
  for (ri = 1; ri <= nrsread; ri++) {
    nl = split(jsrc[rsl[ri]], cl, "\n")
    for (i = 1; i <= nl; i++) envline(rsl[ri], rel(rsl[ri]) ":" i, cl[i], "surface")
  }

  for (f in pipe) npread[pipe[f]]++
  split("workflow action script cargo", kinds, " ")
  split(N_WF " " N_ACT " " N_SCR " " N_CARGO, kfound, " ")
  for (i = 1; i <= 4; i++)
    if (npread[kinds[i]] + 0 != kfound[i] + 0)
      bad("workflow", "foram lidos " (npread[kinds[i]] + 0) " dos " (kfound[i] + 0) " arquivo(s) de " kinds[i] " encontrados; o resto passaria em toda verificação por vacuidade")
  if (WF_EXISTS == 1 && npread["workflow"] + 0 == 0)
    bad("workflow", ".github/workflows existe e nenhum workflow foi lido dela, então toda verificação de workflow passaria por vacuidade")

  do {
    grew = 0
    for (kk in jn) {
      split(kk, ka, SUBSEP)
      if (ka[1] != PKG || index(ka[2], "scripts.") != 1 || (substr(ka[2], 9) in tscript)) continue
      for (i = 1; i <= jn[kk]; i++)
        if (reaches(jval[PKG, ka[2], i])) { tscript[substr(ka[2], 9)] = 1; grew = 1 }
    }
  } while (grew)

  npipe = sortlist(pipe, pipel)
  for (i = 1; i <= npipe; i++) checkpipe(pipel[i], pipe[pipel[i]])

  nsa = split(SECOND_APP, saa, "|")
  for (i = 1; i <= nsa; i++)
    if (saa[i] != "")
      bad("workflow", saa[i] " é uma configuração do Tauri fora do par auditado. Um passo que faça cd para lá, ou que a cite em TAURI_APP_PATH, compila ou mescla essa configuração no lugar de src-tauri/tauri.conf.json")

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
  nwfr = npread["workflow"] + 0; noth = npread["action"] + npread["script"] + npread["cargo"]
  printf "  %s  workflows ................ %d workflow%s, %d outro%s arquivo%s: --config só com o overlay, sem TAURI_CONFIG, sem tauri-action\n",
         st("workflow"), nwfr, (nwfr == 1 ? "" : "s"), noth, (noth == 1 ? "" : "s"), (noth == 1 ? "" : "s")

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
  done < <(find "$srcdir" -maxdepth 1 \( -type f -o -type l \) \( -iname 'tauri*.conf.json' -o -iname 'tauri*.conf.json5' -o -iname 'tauri*.toml' \) -exec basename {} \; | LC_ALL=C sort)

  capfound=""
  while IFS= read -r f; do
    rel=${f#"$capdir"/}
    capfound="$capfound $rel"
  done < <(find "$capdir" \( -type f -o -type l \) | LC_ALL=C sort)

  local files=()
  while IFS= read -r f; do files+=("$f"); done \
    < <(find "$capdir" \( -type f -o -type l \) -name '*.json' | LC_ALL=C sort)
  files+=("$conf" "$cargo" "$pkg")
  if [ -n "$overlay" ]; then files+=("$overlay"); fi
  while IFS= read -r f; do files+=("$f"); done \
    < <(find "$srcdir" \( -name target -o -name gen \) -prune -o -type f -name '*.rs' -print | LC_ALL=C sort)

  local wfdir="$root/.github/workflows" actdir="$root/.github/actions" scrdir="$root/scripts"
  local wfexists=0 nwf=0 nact=0 nscr=0 ncargo=0 second=""
  if [ -d "$wfdir" ]; then
    wfexists=1
    while IFS= read -r f; do files+=("$f"); nwf=$((nwf + 1)); done \
      < <(find -L "$wfdir" -type f \( -iname '*.yml' -o -iname '*.yaml' \) | LC_ALL=C sort)
  fi
  if [ -d "$actdir" ]; then
    while IFS= read -r f; do
      if [ -s "$f" ]; then files+=("$f"); nact=$((nact + 1)); fi
    done < <(find -L "$actdir" -type f | LC_ALL=C sort)
  fi
  if [ -d "$scrdir" ]; then
    while IFS= read -r f; do
      if [ "$f" != "$scrdir/audit-capabilities.sh" ] && [ -s "$f" ]; then files+=("$f"); nscr=$((nscr + 1)); fi
    done < <(find -L "$scrdir" -type f | LC_ALL=C sort)
  fi
  for f in "$root/.cargo/config" "$root/.cargo/config.toml" "$srcdir/.cargo/config" "$srcdir/.cargo/config.toml"; do
    if [ -s "$f" ]; then files+=("$f"); ncargo=$((ncargo + 1)); fi
  done
  while IFS= read -r f; do
    case "$f" in
      "$srcdir"/*/*) second="$second|${f#"$root"/}" ;;
      "$srcdir"/*)   ;;
      *)             second="$second|${f#"$root"/}" ;;
    esac
  done < <(find "$root" \( -name node_modules -o -name .git -o -name target \) -prune -o \
             \( -type f -o -type l \) \( -iname 'tauri*.conf.json' -o -iname 'tauri*.conf.json5' -o -iname 'tauri*.toml' \) \
             -print | LC_ALL=C sort)

  local base_perms base_capfiles deny_ns plugins
  base_perms=$(printf '%s' "$BASELINE_PERMISSIONS" | tr '\n' ' ')
  base_capfiles=$(printf '%s' "$BASELINE_CAPABILITY_FILES" | tr '\n' ' ')
  deny_ns=$(printf '%s' "$DENIED_NAMESPACES" | tr '\n' ' ')
  plugins=$(printf '%s' "$PLUGIN_TABLE" | tr '\n' ';')

  awk -v ROOTDIR="$root" -v CAPDIR="$capdir" -v CONF="$conf" -v CARGO="$cargo" -v PKG="$pkg" \
      -v CAPFOUND="$capfound" \
      -v OVERLAY="$overlay" -v STRAY_CONF="$stray" -v OVERLAY_REL="src-tauri/tauri.bundle.conf.json" \
      -v WFDIR="$wfdir" -v ACTDIR="$actdir" -v SCRDIR="$scrdir" -v WF_EXISTS="$wfexists" \
      -v N_WF="$nwf" -v N_ACT="$nact" -v N_SCR="$nscr" -v N_CARGO="$ncargo" \
      -v SECOND_APP="$second" -v DENY_ENV="$DENIED_ENV" \
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

  write_at() {
    mkdir -p "$(dirname "$tmp/$1")"
    printf '%b\n' "$2" > "$tmp/$1"
  }
  local wf_head='name: package\non: push\njobs:\n  package:\n    runs-on: ubuntu-24.04\n    steps:'
  step() { write_at ".github/workflows/${2:-package.yml}" "$wf_head\n$1"; }

  seed() {
    find "$tmp" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
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
  "scripts": { "package": "pnpm build:analyzer && tauri build --config src-tauri/tauri.bundle.conf.json" },
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
    \"core:window:allow-set-title\"
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

  seed; printf '{}\n' > "$tmp/elsewhere.json"; ln -s ../elsewhere.json "$tmp/src-tauri/tauri.macos.conf.json"
  expect "...mesmo quando é um symlink, que o Tauri segue"             fail "tauri.macos.conf.json é mesclado"

  seed; sed 's|tauri.bundle.conf.json"|tauri.bundle.conf.json --config src-tauri/release.json"|' "$tmp/package.json" > "$tmp/pkg.tmp" && mv "$tmp/pkg.tmp" "$tmp/package.json"
  expect "um segundo --config em um script npm"                        fail "mescla \"src-tauri/release.json\""

  seed; sed 's|--config src-tauri/tauri.bundle.conf.json|-c src-tauri/other.json|' "$tmp/package.json" > "$tmp/pkg.tmp" && mv "$tmp/pkg.tmp" "$tmp/package.json"
  expect "...ou um -c apontando para outro lugar"                      fail "mescla \"src-tauri/other.json\""

  seed; sed 's|"pnpm build:analyzer|"TAURI_CONFIG={} pnpm build:analyzer|' "$tmp/package.json" > "$tmp/pkg.tmp" && mv "$tmp/pkg.tmp" "$tmp/package.json"
  expect "TAURI_CONFIG em um script npm"                               fail "TAURI_CONFIG"

  seed
  if mkdir -p "$tmp/.github/workflows" && cp "$ROOT/.github/workflows/security.yml" "$tmp/.github/workflows/"; then
    expect "o próprio security.yml deste repositório"                  ok "1 workflow, 0 outros arquivos"
  else
    failed=$((failed + 1)); printf '  falha  o próprio security.yml deste repositório: não foi possível copiá-lo de %s\n' "$ROOT"
  fi

  seed; step '      - if: runner.os == "Linux"\n        run: pnpm package --bundles deb\n      - if: runner.os == "macOS"\n        run: pnpm package --bundles app\n      - if: runner.os == "macOS"\n        run: pnpm package --bundles app,dmg\n      - if: runner.os == "Windows"\n        run: pnpm package --bundles nsis'
  expect "um workflow que empacota com pnpm package, uma linha literal por sistema operacional" ok "nenhuma autoridade fora do conjunto declarado"

  seed; step '      - run: pnpm tauri build --config src-tauri/tauri.bundle.conf.json'
  expect "...ou que cita o próprio overlay"                            ok "nenhuma autoridade fora do conjunto declarado"

  seed; step '      - run: |\n          pnpm package --bundles deb\n          git -c core.autocrlf=false checkout -- .\n          git config --global core.autocrlf false\n          bash -c true\n          xvfb-run -a bash scripts/smoke-packaged.sh --ci\n          cargo test --manifest-path src-tauri/Cargo.toml -- --ignored\n          cargo clippy --manifest-path src-tauri/Cargo.toml -- -D warnings'
  expect "o -c do git e do bash, uma linha depois do pnpm package, não é do tauri" ok "nenhuma autoridade fora do conjunto declarado"

  seed; step '      - run: pnpm tauri build --config src-tauri/other.json'
  expect "um workflow que mescla uma segunda configuração pelo script tauri" fail "package.yml:7 mescla \"src-tauri/other.json\""

  seed; step '      - run: pnpm package -- --config src-tauri/other.json'
  expect "...ou que acrescenta uma ao pnpm package"                    fail "mescla \"src-tauri/other.json\""

  seed; step '      - run: npx tauri build -c src-tauri/other.json'
  expect "...ou pelo npx, com -c"                                      fail "mescla \"src-tauri/other.json\""

  seed; step '      - run: cargo tauri build --config=src-tauri/other.json'
  expect "...ou pelo cargo tauri, com --config="                       fail "mescla \"src-tauri/other.json\""

  seed; step '      - run: pnpm tauri build -vc src-tauri/other.json'
  expect "...ou com -c agrupado atrás de outra flag, como o clap permite" fail "mescla \"src-tauri/other.json\""

  seed; sed 's|"scripts": { |"scripts": { "release": "pnpm package", |' "$tmp/package.json" > "$tmp/pkg.tmp" && mv "$tmp/pkg.tmp" "$tmp/package.json"
  step '      - run: pnpm release --config src-tauri/other.json'
  expect "...ou por um script que só chega ao tauri por meio de outro" fail "mescla \"src-tauri/other.json\""

  seed; step '      - run: |\n          pnpm tauri build \\\n            --config src-tauri/other.json'
  expect "...mesmo quebrado em uma linha de continuação"               fail "package.yml:7 mescla \"src-tauri/other.json\""

  seed; step '      - run: pnpm tauri build\n          --config src-tauri/other.json'
  expect "...ou dobrado pelo próprio YAML, sem nenhuma barra invertida" fail "package.yml:7 mescla \"src-tauri/other.json\""

  seed; step "      - run: pnpm tauri build --config '{\"app\":{}}'"
  expect "...ou JSON inline, que nenhuma verificação de arquivo enxerga" fail "mescla \"{"

  seed; step '      - run: pnpm package --bundles ${{ matrix.bundles }}'
  expect "...ou uma expansão, cujo valor esta auditoria não consegue ler" fail "viajar dentro de uma expansão"

  seed; write_at .github/workflows/package.yml 'on: push\nenv:\n  TAURI_CONFIG: "{}"\njobs: {}'
  expect "TAURI_CONFIG em um bloco env de workflow"                    fail "package.yml:3 cita TAURI_CONFIG"

  seed; step '      - run: pnpm package --bundles deb\n        env:\n          TAURI_APP_PATH: ci'
  expect "TAURI_APP_PATH, que aponta a CLI para outro projeto"         fail "cita TAURI_APP_PATH"

  seed; step '      - uses: tauri-apps/tauri-action@0123456789abcdef0123456789abcdef01234567'
  expect "tauri-action, digam o que disserem os inputs"                fail "usa tauri-action"

  seed; step '      - run: pnpm tauri build -c src-tauri/other.json' package.yaml
  expect "...e .yaml não serve de esconderijo"                         fail "package.yaml:7 mescla"

  seed; write_at .github/actions/build/action.yml 'runs:\n  using: composite\n  steps:\n    - run: pnpm tauri build -c src-tauri/other.json\n      shell: bash'
  expect "uma action composite"                                        fail "action.yml:4 mescla"

  seed; write_at scripts/release.sh 'set -euo pipefail\npnpm tauri build --config src-tauri/other.json'
  expect "um script que um workflow roda"                              fail "scripts/release.sh:2 mescla"

  seed; write_at scripts/release.ps1 "Start-Process pnpm -ArgumentList 'tauri','build','--config','src-tauri/other.json'"
  expect "uma lista de argumentos do PowerShell unida por vírgulas"    fail "scripts/release.ps1:1 mescla \"src-tauri/other.json\""

  seed; write_at scripts/release.mjs 'spawnSync("pnpm",["tauri","build","--config","src-tauri/other.json"]);'
  expect "...ou um array JS sem espaços"                               fail "scripts/release.mjs mescla \"src-tauri/other.json\""

  seed; write_at scripts/release.ps1 "Start-Process pnpm -ArgumentList 'tauri','build','--config','src-tauri/tauri.bundle.conf.json'"
  expect "uma lista unida por vírgulas que cita o próprio overlay"     ok "nenhuma autoridade fora do conjunto declarado"

  seed; write_at scripts/release.sh 'pnpm package --bundles app,dmg'
  expect "uma vírgula dentro de um valor de --bundles não é configuração" ok "nenhuma autoridade fora do conjunto declarado"

  seed; step '      - run: pnpm tauri build --config src-tauri/tauri.bundle.conf.json,src-tauri/other.json'
  expect "...mas dentro de um valor de --config é um caminho só, e não o overlay" fail "mescla \"src-tauri/tauri.bundle.conf.json,src-tauri/other.json\""

  seed; write_at scripts/release.mjs 'spawnSync("pnpm",["package","--config","src-tauri/other.json"]);'
  expect "uma chamada JS que envolve o gerenciador de pacotes"         fail "scripts/release.mjs mescla \"src-tauri/other.json\""

  seed; write_at scripts/release.mjs "execFileSync('npx', ['tauri','build','-c','src-tauri/other.json']);"
  expect "...ou o npx e a CLI"                                         fail "scripts/release.mjs mescla \"src-tauri/other.json\""

  seed; write_at scripts/release.ps1 "Start-Process -FilePath pnpm -ArgumentList @('package','--config','src-tauri/other.json')"
  expect "...ou Start-Process -FilePath com um array de argumentos @()" fail "scripts/release.ps1:1 mescla \"src-tauri/other.json\""

  seed; write_at scripts/release.mjs 'spawnSync("node",["scripts/build-analyzer.mjs"]);'
  expect "uma chamada JS a outro programa não é chamada ao Tauri"      ok "nenhuma autoridade fora do conjunto declarado"

  seed
  if mkdir -p "$tmp/scripts" && cp "$ROOT/scripts/build-analyzer.mjs" "$tmp/scripts/"; then
    expect "o próprio build-analyzer.mjs deste repositório"            ok "0 workflows, 1 outro arquivo"
  else
    failed=$((failed + 1)); printf '  falha  o próprio build-analyzer.mjs deste repositório: não foi possível copiá-lo de %s\n' "$ROOT"
  fi

  local prose='O build é feito pelo `pnpm package` que mapeia o staging para `$RESOURCE/analyzer/`.'
  seed; write_at scripts/build-analyzer.sh "set -euo pipefail\n# $prose"
  expect "texto em um comentário de linha inteira em .sh"              ok "nenhuma autoridade fora do conjunto declarado"

  seed; write_at scripts/build-analyzer.mjs "// $prose\nexport const stage = 1;"
  expect "...ou em um comentário // de linha inteira em .mjs"          ok "nenhuma autoridade fora do conjunto declarado"

  seed; write_at scripts/build-analyzer.sh "set -euo pipefail\n$prose"
  expect "...e o mesmo texto como código ainda falha"                  fail "scripts/build-analyzer.sh:2: a CLI do Tauri recebe"

  seed; write_at scripts/release.sh 'echo pronto # pnpm tauri build --config src-tauri/other.json'
  expect "um comentário depois de código na mesma linha ainda é lido"  fail "scripts/release.sh:1 mescla \"src-tauri/other.json\""

  seed; step '      - run: |\n          # pnpm tauri build --config src-tauri/other.json\n          pnpm package --bundles deb'
  expect "...assim como um comentário dentro de um bloco run do YAML, que é shell" fail "package.yml:7 mescla \"src-tauri/other.json\""

  seed; step '      - uses: ./tools/build'
  expect "uma action local fora de .github/actions, que nada lê" fail "roda a action local \"./tools/build\""

  seed; write_at ci/src-tauri/tauri.conf.json '{}'
  expect "um segundo projeto Tauri em que um passo poderia entrar com cd" fail "ci/src-tauri/tauri.conf.json é uma configuração do Tauri fora"

  seed; write_at .cargo/config.toml '[env]\nTAURI_CONFIG = { value = "{}", force = true }'
  expect "TAURI_CONFIG em .cargo/config.toml, que chega ao tauri-build" fail ".cargo/config.toml:2 cita TAURI_CONFIG"

  seed; write_at src-tauri/build.rs 'fn main() {\n    println!("cargo:rustc-env=TAURI_CONFIG={{}}");\n    tauri_build::build()\n}'
  expect "...ou um build script que o repassa ao generate_context!"    fail "build.rs:2 cita TAURI_CONFIG"

  seed; write_at .github/workflows/README.md 'nada aqui roda'
  expect "uma pasta de workflows da qual o awk não leu nada não é aprovação" fail "por vacuidade"

  printf '\n%d ok, %d com falha\n' "$pass" "$failed"
  [ "$failed" -eq 0 ]
}

if [ "$SELF_TEST" = 1 ]; then
  self_test
  exit $?
fi

run_audit "$AUDIT_ROOT"
