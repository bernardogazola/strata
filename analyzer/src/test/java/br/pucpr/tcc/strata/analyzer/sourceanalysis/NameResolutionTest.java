package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.FieldLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberAnswer;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberTypeLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.NameContext;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.NameResolution;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.Outcome;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.WrittenName;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.experimental.CtUnresolvedImport;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtType;

final class NameResolutionTest {

  private static final Fixture ORACLE = Fixture.of(oracleSources());
  private static final SimpleNameIndex INDEX = ORACLE.index();

  private static final List<SourceRootKind> VISIT_ORDER =
      List.of(SourceRootKind.MAIN, SourceRootKind.GENERATED, SourceRootKind.TEST);

  @TempDir private Path temp;

  @Test
  void umTipoMembroHerdadoEColocadoPelaSuperclasseDiretaOuIndireta() {
    assertPlaced(written("b/Sub.java", "Inner", "b.Sub"), "a.Base$Inner");
    assertPlaced(written("b/Sub2.java", "Cb", "b.Sub2"), "a.Base$Cb");
    assertPlaced(written("b/Outer.java", "Inner", "b.Outer$Deep", "b.Outer"), "a.Base$Inner");
  }

  @Test
  void umTipoMembroHerdadoDeUmaInterfaceEColocado() {
    assertPlaced(written("b/Impl.java", "Kind", "b.Impl"), "a.HasKinds$Kind");
  }

  @Test
  void oMembroDeclaradoSombreiaOHerdado() {
    assertPlaced(written("b/Sub3.java", "Inner", "b.Sub3"), "b.Sub3$Inner");
  }

  @Test
  void oMembroHerdadoSombreiaOImportDeTipoUnico() {
    assertPlaced(written("b/Sub4.java", "Inner", "b.Sub4"), "a.Base$Inner");
  }

  @Test
  void umMembroPrivadoOuDePacoteDeOutroPacoteNaoEHerdadoEOImportVale() {
    assertPlaced(written("b/Sub5.java", "Builder", "b.Sub5"), "c.Builder");
    assertPlaced(written("b/Sub6.java", "Entry", "b.Sub6"), "c.Entry");
  }

  @Test
  void doisMembrosHerdadosDistintosSaoAmbiguosENuncaAdivinhados() {
    final NameResolution resolution = written("b/Both.java", "Node", "b.Both");

    assertThat(resolution.outcome()).isEqualTo(Outcome.AMBIGUOUS);
    assertThat(resolution.name()).as("o nome do Spoon fica").isEqualTo("b.Node");
    assertThat(INDEX.memberType("b.Both", "Node"))
        .isEqualTo(new MemberTypeLookup(MemberAnswer.UNKNOWN, null, true));
  }

  @Test
  void umSupertipoDoJdkNaoDaMembroETornaOEscopoIncompleto() {
    final NameResolution entry =
        scope("b/MyMap.java").resolve("java.util.Map$Entry", false, in("b.MyMap"));
    final NameResolution string =
        scope("b/MyMap.java").resolve("java.lang.String", true, in("b.MyMap"));

    assertThat(entry)
        .isEqualTo(new NameResolution("java.util.Map$Entry", Outcome.UNCHANGED_INCOMPLETE));
    assertThat(string).isEqualTo(new NameResolution("java.lang.String", Outcome.PLACED));
    assertThat(INDEX.memberType("b.MyMap", "Entry").answer()).isEqualTo(MemberAnswer.UNKNOWN);
  }

  @Test
  void umSupertipoExternoTornaOMembroDesconhecidoENuncaOColoca() {
    final NameResolution resolution = written("b/ExtSub.java", "LibInner", "b.ExtSub");

    assertThat(resolution)
        .isEqualTo(new NameResolution("b.LibInner", Outcome.UNCHANGED_INCOMPLETE));
  }

  @Test
  void umaHerancaCircularTerminaSemMembroEComEscopoCompleto() {
    final NameResolution resolution = written("cyc/Cyc.java", "Inner", "cyc.Cyc");

    assertThat(resolution).isEqualTo(new NameResolution("cyc.Inner", Outcome.UNCHANGED_COMPLETE));
    assertThat(INDEX.hasMethod("cyc.Cyc", "anything")).isEqualTo(MemberAnswer.NO);
  }

  @Test
  void umImportDeTipoSobDemandaTrazSoOsMembrosDeclarados() {
    assertPlaced(written("b/OnDemandType.java", "Inner2", "b.OnDemandType"), "a.Api$Inner2");
    assertPlaced(
        written("b/OnDemandNonStatic.java", "NonStatic", "b.OnDemandNonStatic"), "a.Api$NonStatic");
    assertThat(written("b/OdInh.java", "Inner", "b.OdInh"))
        .isEqualTo(new NameResolution("b.Inner", Outcome.UNCHANGED_COMPLETE));
  }

  @Test
  void umImportEstaticoTrazOsMembrosEstaticosHerdadosInclusive() {
    assertPlaced(written("b/OnDemandStatic.java", "Inner2", "b.OnDemandStatic"), "a.Api$Inner2");
    assertPlaced(
        written("b/OnDemandInherited.java", "Inner", "b.OnDemandInherited"), "a.Base$Inner");
    assertPlaced(written("b/SsInh.java", "Inner", "b.SsInh"), "a.Base$Inner");
  }

  @Test
  void oPrimeiroSegmentoDeUmNomeComPontosVemAntesDoMaiorPrefixoInterno() {
    final UnitScope scope = scope("s/ScriptMeta.java");

    assertPlaced(
        scope.resolve("s.Metadata.ProjectCustom", false, NameContext.NONE),
        "m.Metadata$ProjectCustom");
    assertPlaced(
        scope.resolve("m.Metadata$ProjectCustom", false, NameContext.NONE),
        "m.Metadata$ProjectCustom");
    assertPlaced(scope.resolve("s.Metadata", true, NameContext.NONE), "m.Metadata");
    assertThat(scope.dottedConflicts()).isZero();
  }

  @Test
  void duasLeiturasValidasEDiferentesFicamComAImpressaEContam() {
    final Fixture fixture =
        Fixture.of(
            Map.of(
                "p/Foo.java",
                "package p;\npublic class Foo {}\n",
                "p/Host.java",
                "package p;\npublic class Host { static class Foo {} p.Foo outer; }\n"));
    final UnitScope scope = fixture.scope("p/Host.java");

    assertPlaced(scope.resolve("p.Foo", false, in("p.Host")), "p.Foo");
    assertThat(scope.dottedConflicts()).isEqualTo(1);
  }

  @Test
  void umNomeComPontosSeResolveMembroAMembro() {
    assertPlaced(
        scope("b/Sub8.java").resolve("Inner.Deeper", false, in("b.Sub8")), "a.Base$Inner$Deeper");
    assertPlaced(
        scope("b/ViaSub.java").resolve("a.ApiSub.Inner", false, in("b.ViaSub")), "a.Base$Inner");
    assertPlaced(
        scope("b/ViaSub.java").resolve("a.ApiSub$Inner", false, in("b.ViaSub")), "a.Base$Inner");
  }

  @Test
  void umMembroPrivadoDoProprioTipoEstaEmEscopo() {
    assertPlaced(written("b/Top.java", "Inner", "b.Top"), "b.Top$Inner");
  }

  @Test
  void oCabecalhoDeUmTipoNaoVeOsMembrosDele() {
    final Fixture fixture =
        Fixture.of(
            Map.of(
                "h/Base.java",
                "package h;\npublic class Base { public interface Inner {} }\n",
                "h/C4.java",
                "package h;\npublic class C4 extends Base implements Inner {}\n",
                "h/Outer.java",
                "package h;\npublic class Outer { static class A {} static class B extends A {} }\n"));

    assertThat(fixture.index().hasMethod("h.C4", "x"))
        .as("Inner não colocado: supertipo desconhecido")
        .isEqualTo(MemberAnswer.UNKNOWN);
    assertThat(fixture.index().type("h.C4").supertypes()).containsExactly("h.Base", null);
    assertThat(fixture.index().type("h.Outer$B").supertypes()).containsExactly("h.Outer$A");
  }

  @Test
  void umMetodoEMembroPeloNomeComAHerancaDaJls() {
    assertThat(INDEX.hasMethod("a.Util", "st2")).isEqualTo(MemberAnswer.YES);
    assertThat(INDEX.hasMethod("a.Util", "stBase")).isEqualTo(MemberAnswer.YES);
    assertThat(INDEX.hasMethod("b.User", "st2")).isEqualTo(MemberAnswer.NO);
    assertThat(INDEX.hasMethod("b.Sub10", "inherited")).isEqualTo(MemberAnswer.YES);
    assertThat(INDEX.hasMethod("b.Sub10$In", "inherited")).isEqualTo(MemberAnswer.NO);
    assertThat(INDEX.hasMethod("b.Sub10$InExt", "inherited")).isEqualTo(MemberAnswer.UNKNOWN);
    assertThat(INDEX.hasMethod("b.T2", "assertThat")).isEqualTo(MemberAnswer.YES);
    assertThat(INDEX.hasMethod("b.T3", "assertThat")).isEqualTo(MemberAnswer.UNKNOWN);
    assertThat(INDEX.hasMethod("b.T1", "assertThat")).isEqualTo(MemberAnswer.NO);
  }

  @Test
  void umMetodoEstaticoDeInterfaceUmPrivadoEUmDePacoteDeOutroPacoteNaoSaoHerdados() {
    final Fixture fixture =
        Fixture.of(
            Map.of(
                "d/WithStatic.java",
                "package d;\npublic interface WithStatic { static void helper() {} default void inst() {} }\n",
                "d/PkgBase.java",
                "package d;\npublic class PkgBase { void hidden() {} private void secret() {} protected void open() {} }\n",
                "d/SamePackage.java",
                "package d;\npublic class SamePackage extends PkgBase {}\n",
                "e/Impl.java",
                "package e;\npublic class Impl implements d.WithStatic {}\n",
                "e/Other.java",
                "package e;\npublic class Other extends d.PkgBase {}\n",
                "d/Through.java",
                "package d;\npublic class Through extends e.Other {}\n"));

    assertThat(fixture.index().hasMethod("e.Impl", "helper")).isEqualTo(MemberAnswer.NO);
    assertThat(fixture.index().hasMethod("e.Impl", "inst")).isEqualTo(MemberAnswer.YES);
    assertThat(fixture.index().hasMethod("d.SamePackage", "hidden")).isEqualTo(MemberAnswer.YES);
    assertThat(fixture.index().hasMethod("d.SamePackage", "secret")).isEqualTo(MemberAnswer.NO);
    assertThat(fixture.index().hasMethod("e.Other", "hidden")).isEqualTo(MemberAnswer.NO);
    assertThat(fixture.index().hasMethod("e.Other", "open")).isEqualTo(MemberAnswer.YES);
    assertThat(fixture.index().hasMethod("d.Through", "hidden"))
        .as("e.Other, no caminho, não herda o de pacote de d")
        .isEqualTo(MemberAnswer.NO);
  }

  @Test
  void oTipoDeUmCampoEResolvidoNaUnidadeQueODeclara() {
    assertThat(INDEX.field("a.Late", "CONST"))
        .isEqualTo(new FieldLookup(MemberAnswer.YES, "a.Late", "Runnable"));
    assertThat(INDEX.field("a.LateSub", "CONST"))
        .isEqualTo(new FieldLookup(MemberAnswer.YES, "a.Late", "Runnable"));
    assertThat(INDEX.field("a.Late", "NAMES").typeName()).isEqualTo("java.util.List");
    assertThat(INDEX.field("a.Color", "RED").typeName()).isEqualTo("a.Color");
    assertThat(INDEX.field("k.Holder", "KC").typeName()).isEqualTo("k.Kconst");
    assertThat(INDEX.memberType("a.Late", "Inner"))
        .isEqualTo(new MemberTypeLookup(MemberAnswer.YES, "a.Late$Inner", false));
    assertThat(INDEX.field("a.Late", "Inner").answer()).isEqualTo(MemberAnswer.NO);
  }

  @Test
  void umCampoHerdadoTemOTipoQueODeclaranteEscreveu() {
    assertThat(INDEX.field("b.V1", "status"))
        .isEqualTo(new FieldLookup(MemberAnswer.YES, "a.Base", "a.Status"));
    assertThat(INDEX.field("b.V3$In", "status"))
        .isEqualTo(new FieldLookup(MemberAnswer.YES, "a.Base", "a.Status"));
    assertThat(INDEX.field("b.P1", "_errHandler").answer()).isEqualTo(MemberAnswer.UNKNOWN);
  }

  @Test
  void oTipoDeUmCampoApagaArgumentosGuardaDimensoesESabeAsVariaveisDeTipo() {
    final Fixture fixture =
        Fixture.of(
            Map.of(
                "f/Box.java",
                """
                package f;
                import java.util.Map;
                public class Box<T> {
                  T value;
                  T[] values;
                  int count;
                  long[][] grid;
                  Map.Entry<String, T> entry;
                  Item[] items;
                  Box<String>.Inner inner;
                  class Inner { T inherited; }
                  public record Pair(Item left, int right) {}
                }
                """,
                "f/Item.java",
                "package f;\npublic class Item {}\n"));

    assertThat(fixture.index().field("f.Box", "value").typeName()).isNull();
    assertThat(fixture.index().field("f.Box", "values").typeName()).isNull();
    assertThat(fixture.index().field("f.Box", "count").typeName()).isEqualTo("int");
    assertThat(fixture.index().field("f.Box", "grid").typeName()).isEqualTo("long[][]");
    assertThat(fixture.index().field("f.Box", "entry").typeName()).isEqualTo("java.util.Map$Entry");
    assertThat(fixture.index().field("f.Box", "items").typeName()).isEqualTo("f.Item[]");
    assertThat(fixture.index().field("f.Box", "inner").typeName()).isEqualTo("f.Box$Inner");
    assertThat(fixture.index().field("f.Box$Inner", "inherited").typeName())
        .as("a variável de tipo do tipo de fora")
        .isNull();
    assertThat(fixture.index().field("f.Box$Pair", "left").typeName()).isEqualTo("f.Item");
    assertThat(fixture.index().hasMethod("f.Box$Pair", "left"))
        .as("o acessor implícito")
        .isEqualTo(MemberAnswer.YES);
  }

  @Test
  void oEscopoSoECompletoQuandoOIndiceVeTudoQuePoderiaTrazerONome() {
    final Fixture fixture =
        Fixture.of(
            Map.of(
                "g/Known.java", "package g;\npublic class Known {}\n",
                "g/Open.java", "package g;\npublic class Open extends org.ext.Base {}\n",
                "p/Plain.java", "package p;\nimport g.*;\npublic class Plain {}\n",
                "p/Star.java", "package p;\nimport org.ext.*;\npublic class Star {}\n",
                "p/Static.java",
                    "package p;\nimport static org.ext.Asserts.*;\npublic class Static {}\n",
                "p/Single.java",
                    "package p;\nimport static g.Known.Ghost;\npublic class Single {}\n",
                "p/Module.java", "package p;\nimport module java.base;\npublic class Module {}\n",
                "p/Heir.java", "package p;\npublic class Heir extends g.Open {}\n"));

    assertThat(fixture.resolve("p/Plain.java", "p.Ghost", "p.Plain").outcome())
        .isEqualTo(Outcome.UNCHANGED_COMPLETE);
    assertThat(fixture.resolve("p/Star.java", "p.Ghost", "p.Star").outcome())
        .isEqualTo(Outcome.UNCHANGED_INCOMPLETE);
    assertThat(fixture.resolve("p/Static.java", "p.Ghost", "p.Static").outcome())
        .isEqualTo(Outcome.UNCHANGED_INCOMPLETE);
    assertThat(fixture.resolve("p/Single.java", "p.Ghost", "p.Single").outcome())
        .isEqualTo(Outcome.UNCHANGED_INCOMPLETE);
    assertThat(fixture.resolve("p/Module.java", "p.Ghost", "p.Module").outcome())
        .isEqualTo(Outcome.UNCHANGED_INCOMPLETE);
    assertThat(fixture.resolve("p/Heir.java", "p.Ghost", "p.Heir").outcome())
        .as("um supertipo externo dois níveis acima")
        .isEqualTo(Outcome.UNCHANGED_INCOMPLETE);
    assertThat(fixture.index().type("p.Heir").complete()).isFalse();
    assertThat(fixture.index().type("p.Plain").complete()).isTrue();
  }

  @Test
  void umTipoAnonimoOuLocalHerdaPelosSupertiposEscritos() {
    final UnitScope scope = scope("b/Top.java");
    final NameContext anonymousBase =
        new NameContext(
            List.of("b.Top$1", "b.Top"),
            Map.of("b.Top$1", List.of(new WrittenName("a.Base", false))));
    final NameContext anonymousRunnable =
        new NameContext(
            List.of("b.Top$2", "b.Top"),
            Map.of("b.Top$2", List.of(new WrittenName("java.lang.Runnable", true))));

    assertPlaced(scope.resolve("b.Inner", true, anonymousBase), "a.Base$Inner");
    assertThat(scope.resolve("b.Missing", true, anonymousRunnable).outcome())
        .isEqualTo(Outcome.UNCHANGED_INCOMPLETE);
    assertThat(scope.resolve("b.Missing", true, in("b.Top")).outcome())
        .isEqualTo(Outcome.UNCHANGED_COMPLETE);
  }

  @Test
  void osCandidatosDaRegraTresSaoTiposDeTopoAcessiveisEmOrdemDeCodigo() {
    final Fixture fixture =
        Fixture.of(
            Map.of(
                "x/Config.java", "package x;\npublic class Config {}\n",
                "y/Config.java", "package y;\nclass Config {}\n",
                "Config.java", "public class Config {}\n",
                "z/Holder.java",
                    "package z;\npublic class Holder { public static class Config {} }\n"));

    assertThat(fixture.index().topLevelCandidates("Config", "q")).containsExactly("x.Config");
    assertThat(fixture.index().topLevelCandidates("Config", "y"))
        .containsExactly("x.Config", "y.Config");
    assertThat(fixture.index().topLevelCandidates("Config", ""))
        .containsExactly("Config", "x.Config");
    assertThat(fixture.index().topLevelCandidates("Missing", "q")).isEmpty();
  }

  @Test
  void umIdRepetidoFicaComAPrimeiraDeclaracaoNaOrdemDeVisita() {
    final Map<String, String> sources =
        Map.of(
            "main/p/Dup.java", "package p;\npublic class Dup { public static class First {} }\n",
            "test/p/Dup.java", "package p;\npublic class Dup { public static class Second {} }\n");
    final Map<String, UnitShape> shapes = Fixture.shapes(sources);

    final SimpleNameIndex mainFirst =
        Fixture.index(shapes, List.of("main/p/Dup.java", "test/p/Dup.java"));
    final SimpleNameIndex testFirst =
        Fixture.index(shapes, List.of("test/p/Dup.java", "main/p/Dup.java"));

    assertThat(mainFirst.memberType("p.Dup", "First").answer()).isEqualTo(MemberAnswer.YES);
    assertThat(mainFirst.memberType("p.Dup", "Second").answer()).isEqualTo(MemberAnswer.NO);
    assertThat(testFirst.memberType("p.Dup", "Second").answer()).isEqualTo(MemberAnswer.YES);
  }

  @Test
  void aResolucaoAntecipadaNaoDependeDaOrdemDeEntrada() {
    final Map<String, String> sources =
        Map.of(
            "q/A.java", "package q;\npublic class A extends B.Nest { public static class X {} }\n",
            "q/B.java", "package q;\npublic class B extends A { public static class Nest {} }\n",
            "q/C.java", "package q;\npublic class C extends A.X {}\n");
    final Map<String, UnitShape> shapes = Fixture.shapes(sources);
    final List<String> forward = List.of("q/A.java", "q/B.java", "q/C.java");
    final List<String> backward = List.of("q/C.java", "q/B.java", "q/A.java");

    final SimpleNameIndex one = Fixture.index(shapes, forward);
    final SimpleNameIndex other = Fixture.index(shapes, backward);

    for (final String type : List.of("q.A", "q.B", "q.C", "q.A$X", "q.B$Nest")) {
      assertThat(other.type(type).supertypes())
          .as(type)
          .containsExactly(one.type(type).supertypes());
      assertThat(other.type(type).complete()).as(type).isEqualTo(one.type(type).complete());
    }
  }

  @Test
  void osImportsEstaticosQueOSpoonDescartaNoLoteVoltamPeloIndice() throws Exception {
    final Map<String, String> declarations =
        Map.of(
            "a/UBase.java",
            "package a;\npublic class UBase { public static void Probe() {} public static class Nest {} }\n",
            "a/Util.java",
            "package a;\npublic class Util extends UBase {}\n",
            "x/Probe.java",
            "package x;\npublic class Probe {}\n");
    final String user =
        """
        package b;

        import static a.Util.Probe;
        import static a.Util.Nest;

        public class User {
          Probe probe;
          Nest nest;
        }
        """;

    final Layout oneRoot =
        layout(temp.resolve("one-root"), declarations, "src/main/java/", user, 1);
    final Layout twoRoots =
        layout(temp.resolve("two-roots"), declarations, "src/test/java/", user, 2);

    assertThat(oneRoot.importTexts()).as("pré-condição: o Spoon perdeu os dois imports").isEmpty();
    assertThat(twoRoots.importTexts()).containsExactly("a.Util.Nest", "a.Util.Probe");
    for (final Layout layout : List.of(oneRoot, twoRoots)) {
      assertThat(layout.simpleProbe())
          .as("Probe é importado: o escopo nunca é completo para ele")
          .isEqualTo(new NameResolution("b.Probe", Outcome.UNCHANGED_INCOMPLETE));
      assertThat(layout.fields().get("nest"))
          .isEqualTo(new NameResolution("a.UBase$Nest", Outcome.PLACED));
      assertThat(layout.fields().get("probe"))
          .isEqualTo(new NameResolution("a.Util$Probe", Outcome.PLACED));
    }
  }

  private record Layout(
      List<String> importTexts, Map<String, NameResolution> fields, NameResolution simpleProbe) {}

  private static Layout layout(
      Path directory, Map<String, String> declarations, String userRoot, String user, int rootCount)
      throws Exception {
    final Map<String, String> files = new LinkedHashMap<>();
    declarations.forEach((path, source) -> files.put("src/main/java/" + path, source));
    files.put(userRoot + "b/User.java", user);
    final ValidatedRepository repository = SourceTrees.repository(directory, files);
    final List<SourceRoot> roots =
        repository.layout().sourceRoots().stream()
            .sorted(Comparator.comparingInt(root -> VISIT_ORDER.indexOf(root.kind())))
            .toList();
    assertThat(roots).as("pré-condição: as raízes").hasSize(rootCount);
    final List<String> imports = new ArrayList<>();
    final Map<String, NameResolution> fields = new LinkedHashMap<>();
    final List<NameResolution> probe = new ArrayList<>();
    new SpoonModelBuilder()
        .analyze(
            repository,
            roots,
            batch ->
                batch.perCompilationUnit(
                    (path, unit) -> {
                      if (path.endsWith("b/User.java")) {
                        for (final CtImport imported : unit.getImports()) {
                          if (imported instanceof CtUnresolvedImport text) {
                            imports.add(text.getUnresolvedReference());
                          }
                        }
                        final SimpleNameIndex.Scope scope = batch.index().scopeOf(unit, "b");
                        for (final CtType<?> type : unit.getDeclaredTypes()) {
                          for (final CtField<?> field : type.getFields()) {
                            fields.put(
                                field.getSimpleName(),
                                ElementIds.resolveLeaf(field.getType(), scope));
                          }
                          probe.add(scope.resolve("b.Probe", true, ElementIds.context(type)));
                        }
                      }
                      return null;
                    }),
            SourceTrees.NEVER);
    return new Layout(imports.stream().sorted().toList(), fields, probe.getFirst());
  }

  private static void assertPlaced(NameResolution resolution, String expected) {
    assertThat(resolution).isEqualTo(new NameResolution(expected, Outcome.PLACED));
  }

  private static NameResolution written(String file, String simpleName, String... context) {
    final String guess = SimpleNameIndex.qualify(ORACLE.packageOf(file), simpleName);
    return scope(file).resolve(guess, true, in(context));
  }

  private static UnitScope scope(String file) {
    return ORACLE.scope(file);
  }

  private static NameContext in(String... types) {
    return new NameContext(List.of(types));
  }

  private record Fixture(Map<String, UnitShape> shapes, SimpleNameIndex index) {

    static Fixture of(Map<String, String> sources) {
      final Map<String, UnitShape> shapes = shapes(sources);
      return new Fixture(shapes, index(shapes, shapes.keySet().stream().sorted().toList()));
    }

    UnitScope scope(String file) {
      return new UnitScope(index, ImportScope.of(shapes.get(file)));
    }

    String packageOf(String file) {
      return shapes.get(file).packageName();
    }

    NameResolution resolve(String file, String spoonName, String... context) {
      return scope(file).resolve(spoonName, true, in(context));
    }

    static SimpleNameIndex index(Map<String, UnitShape> shapes, List<String> order) {
      final IndexBuilder builder = SimpleNameIndex.builder();
      for (final String file : order) {
        builder.add(shapes.get(file), Path.of("/repository", file));
      }
      return builder.build();
    }

    static Map<String, UnitShape> shapes(Map<String, String> sources) {
      final Map<String, UnitShape> shapes = new LinkedHashMap<>();
      sources.forEach(
          (file, source) -> {
            final SyntaxPreParser.PreParse parsed =
                SyntaxPreParser.parse(file, source.toCharArray());
            assertThat(parsed.syntaxError()).as("pré-condição: " + file).isFalse();
            shapes.put(file, parsed.shape());
          });
      return shapes;
    }
  }

  private static Map<String, String> oracleSources() {
    final Map<String, String> sources = new LinkedHashMap<>();
    sources.put(
        "a/Api.java",
        """
        package a;
        public class Api {
          public static class Inner2 {}
          public class NonStatic {}
        }
        """);
    sources.put("a/ApiSub.java", "package a;\npublic class ApiSub extends Base {}\n");
    sources.put(
        "a/Base.java",
        """
        package a;
        public class Base {
          public static class Inner { public static class Deeper {} public static void go() {} }
          public interface Cb {}
          protected Status status;
          public void inherited() {}
          public static void stInherited() {}
        }
        """);
    sources.put(
        "a/BaseTest.java",
        "package a;\npublic class BaseTest { public static void assertThat(Object o) {} }\n");
    sources.put(
        "a/Color.java",
        "package a;\npublic enum Color { RED; public String label() { return \"r\"; } }\n");
    sources.put(
        "a/HasKinds.java", "package a;\npublic interface HasKinds { enum Kind { A, B } }\n");
    sources.put("a/I1.java", "package a;\npublic interface I1 { interface Node {} }\n");
    sources.put("a/I2.java", "package a;\npublic interface I2 { interface Node {} }\n");
    sources.put(
        "a/Late.java",
        """
        package a;
        import java.util.List;
        public class Late {
          public static final Runnable CONST = () -> {};
          public static final List<String> NAMES = List.of();
          public static class Inner { public static void go() {} }
        }
        """);
    sources.put("a/LateSub.java", "package a;\npublic class LateSub extends Late {}\n");
    sources.put("a/Mid.java", "package a;\npublic class Mid extends Base {}\n");
    sources.put(
        "a/PBase.java", "package a;\npublic class PBase { private static class Builder {} }\n");
    sources.put("a/QBase.java", "package a;\npublic class QBase { static class Entry {} }\n");
    sources.put(
        "a/Status.java",
        "package a;\npublic class Status { public boolean isOk() { return true; } }\n");
    sources.put(
        "a/UBase.java", "package a;\npublic class UBase { public static void stBase() {} }\n");
    sources.put(
        "a/Util.java",
        "package a;\npublic class Util extends UBase { public static void st2() {} }\n");
    sources.put("c/Builder.java", "package c;\npublic class Builder {}\n");
    sources.put("c/Entry.java", "package c;\npublic class Entry {}\n");
    sources.put("c/Inner.java", "package c;\npublic class Inner {}\n");
    sources.put(
        "k/Holder.java",
        "package k;\npublic class Holder { public static final k.Kconst KC = new k.Kconst(); }\n");
    sources.put("k/Kconst.java", "package k;\npublic class Kconst { public void touch() {} }\n");
    sources.put(
        "m/Metadata.java",
        "package m;\npublic class Metadata { public interface ProjectCustom {} }\n");
    sources.put(
        "b/ExtSub.java", "package b;\npublic class ExtSub extends ext.Lib {\n  LibInner li;\n}\n");
    sources.put(
        "b/Impl.java", "package b;\npublic class Impl implements a.HasKinds {\n  Kind k;\n}\n");
    sources.put(
        "b/MyMap.java",
        "package b;\npublic class MyMap extends java.util.HashMap<String, String> {\n"
            + "  Entry<String, String> e;\n}\n");
    sources.put(
        "b/OnDemandInherited.java",
        "package b;\nimport static a.ApiSub.*;\npublic class OnDemandInherited {\n  Inner x;\n}\n");
    sources.put(
        "b/OnDemandNonStatic.java",
        "package b;\nimport a.Api.*;\npublic class OnDemandNonStatic {\n  NonStatic x;\n}\n");
    sources.put(
        "b/OnDemandStatic.java",
        "package b;\nimport static a.Api.*;\npublic class OnDemandStatic {\n  Inner2 x;\n}\n");
    sources.put(
        "b/OnDemandType.java",
        "package b;\nimport a.Api.*;\npublic class OnDemandType {\n  Inner2 x;\n}\n");
    sources.put(
        "b/Outer.java",
        "package b;\npublic class Outer extends a.Base {\n  class Deep {\n    Inner i;\n  }\n}\n");
    sources.put(
        "b/P1.java",
        "package b;\npublic class P1 extends ext.Parser {\n  void f() { _errHandler.reset(); }\n}\n");
    sources.put(
        "b/Sub.java",
        "package b;\nimport a.Base;\npublic class Sub extends Base {\n  Inner f;\n}\n");
    sources.put(
        "b/Sub10.java",
        """
        package b;
        public class Sub10 extends a.Base {
          class In { void g() { inherited(); } }
          class InExt extends ext.Lib { void g() { inherited(); } }
          void h() { new Runnable() { public void run() { inherited(); } }.run(); }
        }
        """);
    sources.put("b/Sub2.java", "package b;\npublic class Sub2 extends a.Mid {\n  Cb x;\n}\n");
    sources.put(
        "b/Sub3.java",
        "package b;\npublic class Sub3 extends a.Base {\n  static class Inner {}\n  Inner g;\n}\n");
    sources.put(
        "b/Sub4.java",
        "package b;\nimport c.Inner;\npublic class Sub4 extends a.Base {\n  Inner h;\n}\n");
    sources.put(
        "b/Sub5.java",
        "package b;\nimport c.Builder;\npublic class Sub5 extends a.PBase {\n  Builder z;\n}\n");
    sources.put(
        "b/Sub6.java",
        "package b;\nimport c.Entry;\npublic class Sub6 extends a.QBase {\n  Entry e;\n}\n");
    sources.put(
        "b/Sub8.java", "package b;\npublic class Sub8 extends a.Base {\n  Inner.Deeper d;\n}\n");
    sources.put(
        "b/T1.java",
        "package b;\nimport static ext.Assertions.assertThat;\npublic class T1 {\n"
            + "  void t() { assertThat(1); }\n}\n");
    sources.put(
        "b/T2.java",
        "package b;\nimport static ext.Assertions.assertThat;\npublic class T2 extends a.BaseTest {\n"
            + "  void t() { assertThat(1); }\n}\n");
    sources.put(
        "b/T3.java",
        "package b;\nimport static ext.Assertions.assertThat;\npublic class T3 extends ext.LibTest {\n"
            + "  void t() { assertThat(1); }\n}\n");
    sources.put(
        "b/Top.java",
        "package b;\npublic class Top {\n  private class Inner { Inner(ext.Missing m) {} }\n"
            + "  void f() { new Inner(null); }\n}\n");
    sources.put(
        "b/User.java",
        "package b;\nimport static a.Util.*;\npublic class User {\n  void f() { st2(); stBase(); }\n}\n");
    sources.put(
        "b/V1.java",
        "package b;\npublic class V1 extends a.Base {\n  void f() { status.isOk(); }\n}\n");
    sources.put(
        "b/V3.java",
        "package b;\npublic class V3 {\n  static class In extends a.Base {\n"
            + "    void f() { status.isOk(); }\n  }\n}\n");
    sources.put("b/ViaSub.java", "package b;\npublic class ViaSub {\n  a.ApiSub.Inner v;\n}\n");
    sources.put("s/Metadata.java", "package s;\npublic class Metadata {}\n");
    sources.put(
        "s/ScriptMeta.java",
        "package s;\nimport m.Metadata;\npublic class ScriptMeta implements Metadata.ProjectCustom {\n}\n");
    sources.put(
        "b/Both.java", "package b;\npublic class Both implements a.I1, a.I2 {\n  Node n;\n}\n");
    sources.put(
        "cyc/Cyc.java",
        "package cyc;\npublic class Cyc extends Cyc2 { Inner x; }\nclass Cyc2 extends Cyc {}\n");
    sources.put(
        "b/OdInh.java", "package b;\nimport a.ApiSub.*;\npublic class OdInh { Inner x; }\n");
    sources.put(
        "b/SsInh.java",
        "package b;\nimport static a.ApiSub.Inner;\npublic class SsInh { Inner x; }\n");
    return sources;
  }
}
