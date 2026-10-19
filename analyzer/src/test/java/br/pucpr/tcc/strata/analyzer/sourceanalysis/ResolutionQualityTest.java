package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingStructureSink.evidence;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.ResolutionHarness.Extraction;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ResolutionHarness.Layout;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ExtractedDependency;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ResolutionQualityTest {

  @TempDir private Path temp;

  @ParameterizedTest(name = "{0}")
  @MethodSource("trechos")
  void cadaTrechoDaAsTuplasGoldenExatas(Trecho trecho) throws Exception {
    final Extraction run =
        ResolutionHarness.extract(
            temp.resolve("repositorio"), trecho.layout(), trecho.core(), trecho.app());

    assertThat(run.sink().tuples())
        .as(trecho + ": " + trecho.derivacao())
        .containsExactlyElementsOf(trecho.tuplas());
    assertThat(run.drops()).as(trecho + ": descartes por motivo").isEqualTo(trecho.descartes());
    if (trecho.contagens() != null) {
      assertThat(run.extractor().resolutionCounts())
          .as(trecho + ": contagens por ocorrência")
          .isEqualTo(trecho.contagens());
    }
  }

  static Stream<Trecho> trechos() {
    final List<Trecho> todos = new ArrayList<>();
    todos.addAll(regrasDeNome());
    todos.addAll(ambiguidade());
    todos.addAll(jdkPorNome());
    todos.addAll(tiposMembro());
    todos.addAll(oraculoDeTipos());
    todos.addAll(chamadas());
    todos.addAll(nomesQueOSpoonEstraga());
    return todos.stream();
  }

  private static List<Trecho> regrasDeNome() {
    final Map<String, String> s01core = Map.of("a/Api.java", API);
    final Map<String, String> s01app =
        Map.of(
            "b/User.java",
            """
            package b;

            import a.Api;

            public class User {
              Api api;
            }
            """);
    final String s01 = "JLS 7.5.1: o import de tipo único nomeia a.Api, interno";
    final Map<String, String> s03core =
        Map.of("a/Api.java", API, "b/Peer.java", "package b;\n\npublic class Peer {}\n");
    final Map<String, String> s03app =
        Map.of(
            "b/User.java",
            """
            package b;

            import a.*;

            public class User {
              Api api;
              Peer peer;
            }
            """);
    final String s03 =
        "JLS 6.4.1 e 7.5.2: Peer é do próprio pacote (dividido entre raízes) e Api o único do a.*;"
            + " import sob demanda de pacote não nomeia tipo, sem IMPORTS";
    final Map<String, String> s05core = Map.of("a/Registry.java", REGISTRY);
    final String s05 =
        "escrito aqui, escopo completo, nome simples, um candidato de topo → a.Registry,"
            + " INFERRED também em uma raiz (colocado pela regra iii)";
    final Map<String, String> s16core =
        Map.of("a/Order.java", "package a;\n\npublic class Order {}\n");
    final Map<String, String> s16app =
        Map.of(
            "b/Ledger.java",
            """
            package b;

            import a.Order;

            public class Ledger {
              Order[] orders;
            }
            """);
    final String s16 = "o vetor desembrulha no componente antes de classificar";
    final Map<String, String> s19app =
        Map.of(
            "Bootstrap.java",
            """
            public class Bootstrap {
              Log log;
            }
            """,
            "p/User.java",
            """
            package p;

            public class User {
              Bootstrap boot;
            }
            """,
            "q/Log.java",
            "package q;\n\npublic class Log {}\n");
    final String s19 =
        "JLS 7.5: do pacote padrão, q.Log é o único candidato → INFERRED; de p, o Bootstrap do"
            + " pacote padrão nunca é candidato, e o nome nu que o colide é descartado";
    final Map<String, String> s20core =
        Map.of("p/Record.java", "package p;\n\npublic class Record {}\n");
    final Map<String, String> s20app =
        Map.of(
            "p/User.java",
            """
            package p;

            public class User {
              Record record;
            }
            """);
    final String s20 =
        "JLS 6.4.1: o Record do próprio pacote esconde o java.lang.Record; nunca a declaração"
            + " SHADOW que o Spoon dá ao java.lang";
    final Map<String, String> x3core =
        Map.of(
            "a/Registry.java",
            REGISTRY,
            "a/Holder.java",
            """
            package a;

            public class Holder {
              public static class Registry {}
            }
            """);
    final String x3 =
        "os candidatos da regra (iii) são tipos de topo; a.Holder$Registry não se nomeia por"
            + " nome simples fora do seu escopo → um candidato só";
    return List.of(
        trecho("S01 regra (i)", Layout.TWO_ROOTS, s01core, s01app, s01)
            .com("b.User -> a.Api IMPORTS x1 INFERRED", "b.User -> a.Api FIELD_TYPE x1 INFERRED"),
        trecho("S02 regra (i)", Layout.ONE_ROOT, s01core, s01app, s01)
            .com("b.User -> a.Api IMPORTS x1 RESOLVED", "b.User -> a.Api FIELD_TYPE x1 RESOLVED"),
        trecho("S03 regra (ii)", Layout.TWO_ROOTS, s03core, s03app, s03)
            .com(
                "b.User -> a.Api FIELD_TYPE x1 INFERRED",
                "b.User -> b.Peer FIELD_TYPE x1 INFERRED"),
        trecho("S04 regra (ii)", Layout.ONE_ROOT, s03core, s03app, s03)
            .com(
                "b.User -> a.Api FIELD_TYPE x1 RESOLVED",
                "b.User -> b.Peer FIELD_TYPE x1 RESOLVED"),
        trecho("S05 regra (iii)", Layout.TWO_ROOTS, s05core, PROBE_REGISTRY, s05)
            .com("c.Probe -> a.Registry FIELD_TYPE x1 INFERRED"),
        trecho("S06 regra (iii)", Layout.ONE_ROOT, s05core, PROBE_REGISTRY, s05)
            .com("c.Probe -> a.Registry FIELD_TYPE x1 INFERRED"),
        trecho("S16 vetor interno", Layout.TWO_ROOTS, s16core, s16app, s16)
            .com(
                "b.Ledger -> a.Order IMPORTS x1 INFERRED",
                "b.Ledger -> a.Order FIELD_TYPE x1 INFERRED"),
        trecho("S16b vetor interno", Layout.ONE_ROOT, s16core, s16app, s16)
            .com(
                "b.Ledger -> a.Order IMPORTS x1 RESOLVED",
                "b.Ledger -> a.Order FIELD_TYPE x1 RESOLVED"),
        trecho("S19 pacote padrão", Layout.ONE_ROOT, Map.of(), s19app, s19)
            .com("Bootstrap -> q.Log FIELD_TYPE x1 INFERRED")
            .descartando(DropReason.DEFAULT_PACKAGE_COLLISION, 1),
        trecho("S20 próprio pacote sobre java.lang", Layout.TWO_ROOTS, s20core, s20app, s20)
            .com("p.User -> p.Record FIELD_TYPE x1 INFERRED"),
        trecho("S20b próprio pacote sobre java.lang", Layout.ONE_ROOT, s20core, s20app, s20)
            .com("p.User -> p.Record FIELD_TYPE x1 RESOLVED"),
        trecho("X3 tipo membro não é candidato", Layout.TWO_ROOTS, x3core, PROBE_REGISTRY, x3)
            .com("c.Probe -> a.Registry FIELD_TYPE x1 INFERRED"),
        trecho("X3 tipo membro não é candidato", Layout.ONE_ROOT, x3core, PROBE_REGISTRY, x3)
            .com("c.Probe -> a.Registry FIELD_TYPE x1 INFERRED"));
  }

  private static List<Trecho> ambiguidade() {
    final Map<String, String> configs = Map.of("c/Config.java", CONFIG_IN_C);
    final Map<String, String> s07app =
        Map.of("d/Config.java", CONFIG_IN_D, "e/Probe.java", PROBE_CONFIG);
    final String s07 =
        "dois candidatos de topo, c.Config e d.Config → type:Config nu, U, ambíguo; nenhuma"
            + " linha para c.Config ou d.Config";
    final String s07c = "controle do S07: sem o d.Config sobra um candidato → INFERRED";
    final Map<String, String> s07dcore =
        Map.of("c/Config.java", CONFIG_IN_C, "d/Config.java", CONFIG_IN_D);
    final Map<String, String> s07dapp =
        Map.of(
            "e/Probe.java",
            """
            package e;

            import c.*;
            import d.*;

            public class Probe {
              Config config;
            }
            """);
    final String s07d =
        "dois imports sob demanda internos oferecem Config (erro da JLS 6.4.1)"
            + " → AMBIGUOUS, nunca o primeiro";
    final Map<String, String> s08core = Map.of("a/Registry.java", REGISTRY);
    final Map<String, String> s08app =
        Map.of(
            "c/Probe.java",
            """
            package c;

            import org.ext.*;

            public class Probe {
              Registry registry;
            }
            """);
    final String s08 =
        "import sob demanda de um pacote que o índice não declara → UNCHANGED_INCOMPLETE:"
            + " nem regra (iii) nem ambíguo, pois o import o fornece; pacote, sem IMPORTS";
    final Map<String, String> s08bapp =
        Map.of(
            "d/Config.java",
            CONFIG_IN_D,
            "e/Probe.java",
            """
            package e;

            import org.ext.*;

            public class Probe {
              Config config;
            }
            """);
    final String s08b =
        "com dois candidatos internos, o import sob demanda externo decide antes da"
            + " contagem: incompleto, externo, não ambíguo";
    final Map<String, String> c08core =
        Map.of(
            "a/I1.java",
            "package a;\n\npublic interface I1 {\n  interface Node {}\n}\n",
            "a/I2.java",
            "package a;\n\npublic interface I2 {\n  interface Node {}\n}\n");
    final Map<String, String> c08app =
        Map.of(
            "b/Both.java",
            """
            package b;

            public class Both implements a.I1, a.I2 {
              Node n;
            }
            """);
    final String c08 =
        "dois membros herdados distintos (a.I1$Node, a.I2$Node; javac: ambíguo) →"
            + " AMBIGUOUS, o nome nu do Spoon";
    final Map<String, String> c11core =
        Map.of("a/LibInner.java", "package a;\n\npublic class LibInner {}\n");
    final Map<String, String> c11app =
        Map.of(
            "b/ExtSub.java",
            """
            package b;

            public class ExtSub extends ext.Lib {
              LibInner li;
            }
            """);
    final String c11 =
        "o membro de um supertipo externo é desconhecido, nunca 'nenhum';"
            + " o escopo incompleto bloqueia a regra (iii) para a isca a.LibInner";
    final Map<String, String> anonymousCore =
        Map.of("a/Params.java", "package a;\n\npublic class Params {}\n");
    final Map<String, String> anonymousApp =
        Map.of(
            "c/Probe.java",
            """
            package c;

            public class Probe {
              void listen() {
                new org.ext.Listener() {
                  public void on(Params params) {}
                };
              }
            }
            """);
    final String anonymous =
        "a anônima tem supertipo externo: Params pode ser membro herdado dele, o"
            + " escopo é incompleto: nunca a regra (iii) para a.Params";
    return List.of(
        trecho("S07 duas correspondências", Layout.TWO_ROOTS, configs, s07app, s07)
            .com("e.Probe -> Config FIELD_TYPE x1 UNRESOLVED_EXTERNAL external ambiguous")
            .contando(0, 0, 1, 1),
        trecho("X4 duas correspondências", Layout.ONE_ROOT, configs, s07app, s07)
            .com("e.Probe -> Config FIELD_TYPE x1 UNRESOLVED_EXTERNAL external ambiguous")
            .contando(0, 0, 1, 1),
        trecho(
                "S07c controle",
                Layout.TWO_ROOTS,
                configs,
                Map.of("e/Probe.java", PROBE_CONFIG),
                s07c)
            .com("e.Probe -> c.Config FIELD_TYPE x1 INFERRED"),
        trecho(
                "S07c controle",
                Layout.ONE_ROOT,
                configs,
                Map.of("e/Probe.java", PROBE_CONFIG),
                s07c)
            .com("e.Probe -> c.Config FIELD_TYPE x1 INFERRED"),
        trecho("S07d dois imports sob demanda", Layout.TWO_ROOTS, s07dcore, s07dapp, s07d)
            .com("e.Probe -> Config FIELD_TYPE x1 UNRESOLVED_EXTERNAL external ambiguous"),
        trecho("S07d dois imports sob demanda", Layout.ONE_ROOT, s07dcore, s07dapp, s07d)
            .com("e.Probe -> Config FIELD_TYPE x1 UNRESOLVED_EXTERNAL external ambiguous"),
        trecho("S08 import sob demanda externo", Layout.TWO_ROOTS, s08core, s08app, s08)
            .com("c.Probe -> Registry FIELD_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho("S08 import sob demanda externo", Layout.ONE_ROOT, s08core, s08app, s08)
            .com("c.Probe -> Registry FIELD_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho("S08b import externo e dois candidatos", Layout.TWO_ROOTS, configs, s08bapp, s08b)
            .com("e.Probe -> Config FIELD_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho("S08b import externo e dois candidatos", Layout.ONE_ROOT, configs, s08bapp, s08b)
            .com("e.Probe -> Config FIELD_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho("C08 dois membros herdados", Layout.TWO_ROOTS, c08core, c08app, c08)
            .com(
                "b.Both -> Node FIELD_TYPE x1 UNRESOLVED_EXTERNAL external ambiguous",
                "b.Both -> a.I1 IMPLEMENTS x1 INFERRED",
                "b.Both -> a.I2 IMPLEMENTS x1 INFERRED"),
        trecho("C08 dois membros herdados", Layout.ONE_ROOT, c08core, c08app, c08)
            .com(
                "b.Both -> Node FIELD_TYPE x1 UNRESOLVED_EXTERNAL external ambiguous",
                "b.Both -> a.I1 IMPLEMENTS x1 RESOLVED",
                "b.Both -> a.I2 IMPLEMENTS x1 RESOLVED"),
        trecho("C11 membro de supertipo externo", Layout.TWO_ROOTS, c11core, c11app, c11)
            .com(
                "b.ExtSub -> LibInner FIELD_TYPE x1 UNRESOLVED_EXTERNAL external",
                "b.ExtSub -> ext.Lib EXTENDS x1 UNRESOLVED_EXTERNAL external"),
        trecho("C11 membro de supertipo externo", Layout.ONE_ROOT, c11core, c11app, c11)
            .com(
                "b.ExtSub -> LibInner FIELD_TYPE x1 UNRESOLVED_EXTERNAL external",
                "b.ExtSub -> ext.Lib EXTENDS x1 UNRESOLVED_EXTERNAL external"),
        trecho(
                "anônima de interface externa",
                Layout.TWO_ROOTS,
                anonymousCore,
                anonymousApp,
                anonymous)
            .com(
                "c.Probe -> Params PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external",
                "c.Probe -> org.ext.Listener CALLS x1 UNRESOLVED_EXTERNAL external"),
        trecho(
                "anônima de interface externa",
                Layout.ONE_ROOT,
                anonymousCore,
                anonymousApp,
                anonymous)
            .com(
                "c.Probe -> Params PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external",
                "c.Probe -> org.ext.Listener CALLS x1 UNRESOLVED_EXTERNAL external"));
  }

  private static List<Trecho> jdkPorNome() {
    final Map<String, String> s09 =
        Map.of("p/User.java", "package p;\n\npublic class User {\n  String name;\n}\n");
    final Map<String, String> s10 =
        Map.of(
            "p/User.java",
            """
            package p;

            import java.util.List;
            import java.util.concurrent.Callable;

            public class User {
              List<Callable<Integer>> jobs;
            }
            """);
    final Map<String, String> s11 =
        Map.of(
            "p/User.java",
            """
            package p;

            import javax.annotation.processing.Processor;
            import org.w3c.dom.Document;

            public class User {
              Processor processor;
              Document document;
            }
            """);
    final Map<String, String> s12 =
        Map.of(
            "p/User.java",
            """
            package p;

            import java.net.http.HttpClient;
            import sun.misc.Unsafe;

            public class User {
              HttpClient client;
              Unsafe unsafe;
            }
            """);
    final Map<String, String> s13 =
        Map.of(
            "p/User.java",
            """
            package p;

            import javax.annotation.PostConstruct;
            import javax.inject.Inject;
            import javax.servlet.http.HttpServlet;

            public class User {
              @Inject HttpServlet servlet;

              @PostConstruct
              public void init() {}
            }
            """);
    final Map<String, String> s14 =
        Map.of(
            "p/Box.java",
            """
            package p;

            public class Box<E> {
              E item;
              String label;

              <T> void accept(T value) {}
            }
            """);
    final Map<String, String> s15 =
        Map.of(
            "p/Counts.java",
            """
            package p;

            public class Counts {
              int[] counts;
              Integer boxed;

              byte[][] raw(long[] seeds) {
                return null;
              }

              void touch(int times) {}
            }
            """);
    return List.of(
        trecho(
                "S09 java.lang",
                Layout.ONE_ROOT,
                Map.of(),
                s09,
                "java.lang é do JDK → (R,1); na lista de exclusão, a linha fica")
            .com("p.User -> java.lang.String FIELD_TYPE x1 RESOLVED external"),
        trecho(
                "S10 java.util e argumentos de tipo",
                Layout.ONE_ROOT,
                Map.of(),
                s10,
                "os argumentos de tipo desembrulham; java.util e java.util.concurrent são do JDK,"
                    + " cada um exato")
            .com(
                "p.User -> java.lang.Integer FIELD_TYPE x1 RESOLVED external",
                "p.User -> java.util.List IMPORTS x1 RESOLVED external",
                "p.User -> java.util.List FIELD_TYPE x1 RESOLVED external",
                "p.User -> java.util.concurrent.Callable IMPORTS x1 RESOLVED external",
                "p.User -> java.util.concurrent.Callable FIELD_TYPE x1 RESOLVED external")
            .contando(5, 0, 0, 0),
        trecho(
                "S11 JDK fora de java.",
                Layout.ONE_ROOT,
                Map.of(),
                s11,
                "javax.annotation.processing (java.compiler) e org.w3c.dom (java.xml) são"
                    + " exportações do JDK 25 → (R,1), e não U só por não terem o prefixo java.")
            .com(
                "p.User -> javax.annotation.processing.Processor IMPORTS x1 RESOLVED external",
                "p.User -> javax.annotation.processing.Processor FIELD_TYPE x1 RESOLVED external",
                "p.User -> org.w3c.dom.Document IMPORTS x1 RESOLVED external",
                "p.User -> org.w3c.dom.Document FIELD_TYPE x1 RESOLVED external"),
        trecho(
                "S12 JDK fora da imagem jlink",
                Layout.ONE_ROOT,
                Map.of(),
                s12,
                "pelo nome do pacote, nunca pela declaração: java.net.http e"
                    + " sun.misc (jdk.unsupported) dão (R,1) no JDK inteiro e na imagem")
            .com(
                "p.User -> java.net.http.HttpClient IMPORTS x1 RESOLVED external",
                "p.User -> java.net.http.HttpClient FIELD_TYPE x1 RESOLVED external",
                "p.User -> sun.misc.Unsafe IMPORTS x1 RESOLVED external",
                "p.User -> sun.misc.Unsafe FIELD_TYPE x1 RESOLVED external"),
        trecho(
                "S13 javax que não é JDK",
                Layout.ONE_ROOT,
                Map.of(),
                s13,
                "javax.annotation (removido no 11), javax.inject e javax.servlet não são"
                    + " exportações do JDK 25 → (U,1); void não é linha")
            .com(
                "p.User -> javax.annotation.PostConstruct IMPORTS x1 UNRESOLVED_EXTERNAL external",
                "p.User -> javax.annotation.PostConstruct ANNOTATION_REFERENCE x1 UNRESOLVED_EXTERNAL external",
                "p.User -> javax.inject.Inject IMPORTS x1 UNRESOLVED_EXTERNAL external",
                "p.User -> javax.inject.Inject ANNOTATION_REFERENCE x1 UNRESOLVED_EXTERNAL external",
                "p.User -> javax.servlet.http.HttpServlet IMPORTS x1 UNRESOLVED_EXTERNAL external",
                "p.User -> javax.servlet.http.HttpServlet FIELD_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho(
                "S14 variável de tipo",
                Layout.ONE_ROOT,
                Map.of(),
                s14,
                "E e T nunca são linha nem ocorrência, embora o Spoon lhes dê declaração"
                    + "; nada de java.lang.Object pela erasure")
            .com("p.Box -> java.lang.String FIELD_TYPE x1 RESOLVED external")
            .contando(1, 0, 0, 0),
        trecho(
                "S15 primitivos",
                Layout.ONE_ROOT,
                Map.of(),
                s15,
                "int[], byte[][], long[], int e void saem antes de classificar")
            .com("p.Counts -> java.lang.Integer FIELD_TYPE x1 RESOLVED external")
            .contando(1, 0, 0, 0));
  }

  private static List<Trecho> tiposMembro() {
    final Map<String, String> base = Map.of("a/Base.java", BASE_INNER);
    final Map<String, String> sub =
        Map.of(
            "b/Sub.java",
            """
            package b;

            import a.Base;

            public class Sub extends Base {
              Inner inner;
            }
            """);
    final String s17 = "JLS 8.5: o membro é herdado; S17 é o C01 do oráculo";
    final Map<String, String> recordCore =
        Map.of(
            "a/Holder.java",
            """
            package a;

            public class Holder {
              public static class Record {}
            }
            """);
    final Map<String, String> recordApp =
        Map.of(
            "b/Sub.java",
            """
            package b;

            public class Sub extends a.Holder {
              Record record;
            }
            """);
    final String s17c =
        "JLS 8.5 e 6.4.1: o Record herdado esconde o java.lang.Record, que o Spoon liga quando o"
            + " supertipo está fora do lote";
    final Map<String, String> api = Map.of("a/Api.java", API_INNER);
    final Map<String, String> s18app =
        Map.of(
            "b/User.java",
            """
            package b;

            import a.Api.*;

            public class User {
              Inner inner;
            }
            """,
            "b/StaticUser.java",
            """
            package b;

            import static a.Api.*;

            public class StaticUser {
              Inner inner;
            }
            """);
    final String s18 =
        "JLS 7.5.2 e 7.5.4: T.* traz os membros declarados, static T.* os"
            + " estáticos; são os C13 e C14 do oráculo";
    final Map<String, String> x2core =
        Map.of(
            "a/Base.java",
            BASE_INNER,
            "a/Other.java",
            """
            package a;

            public class Other {
              public static class Inner {}
            }
            """);
    final String x2 =
        "JLS 8.5: o a.Other$Inner não está na cadeia; a herança decide antes"
            + " de qualquer contagem";
    final Map<String, String> c16core =
        Map.of(
            "a/Api.java",
            """
            package a;

            public class Api {
              public class NonStatic {}
            }
            """);
    final Map<String, String> c16app =
        Map.of(
            "b/OnDemandNonStatic.java",
            """
            package b;

            import a.Api.*;

            public class OnDemandNonStatic {
              NonStatic x;
            }
            """);
    final String c16 = "JLS 7.5.2: import T.* traz também o membro não estático (javac C16)";
    return List.of(
        trecho("S17 membro herdado", Layout.TWO_ROOTS, base, sub, s17)
            .com(
                "b.Sub -> a.Base IMPORTS x1 INFERRED",
                "b.Sub -> a.Base EXTENDS x1 INFERRED",
                "b.Sub -> a.Base$Inner FIELD_TYPE x1 INFERRED"),
        trecho("S17b membro herdado", Layout.ONE_ROOT, base, sub, s17)
            .com(
                "b.Sub -> a.Base IMPORTS x1 RESOLVED",
                "b.Sub -> a.Base EXTENDS x1 RESOLVED",
                "b.Sub -> a.Base$Inner FIELD_TYPE x1 RESOLVED"),
        trecho("S17c Record herdado", Layout.TWO_ROOTS, recordCore, recordApp, s17c)
            .com(
                "b.Sub -> a.Holder EXTENDS x1 INFERRED",
                "b.Sub -> a.Holder$Record FIELD_TYPE x1 INFERRED"),
        trecho("S17c Record herdado", Layout.ONE_ROOT, recordCore, recordApp, s17c)
            .com(
                "b.Sub -> a.Holder EXTENDS x1 RESOLVED",
                "b.Sub -> a.Holder$Record FIELD_TYPE x1 RESOLVED"),
        trecho("S18 import de membro", Layout.TWO_ROOTS, api, s18app, s18)
            .com(
                "b.StaticUser -> a.Api IMPORTS x1 INFERRED",
                "b.StaticUser -> a.Api$Inner FIELD_TYPE x1 INFERRED",
                "b.User -> a.Api IMPORTS x1 INFERRED",
                "b.User -> a.Api$Inner FIELD_TYPE x1 INFERRED"),
        trecho("S18b import de membro", Layout.ONE_ROOT, api, s18app, s18)
            .com(
                "b.StaticUser -> a.Api IMPORTS x1 RESOLVED",
                "b.StaticUser -> a.Api$Inner FIELD_TYPE x1 RESOLVED",
                "b.User -> a.Api IMPORTS x1 RESOLVED",
                "b.User -> a.Api$Inner FIELD_TYPE x1 RESOLVED"),
        trecho("X2 dois Inner, um herdado", Layout.TWO_ROOTS, x2core, sub, x2)
            .com(
                "b.Sub -> a.Base IMPORTS x1 INFERRED",
                "b.Sub -> a.Base EXTENDS x1 INFERRED",
                "b.Sub -> a.Base$Inner FIELD_TYPE x1 INFERRED"),
        trecho("X2 dois Inner, um herdado", Layout.ONE_ROOT, x2core, sub, x2)
            .com(
                "b.Sub -> a.Base IMPORTS x1 RESOLVED",
                "b.Sub -> a.Base EXTENDS x1 RESOLVED",
                "b.Sub -> a.Base$Inner FIELD_TYPE x1 RESOLVED"),
        trecho("C16 membro não estático", Layout.TWO_ROOTS, c16core, c16app, c16)
            .com(
                "b.OnDemandNonStatic -> a.Api IMPORTS x1 INFERRED",
                "b.OnDemandNonStatic -> a.Api$NonStatic FIELD_TYPE x1 INFERRED"),
        trecho("C16 membro não estático", Layout.ONE_ROOT, c16core, c16app, c16)
            .com(
                "b.OnDemandNonStatic -> a.Api IMPORTS x1 RESOLVED",
                "b.OnDemandNonStatic -> a.Api$NonStatic FIELD_TYPE x1 RESOLVED"));
  }

  private static List<Trecho> oraculoDeTipos() {
    final List<Trecho> casos = new ArrayList<>();
    final Map<String, String> c02core =
        Map.of(
            "a/Base.java",
            "package a;\n\npublic class Base {\n  public interface Cb {}\n}\n",
            "a/Mid.java",
            "package a;\n\npublic class Mid extends Base {}\n");
    final Map<String, String> c02app =
        Map.of("b/Sub2.java", "package b;\n\npublic class Sub2 extends a.Mid {\n  Cb x;\n}\n");
    casos.add(
        trecho(
                "C02 membro de interface por Mid",
                Layout.TWO_ROOTS,
                c02core,
                c02app,
                "javac C02: a.Base$Cb, herdado por a.Mid, transitivo")
            .com(
                "a.Mid -> a.Base EXTENDS x1 RESOLVED",
                "b.Sub2 -> a.Base$Cb FIELD_TYPE x1 INFERRED",
                "b.Sub2 -> a.Mid EXTENDS x1 INFERRED"));
    casos.add(
        trecho(
                "C02 membro de interface por Mid",
                Layout.ONE_ROOT,
                c02core,
                c02app,
                "javac C02: a.Base$Cb")
            .com(
                "a.Mid -> a.Base EXTENDS x1 RESOLVED",
                "b.Sub2 -> a.Base$Cb FIELD_TYPE x1 RESOLVED",
                "b.Sub2 -> a.Mid EXTENDS x1 RESOLVED"));
    final Map<String, String> c03core =
        Map.of(
            "a/HasKinds.java",
            """
            package a;

            public interface HasKinds {
              enum Kind {
                A,
                B
              }
            }
            """);
    final Map<String, String> c03app =
        Map.of(
            "b/Impl.java",
            "package b;\n\npublic class Impl implements a.HasKinds {\n  Kind k;\n}\n");
    casos.add(
        trecho(
                "C03 membro de superinterface",
                Layout.TWO_ROOTS,
                c03core,
                c03app,
                "javac C03: a.HasKinds$Kind, pelas superinterfaces")
            .com(
                "b.Impl -> a.HasKinds IMPLEMENTS x1 INFERRED",
                "b.Impl -> a.HasKinds$Kind FIELD_TYPE x1 INFERRED"));
    casos.add(
        trecho(
                "C03 membro de superinterface",
                Layout.ONE_ROOT,
                c03core,
                c03app,
                "javac C03: a.HasKinds$Kind")
            .com(
                "b.Impl -> a.HasKinds IMPLEMENTS x1 RESOLVED",
                "b.Impl -> a.HasKinds$Kind FIELD_TYPE x1 RESOLVED"));
    final Map<String, String> base = Map.of("a/Base.java", BASE_INNER);
    final Map<String, String> c04app =
        Map.of(
            "b/Sub3.java",
            """
            package b;

            public class Sub3 extends a.Base {
              static class Inner {}

              Inner g;
            }
            """);
    final String c04 = "javac C04: o membro declarado esconde o herdado (T$N se declarado)";
    casos.add(
        trecho("C04 declarado sobre herdado", Layout.TWO_ROOTS, base, c04app, c04)
            .com(
                "b.Sub3 -> a.Base EXTENDS x1 INFERRED",
                "b.Sub3 -> b.Sub3$Inner FIELD_TYPE x1 RESOLVED"));
    casos.add(
        trecho("C04 declarado sobre herdado", Layout.ONE_ROOT, base, c04app, c04)
            .com(
                "b.Sub3 -> a.Base EXTENDS x1 RESOLVED",
                "b.Sub3 -> b.Sub3$Inner FIELD_TYPE x1 RESOLVED"));
    final Map<String, String> c05core =
        Map.of("a/Base.java", BASE_INNER, "c/Inner.java", "package c;\n\npublic class Inner {}\n");
    final Map<String, String> c05app =
        Map.of(
            "b/Sub4.java",
            """
            package b;

            import c.Inner;

            public class Sub4 extends a.Base {
              Inner h;
            }
            """);
    final String c05 =
        "javac C05: o membro herdado (passo 1) esconde o import de tipo único (passo 2);"
            + " nunca a aresta para c.Inner";
    casos.add(
        trecho("C05 herdado sobre import", Layout.TWO_ROOTS, c05core, c05app, c05)
            .com(
                "b.Sub4 -> a.Base EXTENDS x1 INFERRED",
                "b.Sub4 -> a.Base$Inner FIELD_TYPE x1 INFERRED",
                "b.Sub4 -> c.Inner IMPORTS x1 INFERRED"));
    casos.add(
        trecho("C05 herdado sobre import", Layout.ONE_ROOT, c05core, c05app, c05)
            .com(
                "b.Sub4 -> a.Base EXTENDS x1 RESOLVED",
                "b.Sub4 -> a.Base$Inner FIELD_TYPE x1 RESOLVED",
                "b.Sub4 -> c.Inner IMPORTS x1 RESOLVED"));
    final Map<String, String> c06core =
        Map.of(
            "a/PBase.java",
            "package a;\n\npublic class PBase {\n  private static class Builder {}\n}\n",
            "c/Builder.java",
            "package c;\n\npublic class Builder {}\n");
    final Map<String, String> c06app =
        Map.of(
            "b/Sub5.java",
            """
            package b;

            import c.Builder;

            public class Sub5 extends a.PBase {
              Builder z;
            }
            """);
    final String c06 = "javac C06: membro privado não é herdado → o import vence";
    casos.add(
        trecho("C06 membro privado", Layout.TWO_ROOTS, c06core, c06app, c06)
            .com(
                "b.Sub5 -> a.PBase EXTENDS x1 INFERRED",
                "b.Sub5 -> c.Builder IMPORTS x1 INFERRED",
                "b.Sub5 -> c.Builder FIELD_TYPE x1 INFERRED"));
    casos.add(
        trecho("C06 membro privado", Layout.ONE_ROOT, c06core, c06app, c06)
            .com(
                "b.Sub5 -> a.PBase EXTENDS x1 RESOLVED",
                "b.Sub5 -> c.Builder IMPORTS x1 RESOLVED",
                "b.Sub5 -> c.Builder FIELD_TYPE x1 RESOLVED"));
    final Map<String, String> c07core =
        Map.of(
            "a/QBase.java",
            "package a;\n\npublic class QBase {\n  static class Entry {}\n}\n",
            "c/Entry.java",
            "package c;\n\npublic class Entry {}\n");
    final Map<String, String> c07app =
        Map.of(
            "b/Sub6.java",
            """
            package b;

            import c.Entry;

            public class Sub6 extends a.QBase {
              Entry e;
            }
            """);
    final String c07 =
        "javac C07: membro de pacote, de outro pacote, não é herdado → o import vence";
    casos.add(
        trecho("C07 membro de pacote", Layout.TWO_ROOTS, c07core, c07app, c07)
            .com(
                "b.Sub6 -> a.QBase EXTENDS x1 INFERRED",
                "b.Sub6 -> c.Entry IMPORTS x1 INFERRED",
                "b.Sub6 -> c.Entry FIELD_TYPE x1 INFERRED"));
    casos.add(
        trecho("C07 membro de pacote", Layout.ONE_ROOT, c07core, c07app, c07)
            .com(
                "b.Sub6 -> a.QBase EXTENDS x1 RESOLVED",
                "b.Sub6 -> c.Entry IMPORTS x1 RESOLVED",
                "b.Sub6 -> c.Entry FIELD_TYPE x1 RESOLVED"));
    final Map<String, String> c09app =
        Map.of(
            "b/Outer.java",
            """
            package b;

            public class Outer extends a.Base {
              class Deep {
                Inner i;
              }
            }
            """);
    final String c09 = "javac C09: a cadeia léxica sobe de Deep para Outer, que herda Inner";
    casos.add(
        trecho("C09 de dentro de um aninhado", Layout.TWO_ROOTS, base, c09app, c09)
            .com(
                "b.Outer -> a.Base EXTENDS x1 INFERRED",
                "b.Outer$Deep -> a.Base$Inner FIELD_TYPE x1 INFERRED"));
    casos.add(
        trecho("C09 de dentro de um aninhado", Layout.ONE_ROOT, base, c09app, c09)
            .com(
                "b.Outer -> a.Base EXTENDS x1 RESOLVED",
                "b.Outer$Deep -> a.Base$Inner FIELD_TYPE x1 RESOLVED"));
    final Map<String, String> apiSub = Map.of("a/Base.java", BASE_INNER, "a/ApiSub.java", API_SUB);
    final Map<String, String> c15app =
        Map.of(
            "b/OnDemandInherited.java",
            """
            package b;

            import static a.ApiSub.*;

            public class OnDemandInherited {
              Inner x;
            }
            """);
    final String c15 = "javac C15: static T.* traz o membro estático herdado";
    casos.add(
        trecho("C15 estático sob demanda herdado", Layout.TWO_ROOTS, apiSub, c15app, c15)
            .com(
                "a.ApiSub -> a.Base EXTENDS x1 RESOLVED",
                "b.OnDemandInherited -> a.ApiSub IMPORTS x1 INFERRED",
                "b.OnDemandInherited -> a.Base$Inner FIELD_TYPE x1 INFERRED"));
    casos.add(
        trecho("C15 estático sob demanda herdado", Layout.ONE_ROOT, apiSub, c15app, c15)
            .com(
                "a.ApiSub -> a.Base EXTENDS x1 RESOLVED",
                "b.OnDemandInherited -> a.ApiSub IMPORTS x1 RESOLVED",
                "b.OnDemandInherited -> a.Base$Inner FIELD_TYPE x1 RESOLVED"));
    final Map<String, String> c17core =
        Map.of(
            "m/Metadata.java",
            "package m;\n\npublic class Metadata {\n  public interface ProjectCustom {}\n}\n");
    final Map<String, String> c17app =
        Map.of(
            "s/Metadata.java",
            "package s;\n\npublic class Metadata {}\n",
            "s/ScriptMeta.java",
            """
            package s;

            import m.Metadata;

            public class ScriptMeta implements Metadata.ProjectCustom {}
            """);
    final String c17 =
        "javac C17: o primeiro segmento pelo import de tipo único antes do prefixo interno"
            + " mais longo do próprio pacote (nunca o fantasma s.Metadata$ProjectCustom)";
    casos.add(
        trecho("C17 Metadata.ProjectCustom", Layout.TWO_ROOTS, c17core, c17app, c17)
            .com(
                "s.ScriptMeta -> m.Metadata IMPORTS x1 INFERRED",
                "s.ScriptMeta -> m.Metadata$ProjectCustom IMPLEMENTS x1 INFERRED"));
    casos.add(
        trecho("C17 Metadata.ProjectCustom", Layout.ONE_ROOT, c17core, c17app, c17)
            .com(
                "s.ScriptMeta -> m.Metadata IMPORTS x1 RESOLVED",
                "s.ScriptMeta -> m.Metadata$ProjectCustom IMPLEMENTS x1 RESOLVED"));
    final Map<String, String> c18core =
        Map.of(
            "a/Base.java",
            """
            package a;

            public class Base {
              public static class Inner {
                public static class Deeper {}
              }
            }
            """);
    final Map<String, String> c18app =
        Map.of(
            "b/Sub8.java",
            "package b;\n\npublic class Sub8 extends a.Base {\n  Inner.Deeper d;\n}\n");
    final String c18 =
        "javac C18: o primeiro segmento é o membro herdado, o resto pela busca de membros";
    casos.add(
        trecho("C18 nome com pontos herdado", Layout.TWO_ROOTS, c18core, c18app, c18)
            .com(
                "b.Sub8 -> a.Base EXTENDS x1 INFERRED",
                "b.Sub8 -> a.Base$Inner$Deeper FIELD_TYPE x1 INFERRED"));
    casos.add(
        trecho("C18 nome com pontos herdado", Layout.ONE_ROOT, c18core, c18app, c18)
            .com(
                "b.Sub8 -> a.Base EXTENDS x1 RESOLVED",
                "b.Sub8 -> a.Base$Inner$Deeper FIELD_TYPE x1 RESOLVED"));
    final Map<String, String> c19app =
        Map.of("b/ViaSub.java", "package b;\n\npublic class ViaSub {\n  a.ApiSub.Inner v;\n}\n");
    final String c19 =
        "javac C19: a.ApiSub.Inner é o membro herdado, binário a.Base$Inner (nunca"
            + " o fantasma a.ApiSub$Inner)";
    casos.add(
        trecho("C19 membro pelo nome da subclasse", Layout.TWO_ROOTS, apiSub, c19app, c19)
            .com(
                "a.ApiSub -> a.Base EXTENDS x1 RESOLVED",
                "b.ViaSub -> a.Base$Inner FIELD_TYPE x1 INFERRED"));
    casos.add(
        trecho("C19 membro pelo nome da subclasse", Layout.ONE_ROOT, apiSub, c19app, c19)
            .com(
                "a.ApiSub -> a.Base EXTENDS x1 RESOLVED",
                "b.ViaSub -> a.Base$Inner FIELD_TYPE x1 RESOLVED"));
    return casos;
  }

  private static List<Trecho> chamadas() {
    final List<Trecho> casos = new ArrayList<>();
    final Map<String, String> c21core =
        Map.of(
            "a/UBase.java",
            "package a;\n\npublic class UBase {\n  public static void stBase() {}\n}\n",
            "a/Util.java",
            "package a;\n\npublic class Util extends UBase {\n  public static void st2() {}\n}\n");
    final Map<String, String> c21app =
        Map.of(
            "b/User.java",
            """
            package b;

            import static a.Util.*;

            public class User {
              void f() {
                st2();
                stBase();
              }
            }
            """);
    final String c21 =
        "javac C21/C21b: nenhum tipo da cadeia tem o método → o estático sob demanda cujo D"
            + " não é NÃO, a.Util, também para o stBase herdado (JLS 13.1); a contagem por ocorrência conta x2 duas vezes";
    casos.add(
        trecho("C21 estático sob demanda", Layout.TWO_ROOTS, c21core, c21app, c21)
            .com(
                "a.Util -> a.UBase EXTENDS x1 RESOLVED",
                "b.User -> a.Util IMPORTS x1 INFERRED",
                "b.User -> a.Util CALLS x2 INFERRED")
            .contando(1, 3, 0, 0));
    casos.add(
        trecho("C21 estático sob demanda", Layout.ONE_ROOT, c21core, c21app, c21)
            .com(
                "a.Util -> a.UBase EXTENDS x1 RESOLVED",
                "b.User -> a.Util IMPORTS x1 RESOLVED",
                "b.User -> a.Util CALLS x2 RESOLVED")
            .contando(4, 0, 0, 0));
    final Map<String, String> c22core =
        Map.of(
            "a/Base.java", "package a;\n\npublic class Base {\n  public void inherited() {}\n}\n");
    final Map<String, String> c22app =
        Map.of(
            "b/Sub10.java",
            """
            package b;

            public class Sub10 extends a.Base {
              class In {
                void g() {
                  inherited();
                }
              }

              class InExt extends ext.Lib {
                void g() {
                  inherited();
                }
              }

              void h() {
                new Runnable() {
                  public void run() {
                    inherited();
                  }
                };
              }
            }
            """);
    final String c22 =
        "javac C22/C22b/C23: o primeiro tipo da cadeia com D = SIM é b.Sub10, de In,"
            + " da anônima (Runnable do JDK sem membros) e de InExt (ext.Lib externo conta como NÃO)";
    casos.add(
        trecho("C22 herdado do tipo de fora", Layout.TWO_ROOTS, c22core, c22app, c22)
            .com(
                "b.Sub10 -> a.Base EXTENDS x1 INFERRED",
                "b.Sub10 -> b.Sub10 CALLS x1 RESOLVED",
                "b.Sub10 -> java.lang.Runnable CALLS x1 RESOLVED external",
                "b.Sub10$In -> b.Sub10 CALLS x1 RESOLVED",
                "b.Sub10$InExt -> b.Sub10 CALLS x1 RESOLVED",
                "b.Sub10$InExt -> ext.Lib EXTENDS x1 UNRESOLVED_EXTERNAL external"));
    casos.add(
        trecho("C22 herdado do tipo de fora", Layout.ONE_ROOT, c22core, c22app, c22)
            .com(
                "b.Sub10 -> a.Base EXTENDS x1 RESOLVED",
                "b.Sub10 -> b.Sub10 CALLS x1 RESOLVED",
                "b.Sub10 -> java.lang.Runnable CALLS x1 RESOLVED external",
                "b.Sub10$In -> b.Sub10 CALLS x1 RESOLVED",
                "b.Sub10$InExt -> b.Sub10 CALLS x1 RESOLVED",
                "b.Sub10$InExt -> ext.Lib EXTENDS x1 UNRESOLVED_EXTERNAL external"));
    final Map<String, String> c25core =
        Map.of(
            "a/BaseTest.java",
            "package a;\n\npublic class BaseTest {\n  public static void assertThat(int value) {}\n}\n");
    final Map<String, String> c25app =
        Map.of(
            "b/T2.java",
            """
            package b;

            import static ext.Assertions.assertThat;

            public class T2 extends a.BaseTest {
              void t() {
                assertThat(1);
              }
            }
            """);
    final String c25 =
        "javac C25, JLS 6.4.1 e 13.1: o assertThat herdado esconde o import estático e se"
            + " qualifica pelo tipo que o herda, b.T2, o estático como o de instância";
    casos.add(
        trecho("C25 estático herdado sobre import", Layout.TWO_ROOTS, c25core, c25app, c25)
            .com(
                "b.T2 -> a.BaseTest EXTENDS x1 INFERRED",
                "b.T2 -> b.T2 CALLS x1 RESOLVED",
                "b.T2 -> ext.Assertions IMPORTS x1 UNRESOLVED_EXTERNAL external"));
    casos.add(
        trecho("C25 estático herdado sobre import", Layout.ONE_ROOT, c25core, c25app, c25)
            .com(
                "b.T2 -> a.BaseTest EXTENDS x1 RESOLVED",
                "b.T2 -> b.T2 CALLS x1 RESOLVED",
                "b.T2 -> ext.Assertions IMPORTS x1 UNRESOLVED_EXTERNAL external"));
    final Map<String, String> c26app =
        Map.of(
            "b/T3.java",
            """
            package b;

            import static ext.Assertions.assertThat;

            public class T3 extends ext.LibTest {
              void t() {
                assertThat(1);
              }
            }
            """);
    casos.add(
        trecho(
                "C26 supertipo externo e import estático",
                Layout.ONE_ROOT,
                Map.of(),
                c26app,
                "o supertipo externo não tem membros que o índice veja → o import"
                    + " estático decide; o javac diz b.T3, um desvio deliberado")
            .com(
                "b.T3 -> ext.Assertions IMPORTS x1 UNRESOLVED_EXTERNAL external",
                "b.T3 -> ext.Assertions CALLS x1 UNRESOLVED_EXTERNAL external",
                "b.T3 -> ext.LibTest EXTENDS x1 UNRESOLVED_EXTERNAL external"));
    final Map<String, String> c28core =
        Map.of(
            "a/Late.java",
            """
            package a;

            public class Late {
              public static final Runnable CONST = null;
              public static final java.util.List<String> NAMES = null;

              public static class Inner {
                public static void go() {}
              }
            }
            """,
            "a/LateSub.java",
            "package a;\n\npublic class LateSub extends Late {}\n",
            "a/Color.java",
            """
            package a;

            public enum Color {
              RED;

              public String label() {
                return "r";
              }
            }
            """,
            "k/Holder.java",
            "package k;\n\npublic class Holder {\n  public static final k.Kconst KC = null;\n}\n",
            "k/Kconst.java",
            "package k;\n\npublic class Kconst {\n  public void touch() {}\n}\n");
    final Map<String, String> c28app =
        Map.of(
            "b/U2.java",
            """
            package b;

            import a.Color;
            import a.Late;
            import a.LateSub;

            public class U2 {
              void f() {
                Late.CONST.run();
                LateSub.CONST.run();
                Late.NAMES.size();
                Color.RED.label();
                Late.Inner.go();
                k.Holder.KC.touch();
              }
            }
            """);
    final String c28 =
        "javac C28 a C31c: f campo de T (herdado por LateSub; RED é campo do enum) → o tipo"
            + " escrito na unidade que o declara; Inner tipo membro de Late → a.Late$Inner;"
            + " nenhum descarte";
    casos.add(
        trecho("C28 receptor T.f", Layout.TWO_ROOTS, c28core, c28app, c28)
            .com(
                "a.Color -> java.lang.String RETURN_TYPE x1 RESOLVED external",
                "a.Late -> java.lang.Runnable FIELD_TYPE x1 RESOLVED external",
                "a.Late -> java.lang.String FIELD_TYPE x1 RESOLVED external",
                "a.Late -> java.util.List FIELD_TYPE x1 RESOLVED external",
                "a.LateSub -> a.Late EXTENDS x1 RESOLVED",
                "b.U2 -> a.Color IMPORTS x1 INFERRED",
                "b.U2 -> a.Color CALLS x1 INFERRED",
                "b.U2 -> a.Late IMPORTS x1 INFERRED",
                "b.U2 -> a.Late$Inner CALLS x1 INFERRED",
                "b.U2 -> a.LateSub IMPORTS x1 INFERRED",
                "b.U2 -> java.lang.Runnable CALLS x2 RESOLVED external",
                "b.U2 -> java.util.List CALLS x1 RESOLVED external",
                "b.U2 -> k.Kconst CALLS x1 INFERRED",
                "k.Holder -> k.Kconst FIELD_TYPE x1 RESOLVED"));
    casos.add(
        trecho("C28 receptor T.f", Layout.ONE_ROOT, c28core, c28app, c28)
            .com(
                "a.Color -> java.lang.String RETURN_TYPE x1 RESOLVED external",
                "a.Late -> java.lang.Runnable FIELD_TYPE x1 RESOLVED external",
                "a.Late -> java.lang.String FIELD_TYPE x1 RESOLVED external",
                "a.Late -> java.util.List FIELD_TYPE x1 RESOLVED external",
                "a.LateSub -> a.Late EXTENDS x1 RESOLVED",
                "b.U2 -> a.Color IMPORTS x1 RESOLVED",
                "b.U2 -> a.Color CALLS x1 RESOLVED",
                "b.U2 -> a.Late IMPORTS x1 RESOLVED",
                "b.U2 -> a.Late$Inner CALLS x1 RESOLVED",
                "b.U2 -> a.LateSub IMPORTS x1 RESOLVED",
                "b.U2 -> java.lang.Runnable CALLS x2 RESOLVED external",
                "b.U2 -> java.util.List CALLS x1 RESOLVED external",
                "b.U2 -> k.Kconst CALLS x1 RESOLVED",
                "k.Holder -> k.Kconst FIELD_TYPE x1 RESOLVED"));
    final Map<String, String> c32core =
        Map.of(
            "a/Base.java",
            "package a;\n\npublic class Base {\n  protected Status status;\n}\n",
            "a/Status.java",
            """
            package a;

            public class Status {
              public boolean isOk() {
                return true;
              }
            }
            """);
    final Map<String, String> c32app =
        Map.of(
            "b/V1.java",
            """
            package b;

            public class V1 extends a.Base {
              void f() {
                status.isOk();
              }
            }
            """,
            "b/V3.java",
            """
            package b;

            public class V3 {
              static class In extends a.Base {
                void f() {
                  status.isOk();
                }
              }
            }
            """);
    final String c32 =
        "javac C32/C35: a leitura implícita de um campo herdado de fora do lote → D acha o"
            + " campo, e o alvo é o tipo declarado dele, a.Status (nunca a linha para si)";
    casos.add(
        trecho("C32 campo herdado como receptor", Layout.TWO_ROOTS, c32core, c32app, c32)
            .com(
                "a.Base -> a.Status FIELD_TYPE x1 RESOLVED",
                "b.V1 -> a.Base EXTENDS x1 INFERRED",
                "b.V1 -> a.Status CALLS x1 INFERRED",
                "b.V3$In -> a.Base EXTENDS x1 INFERRED",
                "b.V3$In -> a.Status CALLS x1 INFERRED"));
    casos.add(
        trecho("C32 campo herdado como receptor", Layout.ONE_ROOT, c32core, c32app, c32)
            .com(
                "a.Base -> a.Status FIELD_TYPE x1 RESOLVED",
                "b.V1 -> a.Base EXTENDS x1 RESOLVED",
                "b.V1 -> a.Status CALLS x1 RESOLVED",
                "b.V3$In -> a.Base EXTENDS x1 RESOLVED",
                "b.V3$In -> a.Status CALLS x1 RESOLVED"));
    final Map<String, String> c33app =
        Map.of(
            "b/P1.java",
            """
            package b;

            public class P1 extends ext.Parser {
              void f() {
                _errHandler.reset();
              }
            }
            """);
    casos.add(
        trecho(
                "C33 campo de supertipo externo",
                Layout.ONE_ROOT,
                Map.of(),
                c33app,
                "D não acha o campo atrás de um supertipo externo (UNKNOWN) → fica como está, a"
                    + " linha para si mesma; o javac diria ext.ErrHandler")
            .com(
                "b.P1 -> b.P1 CALLS x1 RESOLVED",
                "b.P1 -> ext.Parser EXTENDS x1 UNRESOLVED_EXTERNAL external"));
    final Map<String, String> c34app =
        Map.of(
            "b/V2.java",
            """
            package b;

            import static ext.Consts.status2;

            public class V2 {
              void f() {
                status2.isOk();
              }
            }
            """);
    casos.add(
        trecho(
                "C34 campo estático externo",
                Layout.ONE_ROOT,
                Map.of(),
                c34app,
                "T.f com T externo → descartado e contado (indecidível sem a biblioteca)")
            .com("b.V2 -> ext.Consts IMPORTS x1 UNRESOLVED_EXTERNAL external")
            .descartando(DropReason.CALL_WITHOUT_TARGET, 1));
    casos.addAll(receptoresDeVariavel());
    return casos;
  }

  private static List<Trecho> receptoresDeVariavel() {
    final Map<String, String> lambda =
        Map.of(
            "p/Lam.java",
            """
            package p;

            public class Lam {
              void m(org.ext.Mapper mapper) {
                mapper.mapping(b -> b.field("x"));
              }
            }
            """);
    final Map<String, String> foo =
        Map.of("a/Foo.java", "package a;\n\npublic class Foo {\n  public void go() {}\n}\n");
    final Map<String, String> typedVar =
        Map.of(
            "b/User.java",
            """
            package b;

            import a.Foo;

            public class User {
              void m(org.ext.Missing missing) {
                missing.run(
                    () -> {
                      var foo = new Foo();
                      foo.go();
                    });
              }
            }
            """);
    final String typed =
        "o var literal se qualifica pelo tipo do inicializador,"
            + " new Foo() → a.Foo (JLS 14.4.1); nunca uma linha type:var";
    final Map<String, String> untypedVar =
        Map.of(
            "b/User.java",
            """
            package b;

            public class User {
              void m(org.ext.Missing missing) {
                missing.run(
                    () -> {
                      for (var each : missing.all()) {
                        each.go();
                      }
                    });
              }
            }
            """);
    return List.of(
        trecho(
                "parâmetro de lambda sem tipo",
                Layout.ONE_ROOT,
                Map.of(),
                lambda,
                "o receptor b sem tipo nunca cai no tipo declarante do executável (o nome da"
                    + " variável): nenhuma linha type:b, +1 CALL_WITHOUT_TARGET")
            .com(
                "p.Lam -> org.ext.Mapper CALLS x1 UNRESOLVED_EXTERNAL external",
                "p.Lam -> org.ext.Mapper PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external")
            .descartando(DropReason.CALL_WITHOUT_TARGET, 1),
        trecho("var com inicializador tipado", Layout.TWO_ROOTS, foo, typedVar, typed)
            .com(
                "b.User -> a.Foo IMPORTS x1 INFERRED",
                "b.User -> a.Foo CALLS x2 INFERRED",
                "b.User -> org.ext.Missing CALLS x1 UNRESOLVED_EXTERNAL external",
                "b.User -> org.ext.Missing PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho("var com inicializador tipado", Layout.ONE_ROOT, foo, typedVar, typed)
            .com(
                "b.User -> a.Foo IMPORTS x1 RESOLVED",
                "b.User -> a.Foo CALLS x2 RESOLVED",
                "b.User -> org.ext.Missing CALLS x1 UNRESOLVED_EXTERNAL external",
                "b.User -> org.ext.Missing PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho(
                "var sem inicializador tipado",
                Layout.ONE_ROOT,
                Map.of(),
                untypedVar,
                "o var do for aprimorado não tem inicializador → desconhecido, +1"
                    + " CALL_WITHOUT_TARGET, nunca type:var; o missing capturado no lambda sem tipo"
                    + " é parâmetro, não campo → +1, nunca type:missing")
            .com(
                "b.User -> org.ext.Missing CALLS x1 UNRESOLVED_EXTERNAL external",
                "b.User -> org.ext.Missing PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external")
            .descartando(DropReason.CALL_WITHOUT_TARGET, 2));
  }

  private static List<Trecho> nomesQueOSpoonEstraga() {
    final Map<String, String> box =
        Map.of("a/Box.java", "package a;\n\npublic class Box<T> {\n  public Box(T value) {}\n}\n");
    final Map<String, String> diamond =
        Map.of(
            "b/User.java",
            """
            package b;

            import a.Box;
            import java.util.ArrayList;

            public class User {
              void m(org.ext.Missing missing) {
                missing.run(() -> new Box<>("x"));
                missing.run(() -> new ArrayList<>());
              }
            }
            """);
    final String c4 =
        "dentro do lambda sem tipo o Spoon imprime b.Box<> e b.ArrayList<>; tira o <> e"
            + " re-canoniza como nome simples → os imports de tipo único; a.Box é INFERRED também"
            + " em uma raiz (o nome canônico não é o que o Spoon imprimiu)";
    final Map<String, String> dotDot =
        Map.of(
            "p/User.java",
            "package p;\n\npublic class User {\n  jdk.internal.misc.Unsafe unsafe;\n}\n");
    return List.of(
        trecho("C4 palpite com diamante", Layout.TWO_ROOTS, box, diamond, c4)
            .com(
                "b.User -> a.Box IMPORTS x1 INFERRED",
                "b.User -> a.Box CALLS x1 INFERRED",
                "b.User -> java.util.ArrayList IMPORTS x1 RESOLVED external",
                "b.User -> java.util.ArrayList CALLS x1 RESOLVED external",
                "b.User -> org.ext.Missing CALLS x2 UNRESOLVED_EXTERNAL external",
                "b.User -> org.ext.Missing PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho("C4 palpite com diamante", Layout.ONE_ROOT, box, diamond, c4)
            .com(
                "b.User -> a.Box IMPORTS x1 RESOLVED",
                "b.User -> a.Box CALLS x1 INFERRED",
                "b.User -> java.util.ArrayList IMPORTS x1 RESOLVED external",
                "b.User -> java.util.ArrayList CALLS x1 RESOLVED external",
                "b.User -> org.ext.Missing CALLS x2 UNRESOLVED_EXTERNAL external",
                "b.User -> org.ext.Missing PARAMETER_TYPE x1 UNRESOLVED_EXTERNAL external"),
        trecho(
                "C5 nome com ponto duplo",
                Layout.ONE_ROOT,
                Map.of(),
                dotDot,
                "jdk.internal.misc..Unsafe escrito por extenso vira jdk.internal.misc.Unsafe;"
                    + " o pacote não é exportado → (U,1), nunca INVALID_NAME")
            .com("p.User -> jdk.internal.misc.Unsafe FIELD_TYPE x1 UNRESOLVED_EXTERNAL external"));
  }

  @Test
  void oMissingClasspathReproduzOGoldenExatamente() throws Exception {
    final Extraction run =
        ResolutionHarness.extract(
            SourceTrees.fixture("missing-classpath", temp.toRealPath().resolve("fixture")));

    assertThat(run.roots()).as("pré-condição: duas raízes MAIN").hasSize(2);
    assertThat(run.sink().tuples())
        .as("dependencies do golden, exatas, com o ambiguous")
        .containsExactlyElementsOf(ResolutionHarness.goldenTuples("missing-classpath"));
    assertThat(
            run.sink().accepted().stream()
                .flatMap(batch -> batch.elements().types().stream())
                .map(type -> type.id().value())
                .sorted(CodeElementId::compareCodePoints)
                .toList())
        .containsExactlyElementsOf(ResolutionHarness.goldenTypes("missing-classpath"));
    assertThat(run.drops()).isEmpty();

    final ExternalPackages packages = ExternalPackages.load();
    assertThat(
            run.sink().rows().stream()
                .filter(
                    row -> row.dependency().source().sortKey().equals("com.acme.plugin.spi.Probe"))
                .filter(row -> !row.targetDeclared())
                .map(row -> row.dependency().target())
                .filter(target -> !packages.isDenylisted(target))
                .map(CodeElementId::value)
                .distinct()
                .toList())
        .as("os alvos externos de Probe que contam no fanOutExternal")
        .containsExactly("type:Config", "type:com.thirdparty.Missing");

    final ResolutionCounts counts = run.extractor().resolutionCounts();
    assertThat(counts).isEqualTo(new ResolutionCounts(1, 4, 3, 1));
    assertThat(counts.rate()).hasValue(0.125);
  }

  @Test
  void aRazaoDeUmTrechoTodoResolvidoEUm() throws Exception {
    final Extraction run =
        ResolutionHarness.extract(
            temp.resolve("s10"),
            Layout.ONE_ROOT,
            Map.of(),
            Map.of(
                "p/User.java",
                """
                package p;

                import java.util.List;
                import java.util.concurrent.Callable;

                public class User {
                  List<Callable<Integer>> jobs;
                }
                """));

    final ResolutionCounts counts = run.extractor().resolutionCounts();
    final long occurrences =
        run.sink().rows().stream().mapToLong(row -> row.dependency().occurrences()).sum();
    assertThat(counts.resolved() + counts.inferred() + counts.unresolved()).isEqualTo(occurrences);
    assertThat(counts.rate()).hasValue(1.0);
  }

  @Test
  void oToMethodEODoCalleeDoLoteENuloEntreLotes() throws Exception {
    final Map<String, String> engine =
        Map.of(
            "p/Engine.java", "package p;\n\npublic class Engine {\n  public void start() {}\n}\n");
    final Map<String, String> car =
        Map.of(
            "p/Car.java",
            """
            package p;

            public class Car {
              Engine engine;

              void go() {
                engine.start();
              }
            }
            """);

    final Extraction oneRoot =
        ResolutionHarness.extract(temp.resolve("uma"), Layout.ONE_ROOT, engine, car);
    final Extraction twoRoots =
        ResolutionHarness.extract(temp.resolve("duas"), Layout.TWO_ROOTS, engine, car);

    assertThat(oneRoot.sink().tuples())
        .containsExactly(
            "p.Car -> p.Engine CALLS x1 RESOLVED", "p.Car -> p.Engine FIELD_TYPE x1 RESOLVED");
    assertThat(evidence(oneRoot.sink().row("p.Car", "p.Engine", "CALLS")))
        .as("o callee no lote")
        .containsExactly("L7 method:p.Car#go() toMethod=method:p.Engine#start()");
    assertThat(twoRoots.sink().tuples())
        .containsExactly(
            "p.Car -> p.Engine CALLS x1 INFERRED", "p.Car -> p.Engine FIELD_TYPE x1 INFERRED");
    assertThat(evidence(twoRoots.sink().row("p.Car", "p.Engine", "CALLS")))
        .as("o callee em outro lote")
        .containsExactly("L7 method:p.Car#go()");
  }

  @Test
  void oCalleeHerdadoEDoTipoQueODeclara() throws Exception {
    final Extraction run =
        ResolutionHarness.extract(
            temp.resolve("herdado"),
            Layout.ONE_ROOT,
            Map.of("p/Base.java", "package p;\n\npublic class Base {\n  public void far() {}\n}\n"),
            Map.of(
                "p/Sub.java",
                """
                package p;

                public class Sub extends Base {
                  void near() {
                    far();
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly("p.Sub -> p.Base EXTENDS x1 RESOLVED", "p.Sub -> p.Sub CALLS x1 RESOLVED");
    assertThat(evidence(run.sink().row("p.Sub", "p.Sub", "CALLS")))
        .containsExactly("L5 method:p.Sub#near() toMethod=method:p.Base#far()");
  }

  @Test
  void aSobrecargaComTipoForaDoLoteDaNulo() throws Exception {
    final Extraction run =
        ResolutionHarness.extract(
            temp.resolve("sobrecarga"),
            Layout.TWO_ROOTS,
            Map.of("a/Key.java", "package a;\n\npublic class Key {}\n"),
            Map.of(
                "b/Store.java",
                """
                package b;

                public class Store {
                  public void put(String s) {}

                  public void put(a.Key k) {}

                  public void log(String s) {}

                  public void log(int n) {}
                }
                """,
                "b/Client.java",
                """
                package b;

                public class Client {
                  Store store;

                  void m(a.Key key) {
                    store.put(key);
                    store.log("x");
                  }
                }
                """));

    assertThat(run.sink().tuples())
        .containsExactly(
            "b.Client -> a.Key PARAMETER_TYPE x1 INFERRED",
            "b.Client -> b.Store CALLS x2 RESOLVED",
            "b.Client -> b.Store FIELD_TYPE x1 RESOLVED",
            "b.Store -> a.Key PARAMETER_TYPE x1 INFERRED",
            "b.Store -> java.lang.String PARAMETER_TYPE x2 RESOLVED external");
    assertThat(evidence(run.sink().row("b.Client", "b.Store", "CALLS")))
        .containsExactly(
            "L7 method:b.Client#m(a.Key)",
            "L8 method:b.Client#m(a.Key) toMethod=method:b.Store#log(java.lang.String)");
  }

  @Test
  void oValuesImplicitoDeUmEnumDaToMethodNulo() throws Exception {
    final Extraction run =
        ResolutionHarness.extract(
            temp.resolve("enum"),
            Layout.ONE_ROOT,
            Map.of("p/Color.java", "package p;\n\npublic enum Color {\n  RED\n}\n"),
            Map.of(
                "p/User.java",
                """
                package p;

                public class User {
                  void m() {
                    Color.values();
                  }
                }
                """));

    assertThat(run.sink().tuples()).containsExactly("p.User -> p.Color CALLS x1 RESOLVED");
    final ExtractedDependency call = run.sink().row("p.User", "p.Color", "CALLS");
    assertThat(evidence(call)).containsExactly("L5 method:p.User#m()");
  }

  private static final String API = "package a;\n\npublic class Api {}\n";

  private static final String REGISTRY = "package a;\n\npublic class Registry {}\n";

  private static final String CONFIG_IN_C = "package c;\n\npublic class Config {}\n";

  private static final String CONFIG_IN_D = "package d;\n\npublic class Config {}\n";

  private static final String BASE_INNER =
      """
      package a;

      public class Base {
        public static class Inner {}
      }
      """;

  private static final String API_INNER =
      """
      package a;

      public class Api {
        public static class Inner {}
      }
      """;

  private static final String API_SUB = "package a;\n\npublic class ApiSub extends Base {}\n";

  private static final Map<String, String> PROBE_REGISTRY =
      Map.of(
          "c/Probe.java",
          """
          package c;

          public class Probe {
            Registry registry;
          }
          """);

  private static final String PROBE_CONFIG =
      """
      package e;

      public class Probe {
        Config config;
      }
      """;

  private static Trecho trecho(
      String nome,
      Layout layout,
      Map<String, String> core,
      Map<String, String> app,
      String derivacao) {
    return new Trecho(nome, layout, core, app, List.of(), Map.of(), null, derivacao);
  }

  record Trecho(
      String nome,
      Layout layout,
      Map<String, String> core,
      Map<String, String> app,
      List<String> tuplas,
      Map<DropReason, Long> descartes,
      ResolutionCounts contagens,
      String derivacao) {

    Trecho com(String... esperadas) {
      return new Trecho(
          nome, layout, core, app, List.of(esperadas), descartes, contagens, derivacao);
    }

    Trecho descartando(DropReason motivo, long quantas) {
      final Map<DropReason, Long> todos = new EnumMap<>(DropReason.class);
      todos.putAll(descartes);
      todos.put(motivo, quantas);
      return new Trecho(nome, layout, core, app, tuplas, Map.copyOf(todos), contagens, derivacao);
    }

    Trecho contando(long resolved, long inferred, long unresolved, long ambiguous) {
      return new Trecho(
          nome,
          layout,
          core,
          app,
          tuplas,
          descartes,
          new ResolutionCounts(resolved, inferred, unresolved, ambiguous),
          derivacao);
    }

    @Override
    public String toString() {
      return nome + ", " + layout;
    }
  }
}
