package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.RecordingElementSink.ids;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Rule7RegressionTest {

  private static final List<SourceRootKind> VISIT_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  private static final String MAIN = "src/main/java/";
  private static final String TEST = "src/test/java/";

  @TempDir private Path temp;

  @Test
  void umTipoDoProprioPacoteSombreiaJavaLang() throws Exception {
    assertSameMethodIds(
        Map.of("p/Record.java", "package p;\n\npublic class Record {}\n"),
        "p/User.java",
        """
        package p;

        public class User {
          void m(Record r) {}

          <T extends Record> void bounded(T t) {}
        }
        """,
        List.of(
            "method:p.Record#<init>()",
            "method:p.User#<init>()",
            "method:p.User#bounded(p.Record)",
            "method:p.User#m(p.Record)"));
  }

  @Test
  void umTipoDoProprioPacoteSombreiaUmImportSobDemanda() throws Exception {
    assertSameMethodIds(
        Map.of("p/List.java", "package p;\n\npublic class List {}\n"),
        "p/User.java",
        """
        package p;

        import java.util.*;

        public class User {
          void m(List l) {}
        }
        """,
        List.of("method:p.List#<init>()", "method:p.User#<init>()", "method:p.User#m(p.List)"));
  }

  @Test
  void umLimiteComPontosResolveOPrimeiroSegmentoPeloImportSobDemanda() throws Exception {
    assertSameMethodIds(
        Map.of("a/Api.java", API_IN_A),
        "b/User.java",
        """
        package b;

        import a.*;

        public class User {
          <T extends Api.Inner> void m(T t) {}
        }
        """,
        List.of(
            "method:a.Api#<init>()",
            "method:a.Api$Inner#<init>()",
            "method:b.User#<init>()",
            "method:b.User#m(a.Api$Inner)"));
  }

  @Test
  void umLimiteComPontosResolveOPrimeiroSegmentoNoProprioPacote() throws Exception {
    assertSameMethodIds(
        Map.of(
            "b/Api.java",
            """
            package b;

            public class Api {
              public static class Inner {}
            }
            """),
        "b/User.java",
        """
        package b;

        public class User {
          <T extends Api.Inner> void m(T t) {}
        }
        """,
        List.of(
            "method:b.Api#<init>()",
            "method:b.Api$Inner#<init>()",
            "method:b.User#<init>()",
            "method:b.User#m(b.Api$Inner)"));
  }

  @Test
  void umNomeComPontosResolveOPrimeiroSegmentoPeloImportDeTipoUnico() throws Exception {
    assertSameMethodIds(
        Map.of("a/Api.java", API_IN_A),
        "b/User.java",
        """
        package b;

        import a.Api;

        public class User {
          void p(Api.Inner inner) {}

          <T extends Api.Inner> void q(T t) {}
        }
        """,
        List.of(
            "method:a.Api#<init>()",
            "method:a.Api$Inner#<init>()",
            "method:b.User#<init>()",
            "method:b.User#p(a.Api$Inner)",
            "method:b.User#q(a.Api$Inner)"));
  }

  @Test
  void umImportDeTipoUnicoDeUmTipoInternoVenceJavaLang() throws Exception {
    assertSameMethodIds(
        Map.of("a/Record.java", "package a;\n\npublic class Record {}\n"),
        "p/User.java",
        """
        package p;

        import a.Record;

        public class User {
          void m(Record r) {}
        }
        """,
        List.of("method:a.Record#<init>()", "method:p.User#<init>()", "method:p.User#m(a.Record)"));
  }

  @Test
  void umImportDeTipoUnicoSombreiaOProprioPacote() throws Exception {
    assertSameMethodIds(
        Map.of(
            "a/Record.java", "package a;\n\npublic class Record {}\n",
            "p/Record.java", "package p;\n\npublic class Record {}\n"),
        "p/User.java",
        """
        package p;

        import a.Record;

        public class User {
          void m(Record r) {}
        }
        """,
        List.of(
            "method:a.Record#<init>()",
            "method:p.Record#<init>()",
            "method:p.User#<init>()",
            "method:p.User#m(a.Record)"));
  }

  @Test
  void umImportDeTipoUnicoDeUmTipoExternoTambemSombreiaOProprioPacote() throws Exception {
    assertSameMethodIds(
        Map.of("p/List.java", "package p;\n\npublic class List {}\n"),
        "p/User.java",
        """
        package p;

        import java.util.List;

        public class User {
          void m(List l) {}
        }
        """,
        List.of(
            "method:p.List#<init>()", "method:p.User#<init>()", "method:p.User#m(java.util.List)"));
  }

  @Test
  void umNomeEscritoPorExtensoNaoCedeAoProprioPacote() throws Exception {
    assertSameMethodIds(
        Map.of("p/Record.java", "package p;\n\npublic class Record {}\n"),
        "p/User.java",
        """
        package p;

        public class User {
          void m(java.lang.Record r) {}
        }
        """,
        List.of(
            "method:p.Record#<init>()",
            "method:p.User#<init>()",
            "method:p.User#m(java.lang.Record)"));
  }

  @Test
  void umTipoMembroComONomeDeUmTipoDoProprioPacoteNaoMuda() throws Exception {
    assertSameMethodIds(
        Map.of(
            "p/List.java", "package p;\n\npublic class List {}\n",
            "p/Entry.java", "package p;\n\npublic class Entry {}\n"),
        "p/User.java",
        """
        package p;

        import java.util.Map;

        public class User {
          static class List {}

          void m(List l) {}

          void e(Map.Entry e) {}
        }
        """,
        List.of(
            "method:p.Entry#<init>()",
            "method:p.List#<init>()",
            "method:p.User#<init>()",
            "method:p.User#e(java.util.Map$Entry)",
            "method:p.User#m(p.User$List)",
            "method:p.User$List#<init>()"));
  }

  private static final String API_IN_A =
      """
      package a;

      public class Api {
        public static class Inner {}
      }
      """;

  private void assertSameMethodIds(
      Map<String, String> declarations, String userPath, String user, List<String> expected)
      throws Exception {
    final Map<String, String> oneRoot = new LinkedHashMap<>();
    final Map<String, String> twoRoots = new LinkedHashMap<>();
    declarations.forEach(
        (path, source) -> {
          oneRoot.put(MAIN + path, source);
          twoRoots.put(MAIN + path, source);
        });
    oneRoot.put(MAIN + userPath, user);
    twoRoots.put(TEST + userPath, user);

    final List<String> inOneRoot = methodIds(temp.resolve("one-root"), oneRoot, 1);
    final List<String> inTwoRoots = methodIds(temp.resolve("two-roots"), twoRoots, 2);

    assertThat(inOneRoot).as("uma raiz").containsExactlyElementsOf(expected);
    assertThat(inTwoRoots).as("duas raízes").containsExactlyElementsOf(expected);
  }

  private static List<String> methodIds(Path directory, Map<String, String> files, int rootCount)
      throws Exception {
    final ValidatedRepository repository = SourceTrees.repository(directory, files);
    final List<SourceRoot> roots =
        repository.layout().sourceRoots().stream()
            .sorted(Comparator.comparingInt(root -> VISIT_ORDER.indexOf(root.kind())))
            .toList();
    assertThat(roots).as("pré-condição: as raízes").hasSize(rootCount);
    final RecordingElementSink sink = new RecordingElementSink();

    new SpoonModelBuilder()
        .analyze(repository, roots, new CodeElementExtractor(roots, sink), SourceTrees.NEVER);

    return ids(sink.methods());
  }
}
