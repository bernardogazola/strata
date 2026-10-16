#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BUNDLE_DIR="$REPO_ROOT/src-tauri/target/release/bundle"
VOLUME_NAME="Strata"
SYSPOLICY_FINDINGS_EXIT=70

PROBLEMS=0
SCRATCH=""
MOUNT=""

usage() {
  cat <<'USAGE'
sign-macos.sh: assina o Strata.app ad hoc de dentro para fora, verifica cada assinatura e monta o .dmg

  bash scripts/sign-macos.sh
  bash scripts/sign-macos.sh --app <caminho/para/Strata.app>
  bash scripts/sign-macos.sh --self-test

Saída: 0 assinado e verificado · 1 alguma verificação falhou · 2 erro de uso ou de ambiente
USAGE
}

bad() { echo "  falha  $1"; PROBLEMS=$((PROBLEMS + 1)); }
good() { echo "  ok     $1"; }
info() { echo "  info   $1"; }
die() { echo "sign-macos.sh: $1" >&2; exit 2; }
step() { echo "sign-macos: $1"; }

cleanup() {
  if [ -n "$MOUNT" ] && [ -d "$MOUNT" ]; then
    /usr/bin/hdiutil detach "$MOUNT" -quiet -force 2>/dev/null || true
  fi
  if [ -n "$SCRATCH" ] && [ -d "$SCRATCH" ]; then rm -rf "$SCRATCH"; fi
}

is_macho() { /usr/bin/file -b "$1" | grep -q '^Mach-O'; }

main_executable() {
  local app="$1" name
  name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Contents/Info.plist" 2>/dev/null)" \
    || die "$app/Contents/Info.plist não tem CFBundleExecutable"
  [ -f "$app/Contents/MacOS/$name" ] || die "o executável principal $name não está em $app/Contents/MacOS"
  printf '%s\n' "$app/Contents/MacOS/$name"
}

nested_macho() {
  local app="$1" main_exe="$2" path
  find "$app/Contents" -type f -print | while IFS= read -r path; do
    [ "$path" = "$main_exe" ] && continue
    if is_macho "$path"; then
      printf '%s\t%s\n' "$(printf '%s' "$path" | tr -cd '/' | wc -c | tr -d ' ')" "$path"
    fi
  done | sort -t "$(printf '\t')" -k1,1nr -k2,2 | cut -f2-
}

has_valid_signature() { /usr/bin/codesign --verify --strict "$1" >/dev/null 2>&1; }

describe() { /usr/bin/codesign -dv --verbose=2 "$1" 2>&1 || true; }

authority_of() {
  describe "$1" | sed -n 's/^Authority=//p' | head -1
}

sign_adhoc() {
  /usr/bin/codesign --force --sign - --timestamp=none "$1" 2>&1 \
    | grep -v ': replacing existing signature$' || true
}

sign_inside_out() {
  local app="$1" main_exe path kept=0 signed=0 authority=""
  main_exe="$(main_executable "$app")"
  while IFS= read -r path; do
    [ -n "$path" ] || continue
    case "$path" in
      "$app/Contents/MacOS/"*)
        sign_adhoc "$path"
        signed=$((signed + 1))
        ;;
      *)
        if has_valid_signature "$path"; then
          kept=$((kept + 1))
          [ -n "$authority" ] || authority="$(authority_of "$path")"
        else
          sign_adhoc "$path"
          signed=$((signed + 1))
        fi
        ;;
    esac
  done <<<"$(nested_macho "$app" "$main_exe")"
  step "mantidas $kept assinatura(s) aninhada(s)${authority:+ ($authority)}; assinados ad hoc $signed Mach-O aninhado(s)"
  sign_adhoc "$app"
  step "bundle selado ad hoc"
}

verify_signed() {
  local app="$1" main_exe described path nested=0
  main_exe="$(main_executable "$app")"

  if /usr/bin/codesign --verify --deep --strict "$app" >/dev/null 2>&1; then
    good "codesign --verify --deep --strict aceita o bundle"
  else
    bad "codesign --verify --deep --strict rejeita o bundle: $(/usr/bin/codesign --verify --deep --strict "$app" 2>&1 | tail -1)"
  fi

  described="$(describe "$app")"
  case "$described" in
    *"Signature=adhoc"*) good "a assinatura do bundle é ad hoc" ;;
    *) bad "o bundle não tem assinatura ad hoc: $(printf '%s\n' "$described" | grep -m1 '^Authority=' || echo 'sem assinatura')" ;;
  esac
  case "$described" in
    *"Sealed Resources version=2"*) good "o bundle sela os próprios recursos" ;;
    *) bad "o bundle não sela nenhum recurso (uma assinatura do linker só cobre o executável)" ;;
  esac
  case "$described" in
    *"Info.plist=not bound"*) bad "o Info.plist não está vinculado à assinatura" ;;
    *) good "o Info.plist está vinculado à assinatura" ;;
  esac
  case "$described" in
    *linker-signed*) bad "o executável principal ainda tem só a assinatura do linker" ;;
    *) good "o executável principal é assinado como parte do bundle" ;;
  esac

  while IFS= read -r path; do
    [ -n "$path" ] || continue
    nested=$((nested + 1))
    has_valid_signature "$path" || bad "${path#"$app"/} não tem assinatura válida (o kernel o mata no exec)"
  done <<<"$(nested_macho "$app" "$main_exe")"
  if [ "$nested" -gt 0 ]; then
    good "cada um dos $nested arquivo(s) Mach-O aninhado(s) tem assinatura válida"
  else
    bad "nenhum Mach-O aninhado encontrado: falta a JVM empacotada, ou a varredura não leu nada"
  fi
}

check_gatekeeper() {
  local app="$1" report status=0 verdict
  verdict="$(/usr/sbin/spctl --assess --type execute -vv "$app" 2>&1 || true)"
  info "spctl --assess --type execute: $(printf '%s' "$verdict" | tr '\n' ' ')"
  if [ -x /usr/bin/syspolicy_check ]; then
    report="$(/usr/bin/syspolicy_check distribution "$app" 2>&1)" || status=$?
    if [ "$status" -eq "$SYSPOLICY_FINDINGS_EXIT" ] \
      && printf '%s\n' "$report" | grep -q '^Adhoc Signed App' \
      && printf '%s\n' "$report" | grep -q '^Notary Ticket Missing' \
      && ! printf '%s\n' "$report" | grep -q '^Codesign Error'; then
      good "o syspolicy_check só aponta as duas consequências de não notarizar (ad hoc, sem ticket)"
    else
      bad "o syspolicy_check saiu com $status e apontou mais do que ad hoc + sem ticket de notarização"
      printf '%s\n' "$report" | sed 's/^/        /'
    fi
  else
    info "o syspolicy_check não está disponível neste macOS; pulado"
  fi
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### Gatekeeper no app assinado ad hoc"
      echo '```'
      printf '%s\n' "$verdict"
      printf '%s\n' "${report:-syspolicy_check indisponível}"
      echo '```'
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

dmg_name() {
  local app="$1" version arch
  version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist")"
  case "$(/usr/bin/lipo -archs "$(main_executable "$app")")" in
    arm64) arch="aarch64" ;;
    x86_64) arch="x64" ;;
    *) arch="universal" ;;
  esac
  printf '%s_%s_%s.dmg\n' "$VOLUME_NAME" "$version" "$arch"
}

build_dmg() {
  local app="$1" out_dir="$2" out stage attempt
  out="$out_dir/$(dmg_name "$app")"
  stage="$SCRATCH/dmg-root"
  mkdir -p "$stage" "$out_dir"
  /usr/bin/ditto "$app" "$stage/$(basename "$app")"
  ln -s /Applications "$stage/Applications"

  find "$out_dir" -maxdepth 1 -type f -name "${VOLUME_NAME}_*.dmg" -delete

  for attempt in 1 2; do
    if /usr/bin/hdiutil create -quiet -volname "$VOLUME_NAME" -srcfolder "$stage" -ov -format UDZO "$out"; then
      break
    fi
    [ "$attempt" -eq 2 ] && die "o hdiutil create falhou duas vezes"
    echo "sign-macos: o hdiutil create falhou, tentando mais uma vez" >&2
    sleep 5
  done
  printf '%s\n' "$out"
}

verify_dmg() {
  local dmg="$1" copy
  MOUNT="$SCRATCH/mnt"
  mkdir -p "$MOUNT"
  if ! /usr/bin/hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$MOUNT" "$dmg" >/dev/null 2>"$SCRATCH/attach.err"; then
    bad "o hdiutil não conseguiu montar $(basename "$dmg"): $(tail -1 "$SCRATCH/attach.err")"
    MOUNT=""
    return 0
  fi
  copy="$MOUNT/$VOLUME_NAME.app"
  if /usr/bin/codesign --verify --deep --strict "$copy" >/dev/null 2>&1; then
    good "o app dentro de $(basename "$dmg") continua passando na verificação"
  else
    bad "o app dentro de $(basename "$dmg") não passa mais na verificação: $(/usr/bin/codesign --verify --deep --strict "$copy" 2>&1 | tail -1)"
  fi
  detach_volume "$MOUNT"
  MOUNT=""
}

detach_volume() {
  /usr/bin/hdiutil detach "$1" -quiet 2>/dev/null && return 0
  sleep 2
  /usr/bin/hdiutil detach "$1" -quiet 2>/dev/null && return 0
  /usr/bin/hdiutil detach "$1" -quiet -force
}

synthetic_app() {
  local app="$1" jdk="$2"
  mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources/analyzer/runtime/bin" \
    "$app/Contents/Resources/analyzer/runtime/lib/server"
  cp "$jdk/bin/java" "$app/Contents/MacOS/strata"
  cp "$jdk/bin/java" "$app/Contents/Resources/analyzer/runtime/bin/java"
  cp "$jdk/lib/libjli.dylib" "$app/Contents/Resources/analyzer/runtime/lib/libjli.dylib"
  cp "$jdk/lib/server/libjvm.dylib" "$app/Contents/Resources/analyzer/runtime/lib/server/libjvm.dylib"
  printf 'not a Mach-O\n' > "$app/Contents/Resources/analyzer/analyzer.jar"
  cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>strata</string>
  <key>CFBundleIdentifier</key><string>br.pucpr.tcc.strata.selftest</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.0.0</string>
</dict>
</plist>
PLIST
}

self_test() {
  local jdk app failures=0 got java_rel
  jdk="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
  [ -x "$jdk/bin/java" ] || die "--self-test precisa de um JDK (JAVA_HOME ou /usr/libexec/java_home)"
  SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/sign-macos-self-test.XXXXXX")"
  trap cleanup EXIT
  java_rel="Contents/Resources/analyzer/runtime/bin/java"

  expect() {
    if [ "$2" = "$3" ]; then echo "  ok     $1"; else echo "  falha  $1 (obtido '$2', esperado '$3')"; failures=$((failures + 1)); fi
  }
  fresh() { rm -rf "$SCRATCH/Strata.app"; synthetic_app "$SCRATCH/Strata.app" "$jdk"; app="$SCRATCH/Strata.app"; }
  outcome() { PROBLEMS=0; verify_signed "$app" >/dev/null; if [ "$PROBLEMS" -gt 0 ]; then echo detectado; else echo aceito; fi; }

  fresh
  got="$(nested_macho "$app" "$app/Contents/MacOS/strata" | wc -l | tr -d ' ')"
  expect "a varredura acha os três Mach-O aninhados e não o jar" "$got" "3"
  got="$(nested_macho "$app" "$app/Contents/MacOS/strata" | head -1)"
  case "$got" in */server/libjvm.dylib) got="o mais fundo" ;; esac
  expect "o arquivo mais fundo vem primeiro" "$got" "o mais fundo"

  fresh; sign_inside_out "$app" >/dev/null
  expect "a assinatura de dentro para fora passa na verificação" "$(outcome)" "aceito"
  if has_valid_signature "$jdk/bin/java"; then
    expect "uma JVM aninhada com assinatura válida mantém o signatário" "$(authority_of "$app/$java_rel")" "$(authority_of "$jdk/bin/java")"
  fi

  fresh; /usr/bin/codesign --remove-signature "$app/$java_rel"
  expect "um Mach-O aninhado sem assinatura é detectado antes de assinar" "$(outcome)" "detectado"
  sign_inside_out "$app" >/dev/null
  expect "...e é assinado ad hoc na passada de dentro para fora" "$(outcome)" "aceito"

  fresh
  expect "um bundle não selado é detectado" "$(outcome)" "detectado"

  fresh; /usr/bin/codesign --remove-signature "$app/$java_rel"; sign_adhoc "$app" >/dev/null
  expect "selar de fora para dentro sobre um binário aninhado sem assinatura é detectado" "$(outcome)" "detectado"

  fresh; sign_inside_out "$app" >/dev/null
  printf 'x' >> "$app/Contents/Resources/analyzer/runtime/lib/libjli.dylib"
  expect "um binário aninhado alterado depois de selar é detectado" "$(outcome)" "detectado"

  fresh; sign_inside_out "$app" >/dev/null
  printf 'tampered\n' > "$app/Contents/Resources/analyzer/analyzer.jar"
  expect "um recurso alterado depois de selar é detectado" "$(outcome)" "detectado"

  fresh; rm -rf "$app/Contents/Resources/analyzer/runtime"; sign_inside_out "$app" >/dev/null
  expect "um bundle sem JVM aninhada é detectado" "$(outcome)" "detectado"

  if [ "$failures" -eq 0 ]; then echo "autoteste: ok"; exit 0; fi
  echo "autoteste: $failures verificação(ões) com resultado inesperado"; exit 1
}

main() {
  local app=""
  case "${1:-}" in
    "") ;;
    --app) [ -n "${2:-}" ] || { usage >&2; exit 2; }; app="$2" ;;
    --self-test) [ "$(uname -s)" = "Darwin" ] || die "só no macOS"; self_test ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
  [ "$(uname -s)" = "Darwin" ] || die "só no macOS"

  local out_dir
  if [ -z "$app" ]; then
    local apps=("$BUNDLE_DIR"/macos/*.app)
    [ -e "${apps[0]}" ] || die "nenhum .app em src-tauri/target/release/bundle/macos; rode 'pnpm package' antes"
    [ "${#apps[@]}" -eq 1 ] || die "${#apps[@]} bundles .app em bundle/macos; era esperado exatamente um"
    app="${apps[0]}"
    out_dir="$BUNDLE_DIR/dmg"
  else
    [ -d "$app/Contents" ] || die "$app não é um bundle de app"
    out_dir="$(CDPATH= cd -- "$(dirname -- "$app")" && pwd)/dmg"
  fi

  SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/sign-macos.XXXXXX")"
  trap cleanup EXIT

  step "assinando $(basename "$app") de dentro para fora, ad hoc"
  sign_inside_out "$app"
  verify_signed "$app"
  check_gatekeeper "$app"
  [ "$PROBLEMS" -eq 0 ] || { echo "sign-macos: $PROBLEMS problema(s); nenhum dmg foi montado"; exit 1; }

  local dmg
  dmg="$(build_dmg "$app" "$out_dir")"
  verify_dmg "$dmg"
  [ "$PROBLEMS" -eq 0 ] || { echo "sign-macos: $PROBLEMS problema(s)"; exit 1; }
  step "montado: ${dmg#"$REPO_ROOT"/} ($(stat -f %z "$dmg") bytes)"
  step "sha256 $(/usr/bin/shasum -a 256 "$dmg" | cut -d' ' -f1)"
}

main "$@"
