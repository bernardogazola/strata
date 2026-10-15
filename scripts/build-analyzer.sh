#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ANALYZER_DIR="$REPO_ROOT/analyzer"
STAGE="$REPO_ROOT/src-tauri/resources/analyzer"
MODULES_FILE="$ANALYZER_DIR/runtime-modules.txt"
JAVA_RELEASE=25
MAIN_CLASS="br.pucpr.tcc.strata.analyzer.bootstrap.Main"
SCRATCH=""

usage() {
  cat <<'USAGE'
build-analyzer.sh: roda verify, jdeps e jlink e prepara o analisador para o `pnpm tauri build`

  bash scripts/build-analyzer.sh
  bash scripts/build-analyzer.sh --accept-modules

Saída: 0 analisador preparado · 1 alguma verificação falhou · 2 erro de uso ou de ambiente
USAGE
}

die() { echo "build-analyzer.sh: $1" >&2; exit 2; }
fail() { echo "build-analyzer.sh: $1" >&2; exit 1; }
step() { echo "build-analyzer: $1"; }

cleanup() {
  if [ -n "$SCRATCH" ] && [ -d "$SCRATCH" ]; then rm -rf "$SCRATCH"; fi
}

java_property() {
  "$1" -XshowSettings:properties -version 2>&1 | sed -n "s/^ *$2 = //p" | head -1
}

resolve_jdk() {
  local jdk=""
  if [ -n "${JAVA_HOME:-}" ]; then
    jdk="$JAVA_HOME"
  elif [ "$(uname -s)" = "Darwin" ] && jdk="$(/usr/libexec/java_home -v "$JAVA_RELEASE" 2>/dev/null)"; then
    :
  elif command -v java >/dev/null 2>&1; then
    jdk="$(java_property java java.home)"
  fi
  [ -n "$jdk" ] || die "nenhum JDK encontrado: aponte JAVA_HOME para um JDK $JAVA_RELEASE"
  for tool in java jdeps jlink; do
    [ -x "$jdk/bin/$tool" ] || die "$jdk/bin/$tool não existe; JAVA_HOME tem de ser um JDK completo, não um runtime"
  done
  local version
  version="$(java_property "$jdk/bin/java" java.specification.version)"
  [ "$version" = "$JAVA_RELEASE" ] || die "$jdk é Java $version; o analisador é feito para o Java $JAVA_RELEASE"
  printf '%s\n' "$jdk"
}

analyzer_jar() {
  local jars=() candidate
  for candidate in "$ANALYZER_DIR"/target/analyzer-*.jar; do
    case "$candidate" in
      *-sources.jar | *-javadoc.jar | *-tests.jar) ;;
      *) [ -f "$candidate" ] && jars+=("$candidate") ;;
    esac
  done
  [ "${#jars[@]}" -eq 1 ] || fail "era esperado exatamente um jar do analisador em analyzer/target, e há ${#jars[@]}"
  printf '%s\n' "${jars[0]}"
}

baseline_modules() {
  [ -f "$MODULES_FILE" ] || return 0
  grep -v '^#' "$MODULES_FILE" | grep -v '^[[:space:]]*$' | head -1
}

accept_modules() {
  local modules="$1" header
  header="$(grep '^#' "$MODULES_FILE" 2>/dev/null || true)"
  { [ -n "$header" ] && printf '%s\n' "$header"; printf '%s\n' "$modules"; } > "$MODULES_FILE.tmp"
  mv "$MODULES_FILE.tmp" "$MODULES_FILE"
}

check_modules() {
  local computed="$1" accept="$2" expected
  expected="$(baseline_modules)"
  if [ "$computed" = "$expected" ]; then
    step "o conjunto de módulos confere com analyzer/runtime-modules.txt: $computed"
    return 0
  fi
  if [ "$accept" = "yes" ]; then
    accept_modules "$computed"
    step "baseline de módulos regravada: ${expected:-<nenhuma>} -> $computed"
    return 0
  fi
  cat >&2 <<MSG
build-analyzer.sh: o conjunto de módulos do runtime mudou
  baseline (analyzer/runtime-modules.txt): ${expected:-<nenhuma>}
  calculado pelo jdeps:                    $computed
O analisador agora usa um recurso de biblioteca que precisa de outro módulo do JDK. Confira se
o novo conjunto é intencional e então rode: bash scripts/build-analyzer.sh --accept-modules
e inclua a baseline no mesmo commit do código que causou a mudança.
MSG
  exit 1
}

self_check() {
  local stdout_file="$SCRATCH/stdout.jsonl" status=0
  "$STAGE/runtime/bin/java" -Xss2m -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp "$STAGE/analyzer.jar:$STAGE/lib/*" "$MAIN_CLASS" "--data-dir=$SCRATCH/data" \
    < /dev/null > "$stdout_file" 2> "$SCRATCH/stderr.log" || status=$?
  [ "$status" -eq 0 ] || fail "o runtime preparado saiu com $status no EOF do stdin (o stderr está na saída acima)"
  grep -q '"event":"protocol.ready"' "$stdout_file" \
    || fail "o runtime preparado não imprimiu protocol.ready"
  step "o runtime preparado responde ao handshake"
}

main() {
  local accept="no"
  case "${1:-}" in
    "") ;;
    --accept-modules) accept="yes" ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) die "no Windows, use scripts/build-analyzer.ps1" ;;
  esac

  local jdk jar modules
  jdk="$(resolve_jdk)"
  step "JDK $(java_property "$jdk/bin/java" java.runtime.version) em $jdk"

  step "mvnw verify"
  (cd "$ANALYZER_DIR" && JAVA_HOME="$jdk" ./mvnw -q verify)
  jar="$(analyzer_jar)"

  modules="$("$jdk/bin/jdeps" --print-module-deps --ignore-missing-deps \
    --multi-release "$JAVA_RELEASE" --class-path "$ANALYZER_DIR/target/lib/*" "$jar")"
  check_modules "$modules" "$accept"

  case "$STAGE" in
    */src-tauri/resources/analyzer) rm -rf "$STAGE" ;;
    *) die "recusando limpar um caminho de staging inesperado: $STAGE" ;;
  esac
  mkdir -p "$STAGE/lib"

  step "jlink --add-modules $modules"
  "$jdk/bin/jlink" --add-modules "$modules" --strip-debug --no-header-files \
    --no-man-pages --compress=zip-6 --output "$STAGE/runtime"
  cp "$jar" "$STAGE/analyzer.jar"
  cp "$ANALYZER_DIR"/target/lib/*.jar "$STAGE/lib/"

  SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/build-analyzer.XXXXXX")"
  trap cleanup EXIT
  self_check

  step "preparado: $(du -sh "$STAGE" | cut -f1) em src-tauri/resources/analyzer ($(find "$STAGE/lib" -name '*.jar' | wc -l | tr -d ' ') jars)"
}

main "$@"
