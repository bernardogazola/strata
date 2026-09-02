#!/usr/bin/env bash

set -euo pipefail

export LC_ALL=C

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SELF=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")
OUT_DIR="$ROOT/fixtures/repositories"
NO_GIT=0
PRINT_IDS=0

FIXTURE_NAME="Strata Fixture"
FIXTURE_EMAIL="fixtures@strata.invalid"
FIXTURE_DATE="2024-01-02T03:04:05+0000"

SIDECARS="expected.json README.md cloc-golden.txt"

FIXTURES="simple-packages inheritance cyclic method-calls missing-classpath broken-source multi-module"

usage() {
  cat <<'USAGE'
uso: make-fixtures.sh [opções]

  --out <pasta>          onde o corpus é gravado (padrão: <repo>/fixtures/repositories)
  --no-git               grava só os fontes; não cria .git aninhado
  --print-ids            mostra "<fixture> <sha>" por linha em vez do relatório
  --self-test            roda o gerador contra árvores sintéticas e sai
  --help                 mostra este texto

saída: 0 ok · 1 alguma verificação falhou · 2 erro de uso
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out)          OUT_DIR="${2:-}"; shift 2 ;;
    --no-git)       NO_GIT=1; shift ;;
    --print-ids)    PRINT_IDS=1; shift ;;
    --self-test)    SELF_TEST=1; shift ;;
    --help|-h)      usage; exit 0 ;;
    *)              echo "make-fixtures: opção desconhecida '$1'" >&2; usage >&2; exit 2 ;;
  esac
done

validate_out_dir() {
  local candidate="$1"
  case "$candidate" in
    ""|/|/*/..*) echo "make-fixtures: recusando --out '$candidate'" >&2; exit 2 ;;
  esac
  if [ "$candidate" = "$HOME" ] || [ "$candidate" = "$ROOT" ] || [ "$candidate" = "/" ]; then
    echo "make-fixtures: recusando gravar o corpus em '$candidate'" >&2
    exit 2
  fi
  mkdir -p -- "$candidate"
}

FIXTURE_DIR=""

emit() {
  local target="$FIXTURE_DIR/$1"
  mkdir -p -- "$(dirname -- "$target")"
  cat > "$target"
}

fixture_reset() {
  local dir="$1" entry name keep
  FIXTURE_DIR="$dir"
  mkdir -p -- "$dir"
  while IFS= read -r entry; do
    name=$(basename "$entry")
    keep=0
    for sidecar in $SIDECARS; do
      [ "$name" = "$sidecar" ] && keep=1
    done
    [ "$keep" = 1 ] || rm -rf -- "$entry"
  done < <(find "$dir" -mindepth 1 -maxdepth 1 | LC_ALL=C sort)
}

fixture_git() {
  local dir="$1"; shift
  env -u EMAIL \
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_CONFIG_NOSYSTEM=1 \
    GIT_AUTHOR_NAME="$FIXTURE_NAME"    GIT_AUTHOR_EMAIL="$FIXTURE_EMAIL" \
    GIT_COMMITTER_NAME="$FIXTURE_NAME" GIT_COMMITTER_EMAIL="$FIXTURE_EMAIL" \
    GIT_AUTHOR_DATE="$FIXTURE_DATE"    GIT_COMMITTER_DATE="$FIXTURE_DATE" \
    git -C "$dir" \
        -c user.name="$FIXTURE_NAME" -c user.email="$FIXTURE_EMAIL" \
        -c core.excludesFile=/dev/null -c core.attributesFile=/dev/null \
        -c core.autocrlf=false -c core.eol=lf -c commit.gpgsign=false \
        -c init.defaultBranch=main \
        "$@"
}

fixture_history() {
  local dir="$1" message="$2" sidecar
  rm -rf -- "$dir/.git"
  fixture_git "$dir" init -q
  : > "$dir/.git/info/exclude"
  for sidecar in $SIDECARS; do
    printf '/%s\n' "$sidecar" >> "$dir/.git/info/exclude"
  done
  fixture_git "$dir" add -A
  fixture_git "$dir" commit -q --no-verify -m "$message"
}

make_simple_packages() {
  fixture_reset "$1"

  emit src/main/java/Bootstrap.java <<'JAVA'
/**
 * Fixture: o pacote padrão existe e o seu id é `pkg:`, sem nome qualificado.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    public static void main(String[] args) {
        Log.info("strata");
    }
}
JAVA

  emit src/main/java/br/pucpr/Log.java <<'JAVA'
package br.pucpr;

/**
 * Fixture: varargs normaliza para vetor no id do método:
 * `info(java.lang.String,java.lang.Object[])`.
 */
public final class Log {

    private Log() {
    }

    public static void info(String message, Object... args) {
    }
}
JAVA

  emit src/main/java/br/pucpr/orders/Order.java <<'JAVA'
package br.pucpr.orders;

import java.util.UUID;

/**
 * Fixture: tipo aninhado nomeado é elemento e usa `$` no id, o que o desambigua de um
 * pacote chamado `Order`.
 */
public final class Order {

    private final UUID id;

    public Order(UUID id) {
        this.id = id;
    }

    public UUID id() {
        return id;
    }

    /** Fixture: `type:br.pucpr.orders.Order$Builder`. */
    public static final class Builder {

        private UUID id;

        public Builder() {
        }

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Order build() {
            return new Order(id);
        }
    }
}
JAVA

  emit src/main/java/br/pucpr/orders/OrderService.java <<'JAVA'
package br.pucpr.orders;

import java.util.List;
import java.util.UUID;

/**
 * Fixture: a classe anônima não é elemento, e o seu `run()` pertence ao tipo nomeado mais
 * próximo; `List<String>` apaga para `java.util.List`; e `T extends Order` apaga para o
 * primeiro limite.
 */
public final class OrderService {

    private final List<Order> orders;

    public OrderService(List<Order> orders) {
        this.orders = orders;
    }

    public void register(List<String> labels) {
    }

    public <T extends Order> T first(T candidate) {
        return candidate;
    }

    public Runnable auditor() {
        return new Runnable() {

            public void run() {
                Log.info("audit");
            }
        };
    }

    public Order build(UUID id) {
        return new Order.Builder().id(id).build();
    }
}
JAVA

  emit src/main/java/br/pucpr/orders/package-info.java <<'JAVA'
/**
 * Fixture: `package-info.java` conta para o LOC do pacote e não produz TypeElement.
 */
package br.pucpr.orders;
JAVA
}

make_inheritance() {
  fixture_reset "$1"

  emit src/main/java/br/pucpr/payments/Named.java <<'JAVA'
package br.pucpr.payments;

public interface Named {

    void describe();
}
JAVA

  emit src/main/java/br/pucpr/payments/Traceable.java <<'JAVA'
package br.pucpr.payments;

public interface Traceable {

    void trace();
}
JAVA

  emit src/main/java/br/pucpr/payments/Auditable.java <<'JAVA'
package br.pucpr.payments;

/**
 * Fixture: interface que estende duas interfaces. São duas arestas EXTENDS, e não
 * IMPLEMENTS, porque a palavra-chave usada é `extends`.
 */
public interface Auditable extends Named, Traceable {

    void audit();
}
JAVA

  emit src/main/java/br/pucpr/payments/Instrument.java <<'JAVA'
package br.pucpr.payments;

public interface Instrument {

    void charge();
}
JAVA

  emit src/main/java/br/pucpr/payments/BaseGateway.java <<'JAVA'
package br.pucpr.payments;

public abstract class BaseGateway {

    protected BaseGateway() {
    }

    protected void connect() {
    }
}
JAVA

  emit src/main/java/br/pucpr/payments/Card.java <<'JAVA'
package br.pucpr.payments;

public final class Card implements Instrument {

    public Card() {
    }

    public void charge() {
    }
}
JAVA

  emit src/main/java/br/pucpr/payments/CardGateway.java <<'JAVA'
package br.pucpr.payments;

/**
 * Fixture: uma classe com N supertipos produz N arestas: uma EXTENDS para a superclasse
 * e uma IMPLEMENTS por interface. Nada de `@Override` nesta fixture: a anotação criaria
 * uma aresta ANNOTATION_REFERENCE para `java.lang.Override` e o golden lista as
 * `dependencies` de forma exaustiva.
 */
public final class CardGateway extends BaseGateway implements Auditable, Instrument {

    public CardGateway() {
    }

    public void audit() {
    }

    public void charge() {
    }

    public void describe() {
    }

    public void trace() {
    }
}
JAVA
}

make_cyclic() {
  fixture_reset "$1"

  emit settings.gradle <<'GRADLE'
rootProject.name = 'cyclic'
GRADLE

  emit build.gradle <<'GRADLE'
plugins {
    id 'java'
}
GRADLE

  emit src/main/java/com/x/app/Main.java <<'JAVA'
package com.x.app;

import com.x.orders.Order;

/**
 * Fixture: aponta para dentro do ciclo, mas não faz parte dele. Serve para pegar uma
 * implementação que chame "alcançável a partir de um ciclo" de ciclo.
 */
public final class Main {

    private final Order order;

    public Main(Order order) {
        this.order = order;
    }

    public void run() {
        order.touch();
    }
}
JAVA

  emit src/main/java/com/x/orders/Order.java <<'JAVA'
package com.x.orders;

import com.x.orders.dto.OrderDto;

/**
 * Fixture: metade do ciclo de pacote. Um pacote que depende do seu subpacote é uma
 * aresta só; o ciclo precisa da volta, que é o DTO que conhece o agregado.
 */
public final class Order {

    private final OrderDto dto;

    public Order(OrderDto dto) {
        this.dto = dto;
    }

    public void touch() {
    }
}
JAVA

  emit src/main/java/com/x/orders/dto/OrderDto.java <<'JAVA'
package com.x.orders.dto;

import com.x.orders.Order;

public final class OrderDto {

    private Order order;

    public OrderDto() {
    }

    public void attach(Order value) {
        this.order = value;
    }
}
JAVA

  emit src/main/java/com/x/billing/Invoice.java <<'JAVA'
package com.x.billing;

/**
 * Fixture: ciclo de três tipos dentro de um só pacote. No nível de pacote ele não produz
 * aresta nenhuma, porque `P -> P` é excluído; o acoplamento aparece como
 * `internalCohesion`.
 */
public final class Invoice {

    private Ledger ledger;

    public Invoice() {
    }

    public void post(Ledger value) {
        this.ledger = value;
    }
}
JAVA

  emit src/main/java/com/x/billing/Ledger.java <<'JAVA'
package com.x.billing;

public final class Ledger {

    private Posting posting;

    public Ledger() {
    }

    public void record(Posting value) {
        this.posting = value;
    }
}
JAVA

  emit src/main/java/com/x/billing/Posting.java <<'JAVA'
package com.x.billing;

public final class Posting {

    private Invoice invoice;

    public Posting() {
    }

    public void bill(Invoice value) {
        this.invoice = value;
    }
}
JAVA
}

make_method_calls() {
  fixture_reset "$1"

  emit src/main/java/a/b/Order.java <<'JAVA'
package a.b;

public final class Order {

    public Order() {
    }
}
JAVA

  emit src/main/java/a/c/Task.java <<'JAVA'
package a.c;

public interface Task {

    void run();
}
JAVA

  emit src/main/java/a/c/OrderRepository.java <<'JAVA'
package a.c;

public final class OrderRepository {

    public OrderRepository() {
    }

    public void reload() {
    }

    public void audit() {
    }

    public void first() {
    }
}
JAVA

  emit src/main/java/a/c/OrderService.java <<'JAVA'
package a.c;

import a.b.*;
import java.util.List;

/**
 * Fixture: cinco pontos de chamada e duas declarações de campo para OrderRepository,
 * com peso agregado 5*2 + 2*3 = 16. Os campos não são
 * inicializados de propósito: um `new OrderRepository()` acrescentaria um ponto de
 * chamada e a conta deixaria de dar 16.
 *
 * O `import a.b.*;` não nomeia tipo nenhum e não produz aresta. `List<Order>` desembrulha
 * recursivamente e produz as duas metades: `java.util.List`, externa, e `a.b.Order`,
 * interna.
 */
public final class OrderService {

    private OrderRepository primary;
    private OrderRepository fallback;
    private final List<Order> cache;

    public OrderService(List<Order> cache) {
        this.cache = cache;
    }

    public void refresh() {
        primary.reload();
        fallback.reload();
    }

    public Task auditor() {
        return () -> primary.audit();
    }

    public Task reporter() {
        return new Task() {

            public void run() {
                fallback.audit();
            }
        };
    }

    public void seed() {
        primary.first();
    }
}
JAVA
}

make_missing_classpath() {
  fixture_reset "$1"

  emit core/src/main/java/com/acme/core/Widget.java <<'JAVA'
package com.acme.core;

public final class Widget {

    public Widget() {
    }
}
JAVA

  emit core/src/main/java/com/acme/core/Registry.java <<'JAVA'
package com.acme.core;

public final class Registry {

    public Registry() {
    }
}
JAVA

  emit core/src/main/java/com/acme/core/Config.java <<'JAVA'
package com.acme.core;

/**
 * Fixture: primeira metade da ambiguidade. O nome simples `Config` casa com dois tipos
 * internos, e a resolução por nome simples não adivinha.
 */
public final class Config {

    public Config() {
    }
}
JAVA

  emit plugin/src/main/java/com/acme/core/Shared.java <<'JAVA'
package com.acme.core;

/**
 * Fixture: o mesmo pacote declarado em duas raízes de fonte. É o caso que quebra quem
 * assume "um pacote, uma raiz".
 */
public final class Shared {

    public Shared() {
    }
}
JAVA

  emit plugin/src/main/java/com/acme/plugin/Config.java <<'JAVA'
package com.acme.plugin;

public final class Config {

    public Config() {
    }
}
JAVA

  emit plugin/src/main/java/com/acme/plugin/Loader.java <<'JAVA'
package com.acme.plugin;

import com.acme.core.Widget;

/**
 * Fixture: resolução por import de tipo único, que mapeia o nome simples para um tipo interno
 * declarado em outra raiz de fonte. O modelo do Spoon é por raiz, então a declaração não
 * está nele e a referência é INFERRED, não RESOLVED.
 */
public final class Loader {

    private Widget widget;

    public Loader() {
    }
}
JAVA

  emit plugin/src/main/java/com/acme/plugin/Finder.java <<'JAVA'
package com.acme.plugin;

import com.acme.core.*;

/**
 * Fixture: resolução por import sob demanda. Ele mapeia `Registry` para exatamente um tipo
 * interno; como não nomeia tipo nenhum, o import em si não produz aresta IMPORTS.
 */
public final class Finder {

    private Registry registry;

    public Finder() {
    }
}
JAVA

  emit plugin/src/main/java/com/acme/plugin/spi/Probe.java <<'JAVA'
package com.acme.plugin.spi;

import com.thirdparty.Missing;

/**
 * Fixture: quatro desfechos em um arquivo só.
 *
 * `Registry` sem import nenhum resolve pelo nome simples, que casa com exatamente um
 * tipo interno. `Config` é o caso ambíguo: casa com dois, e o resultado é
 * UNRESOLVED_EXTERNAL marcado como ambíguo, nunca um palpite. `String` é
 * RESOLVED_EXTERNAL. `Missing` é externo de verdade. E `T` não é dependência nenhuma:
 * variável de tipo nunca é.
 */
public final class Probe {

    private Registry registry;

    private Config config;

    private String label;

    private Missing missing;

    public Probe() {
    }

    public <T> void accept(T value) {
    }
}
JAVA
}

make_broken_source() {
  fixture_reset "$1"

  emit src/main/java/br/pucpr/broken/Healthy.java <<'JAVA'
package br.pucpr.broken;

/**
 * Fixture: continua sendo analisado depois de o vizinho quebrado ser isolado.
 */
public final class Healthy {

    private Neighbour neighbour;

    public Healthy() {
    }
}
JAVA

  emit src/main/java/br/pucpr/broken/Neighbour.java <<'JAVA'
package br.pucpr.broken;

public final class Neighbour {

    public Neighbour() {
    }
}
JAVA

  emit src/main/java/br/pucpr/broken/package-info.java <<'JAVA'
/**
 * Fixture: um arquivo que não produz nenhum tipo não é uma falha de parsing. Essa distinção é o
 * que separa `parseFailures` de "nada foi extraído daqui".
 */
package br.pucpr.broken;
JAVA

  emit src/main/java/br/pucpr/broken/ReportPath.java <<'JAVA'
package br.pucpr.broken;

public class ReportPath {

    void ( ] {
        String s = "sem fechar
        int ] = ( { ;
        class class class
JAVA
}

make_multi_module() {
  fixture_reset "$1"

  emit pom.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>br.pucpr.legacy</groupId>
  <artifactId>legacy-parent</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
  <modules>
    <module>core</module>
    <module>api</module>
  </modules>
</project>
XML

  emit settings.gradle <<'GRADLE'
rootProject.name = 'legacy'
include 'core'
include 'api'
GRADLE

  emit core/pom.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>br.pucpr.legacy</groupId>
    <artifactId>legacy-parent</artifactId>
    <version>1.0.0</version>
  </parent>
  <artifactId>legacy-core</artifactId>
</project>
XML

  emit api/pom.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>br.pucpr.legacy</groupId>
    <artifactId>legacy-parent</artifactId>
    <version>1.0.0</version>
  </parent>
  <artifactId>legacy-api</artifactId>
  <dependencies>
    <dependency>
      <groupId>br.pucpr.legacy</groupId>
      <artifactId>legacy-core</artifactId>
      <version>1.0.0</version>
    </dependency>
  </dependencies>
</project>
XML

  emit core/src/main/java/br/pucpr/legacy/core/Kind.java <<'JAVA'
package br.pucpr.legacy.core;

/** Fixture: `TypeKind.ENUM`. */
public enum Kind {
    BOOK,
    MOVIE
}
JAVA

  emit core/src/main/java/br/pucpr/legacy/core/Audited.java <<'JAVA'
package br.pucpr.legacy.core;

/** Fixture: `TypeKind.ANNOTATION`. */
public @interface Audited {
}
JAVA

  emit core/src/main/java/br/pucpr/legacy/core/CatalogEntry.java <<'JAVA'
package br.pucpr.legacy.core;

/** Fixture: `TypeKind.RECORD`. */
public record CatalogEntry(String title, Kind kind) {
}
JAVA

  emit core/src/main/java/br/pucpr/legacy/core/spi/Store.java <<'JAVA'
package br.pucpr.legacy.core.spi;

import br.pucpr.legacy.core.CatalogEntry;

/** Fixture: `TypeKind.INTERFACE`, e a volta do ciclo de pacote com `core`. */
public interface Store {

    void save(CatalogEntry entry);
}
JAVA

  emit core/src/main/java/br/pucpr/legacy/core/spi/MemoryStore.java <<'JAVA'
package br.pucpr.legacy.core.spi;

import br.pucpr.legacy.core.CatalogEntry;

public final class MemoryStore implements Store {

    public MemoryStore() {
    }

    public void save(CatalogEntry entry) {
    }
}
JAVA

  emit core/src/main/java/br/pucpr/legacy/core/Catalog.java <<'JAVA'
package br.pucpr.legacy.core;

import br.pucpr.legacy.core.spi.Store;

@Audited
public final class Catalog {

    private final Store store;

    public Catalog(Store store) {
        this.store = store;
    }

    public void add(CatalogEntry entry) {
        store.save(entry);
    }
}
JAVA

  emit core/src/test/java/br/pucpr/legacy/core/CatalogTest.java <<'JAVA'
package br.pucpr.legacy.core;

import br.pucpr.legacy.core.spi.MemoryStore;

/** Fixture: a raiz TEST, que sai da coorte de percentil quando `includeTests` é falso. */
public final class CatalogTest {

    public CatalogTest() {
    }

    public void addsEntry() {
        Catalog catalog = new Catalog(new MemoryStore());
        catalog.add(new CatalogEntry("titulo", Kind.BOOK));
    }
}
JAVA

  emit core/target/generated-sources/annotations/br/pucpr/legacy/core/gen/CatalogMetadata.java <<'JAVA'
package br.pucpr.legacy.core.gen;

/**
 * Gerado por processador de anotação. Não editar.
 *
 * Fixture: a raiz GENERATED. Ela fica sob `target/`, que está no ignore global de quase
 * toda máquina que compila Java. Por isso o `.gitignore` do repositório tem a negação
 * que a reinclui, e o gerador passa `-c core.excludesFile=/dev/null`.
 */
public final class CatalogMetadata {

    public static final String NAME = "catalog";

    private CatalogMetadata() {
    }
}
JAVA

  emit api/src/main/java/br/pucpr/legacy/api/CatalogEndpoint.java <<'JAVA'
package br.pucpr.legacy.api;

import br.pucpr.legacy.core.Catalog;
import br.pucpr.legacy.core.CatalogEntry;
import br.pucpr.legacy.core.Kind;

public final class CatalogEndpoint {

    private final Catalog catalog;

    public CatalogEndpoint(Catalog catalog) {
        this.catalog = catalog;
    }

    public void post(String title) {
        catalog.add(new CatalogEntry(title, Kind.MOVIE));
    }
}
JAVA

  emit api/src/main/java/br/pucpr/legacy/api/Main.java <<'JAVA'
package br.pucpr.legacy.api;

import br.pucpr.legacy.core.Catalog;
import br.pucpr.legacy.core.spi.MemoryStore;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        CatalogEndpoint endpoint = new CatalogEndpoint(new Catalog(new MemoryStore()));
        endpoint.post("strata");
    }
}
JAVA
}

build_one() {
  local name="$1" dir="$2"
  case "$name" in
    simple-packages)   make_simple_packages "$dir" ;;
    inheritance)       make_inheritance "$dir" ;;
    cyclic)            make_cyclic "$dir" ;;
    method-calls)      make_method_calls "$dir" ;;
    missing-classpath) make_missing_classpath "$dir" ;;
    broken-source)     make_broken_source "$dir" ;;
    multi-module)      make_multi_module "$dir" ;;
    *) echo "make-fixtures: não há gerador para '$name'" >&2; exit 2 ;;
  esac
}

build_all() {
  local out="$1" nogit="$2" name dir
  validate_out_dir "$out"
  for name in $FIXTURES; do
    dir="$out/$name"
    build_one "$name" "$dir"
    if [ "$nogit" = 0 ]; then
      fixture_history "$dir" "adiciona a fixture $name"
    else
      rm -rf -- "$dir/.git"
    fi
  done
}

print_ids() {
  local out="$1" name
  for name in $FIXTURES; do
    printf '%s %s\n' "$name" "$(fixture_git "$out/$name" rev-parse HEAD)"
  done
}

report() {
  local out="$1" nogit="$2" name total=0
  for name in $FIXTURES; do
    total=$((total + 1))
  done
  printf 'make-fixtures · %d fixture%s · %s\n' \
    "$total" "$( [ "$total" = 1 ] && echo "" || echo "s" )" \
    "$( [ "$nogit" = 1 ] && echo "só fontes, sem histórico" || echo "fontes e histórico" )"
  if [ "$nogit" = 0 ]; then
    print_ids "$out" | sed 's/^/  /'
    print_ids "$out" > "$out/commits.txt"
    printf '  commits.txt gravado\n'
  fi
}

self_test() {
  local pass=0 failed=0
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT

  gen() {
    local out="$1"; shift
    case "$out" in
      "$tmp"/*) ;;
      *) echo "autoteste: recusando rm fora de \$tmp: $out" >&2; exit 2 ;;
    esac
    rm -rf -- "$out"
    bash "$SELF" --out "$out" "$@" 2>"$tmp/log"
  }

  ids() {
    bash "$SELF" --out "$1" --print-ids
  }

  ok() { pass=$((pass + 1)); printf '  ok     %s\n' "$1"; }
  no() { failed=$((failed + 1)); printf '  falha  %s: %s\n' "$1" "$2"; }

  echo "make-fixtures --self-test"

  gen "$tmp/a" >/dev/null
  ids "$tmp/a" > "$tmp/id-a"
  if [ "$(wc -l < "$tmp/id-a" | tr -d ' ')" = 7 ] &&
     [ "$(grep -c -E '^[a-z-]+ [0-9a-f]{40}$' "$tmp/id-a")" = 7 ]; then
    ok "sete fixtures, sete ids de commit com quarenta hex"
  else
    no "sete fixtures, sete ids de commit com quarenta hex" "$(cat "$tmp/id-a")"
  fi

  gen "$tmp/b" >/dev/null
  ids "$tmp/b" > "$tmp/id-b"
  if [ "$(wc -l < "$tmp/id-b" | tr -d ' ')" = 7 ] && diff -q "$tmp/id-a" "$tmp/id-b" >/dev/null; then
    ok "duas execuções em duas pastas dão ids idênticos"
  else
    no "duas execuções em duas pastas dão ids idênticos" "$(diff "$tmp/id-a" "$tmp/id-b" | head -4)"
  fi

  mkdir -p "$tmp/xdg/git"
  printf 'target/\n' > "$tmp/xdg/git/ignore"
  printf '* text=auto eol=crlf\n' > "$tmp/xdg/git/attributes"
  (
    export XDG_CONFIG_HOME="$tmp/xdg"
    export GIT_AUTHOR_NAME=Ninguem GIT_AUTHOR_EMAIL=ninguem@example.com
    export GIT_COMMITTER_NAME=Outro GIT_COMMITTER_EMAIL=outro@example.com
    export EMAIL=outro@example.com
    gen "$tmp/hostile" >/dev/null
    ids "$tmp/hostile" > "$tmp/id-hostile"
  )
  if [ "$(wc -l < "$tmp/id-hostile" | tr -d ' ')" = 7 ] && diff -q "$tmp/id-a" "$tmp/id-hostile" >/dev/null; then
    ok "um ambiente hostil não muda nenhum id de commit"
  else
    no "um ambiente hostil não muda nenhum id de commit" "$(diff "$tmp/id-a" "$tmp/id-hostile" | head -4)"
  fi

  if [ -f "$tmp/hostile/multi-module/core/target/generated-sources/annotations/br/pucpr/legacy/core/gen/CatalogMetadata.java" ] &&
     [ -n "$(cd "$tmp/hostile/multi-module" && fixture_git . ls-files -- core/target)" ]; then
    ok "um ignore global de target/ não derruba a raiz GENERATED"
  else
    no "um ignore global de target/ não derruba a raiz GENERATED" "o arquivo não está no índice"
  fi

  gen "$tmp/nogit" --no-git >/dev/null
  if [ ! -e "$tmp/nogit/simple-packages/.git" ] && [ -f "$tmp/nogit/simple-packages/src/main/java/Bootstrap.java" ]; then
    ok "--no-git grava os fontes e não cria .git"
  else
    no "--no-git grava os fontes e não cria .git" "sobrou um .git, ou faltam os fontes"
  fi

  gen "$tmp/keep" >/dev/null
  printf '{"version": 1}\n' > "$tmp/keep/cyclic/expected.json"
  printf '# cyclic\n' > "$tmp/keep/cyclic/README.md"
  printf 'stale\n' > "$tmp/keep/cyclic/src/main/java/Stale.java"
  bash "$SELF" --out "$tmp/keep" >/dev/null 2>&1
  if [ -f "$tmp/keep/cyclic/expected.json" ] && [ -f "$tmp/keep/cyclic/README.md" ] &&
     [ ! -f "$tmp/keep/cyclic/src/main/java/Stale.java" ]; then
    ok "a regeneração mantém os sidecars e remove um fonte antigo"
  else
    no "a regeneração mantém os sidecars e remove um fonte antigo" "um sidecar sumiu ou o arquivo antigo sobreviveu"
  fi

  if [ -z "$(cd "$tmp/keep/cyclic" && fixture_git . ls-files -- expected.json README.md)" ]; then
    ok "o histórico da própria fixture não versiona os sidecars"
  else
    no "o histórico da própria fixture não versiona os sidecars" "há um sidecar no índice interno"
  fi

  if [ "$(cd "$tmp/a/simple-packages" && fixture_git . symbolic-ref --short HEAD)" = "main" ]; then
    ok "o branch é main, não o padrão do git"
  else
    no "o branch é main, não o padrão do git" "o HEAD aponta para outro lugar"
  fi

  if [ -s "$tmp/a/commits.txt" ] && [ "$(wc -l < "$tmp/a/commits.txt" | tr -d ' ')" = 7 ]; then
    ok "commits.txt tem uma linha por fixture"
  else
    no "commits.txt tem uma linha por fixture" "o arquivo não existe ou está incompleto"
  fi

  set +e
  bash "$SELF" --out "$HOME" >/dev/null 2>&1; local rc_home=$?
  bash "$SELF" --out "$ROOT" >/dev/null 2>&1; local rc_root=$?
  bash "$SELF" --frobnicate >/dev/null 2>&1;  local rc_opt=$?
  set -e
  [ "$rc_home" = 2 ] && ok "--out \$HOME é recusado" || no "--out \$HOME é recusado" "saída $rc_home"
  [ "$rc_root" = 2 ] && ok "--out com a raiz do repositório é recusado" || no "--out com a raiz do repositório é recusado" "saída $rc_root"
  [ "$rc_opt" = 2 ]  && ok "uma opção desconhecida é erro de uso" || no "uma opção desconhecida é erro de uso" "saída $rc_opt"

  printf '\n%d ok, %d com falha\n' "$pass" "$failed"
  [ "$failed" -eq 0 ]
}

if [ "${SELF_TEST:-0}" = 1 ]; then
  self_test
  exit $?
fi

if [ "$PRINT_IDS" = 1 ]; then
  print_ids "$OUT_DIR"
  exit 0
fi

build_all "$OUT_DIR" "$NO_GIT"
report "$OUT_DIR" "$NO_GIT"
