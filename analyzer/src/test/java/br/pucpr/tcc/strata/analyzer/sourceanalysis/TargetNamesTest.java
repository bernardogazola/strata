package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.TargetNames.Target;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TargetNamesTest {

  private static final SourceRootId ROOT = SourceRootId.derive("src/main/java");

  private static final SimpleNameIndex INDEX =
      SimpleNameIndex.of(
          List.of(
              analyzed("src/main/java/a/Api.java", "a", "Api", "Odd$Name"),
              analyzed("src/main/java/b/Peer.java", "b", "Peer"),
              analyzed("src/main/java/Root.java", "", "Root")));

  private static final Set<String> THIS_BATCH = Set.of("b.Peer");

  @Test
  void umAninhadoEDeclaradoPeloSeuTipoDeTopo() {
    assertThat(INDEX.declaredTopLevel("a.Api")).contains("a.Api");
    assertThat(INDEX.declaredTopLevel("a.Api$Inner$Deep")).contains("a.Api");
    assertThat(INDEX.declaredTopLevel("Root$Inner")).contains("Root");
  }

  @Test
  void umDolarNoNomeDoTipoDeTopoNaoViraAninhado() {
    assertThat(INDEX.declaredTopLevel("a.Odd$Name")).contains("a.Odd$Name");
    assertThat(INDEX.declaredTopLevel("a.Odd$Name$Inner")).contains("a.Odd$Name");
  }

  @Test
  void umNomeExternoOuUmDolarDePacoteNaoSaoDeclarados() {
    assertThat(INDEX.declaredTopLevel("java.util.List")).isEmpty();
    assertThat(INDEX.declaredTopLevel("a$x.Api")).isEmpty();
    assertThat(INDEX.declaredTopLevel("a.Apix")).isEmpty();
    assertThat(INDEX.declaredTopLevel("$Root")).isEmpty();
  }

  @Test
  void osTiposDeTopoDeUmLoteSaoOsDoPrePareDosArquivosDele() {
    assertThat(
            SimpleNameIndex.topLevelTypes(
                List.of(
                    analyzed("src/main/java/a/Api.java", "a", "Api", "Odd$Name"),
                    analyzed("src/main/java/Root.java", "", "Root"),
                    new SourceFile(
                        "src/main/java/a/Broken.java",
                        ROOT,
                        SourceFileStatus.PARSE_FAILED,
                        ParseFailureCause.SYNTAX_ERROR,
                        null))))
        .containsExactlyInAnyOrder("a.Api", "a.Odd$Name", "Root");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Order", "p.Order", "p.Outer$Inner", "_x.$y", "pkg.Ação"})
  void umNomeQualificadoDeIdentificadoresEValido(String name) {
    assertThat(TargetNames.isQualifiedIdentifier(name)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"p.HashMap<>", "int[]", "", "p..Order", "p.", ".Order", "p.1Order"})
  void umNomeQueNaoEIdNaoEValido(String name) {
    assertThat(TargetNames.isQualifiedIdentifier(name)).isFalse();
  }

  @Test
  void aQualidadeEDoTextoDoLoteEDaRegraQueNomeou() {
    final TargetNames names = names("b", new EnumMap<>(DropReason.class));

    assertThat(names.exact("b.Peer").quality()).isEqualTo(ResolutionQuality.RESOLVED);
    assertThat(names.recovered("b.Peer").quality()).isEqualTo(ResolutionQuality.INFERRED);
    assertThat(names.exact("a.Api$Inner").quality()).isEqualTo(ResolutionQuality.INFERRED);
    assertThat(names.exact("java.util.Map$Entry"))
        .isEqualTo(
            new Target(
                CodeElementId.ofType("java.util.Map$Entry"),
                false,
                ResolutionQuality.RESOLVED,
                false));
    assertThat(names.exact("javax.inject.Inject").quality())
        .isEqualTo(ResolutionQuality.UNRESOLVED_EXTERNAL);
    assertThat(names.exact("Ghost").quality()).isEqualTo(ResolutionQuality.UNRESOLVED_EXTERNAL);
  }

  @Test
  void oTipoDeCampoRecuperadoSoVaiAoJavaLangQuandoOJavaBaseOTem() {
    final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);
    final TargetNames names = names("b", drops);

    assertThat(names.recovered("Runnable"))
        .isEqualTo(
            new Target(
                CodeElementId.ofType("java.lang.Runnable"),
                false,
                ResolutionQuality.RESOLVED,
                false));
    assertThat(names.recovered("Ghost"))
        .isEqualTo(
            new Target(
                CodeElementId.ofType("Ghost"),
                false,
                ResolutionQuality.UNRESOLVED_EXTERNAL,
                false));
    assertThat(names.recovered(null)).isNull();
    assertThat(names.recovered("int")).isNull();
    assertThat(drops).containsExactly(Map.entry(DropReason.CALL_WITHOUT_TARGET, 2));
  }

  @Test
  void cadaNomeRecusadoEContadoPeloMotivo() {
    final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);
    final TargetNames names = names("b", drops);

    assertThat(names.exact("Root")).as("nu, de um pacote com nome: JLS §7.5").isNull();
    assertThat(names.exact("p.HashMap<>")).isNull();
    assertThat(names.exact("p..")).isNull();
    assertThat(names.exact("p." + "X".repeat(5_000))).isNull();
    assertThat(names.callWithoutTarget()).isNull();
    assertThat(names.exact("a.Api"))
        .isEqualTo(
            new Target(CodeElementId.ofType("a.Api"), true, ResolutionQuality.INFERRED, false));
    assertThat(names.exact("com.acme.Missing"))
        .isEqualTo(
            new Target(
                CodeElementId.ofType("com.acme.Missing"),
                false,
                ResolutionQuality.UNRESOLVED_EXTERNAL,
                false));
    assertThat(names.exact("jdk.internal.misc..Unsafe"))
        .isEqualTo(
            new Target(
                CodeElementId.ofType("jdk.internal.misc.Unsafe"),
                false,
                ResolutionQuality.UNRESOLVED_EXTERNAL,
                false));

    assertThat(drops)
        .containsExactly(
            Map.entry(DropReason.INVALID_NAME, 2),
            Map.entry(DropReason.ID_TOO_LONG, 1),
            Map.entry(DropReason.DEFAULT_PACKAGE_COLLISION, 1),
            Map.entry(DropReason.CALL_WITHOUT_TARGET, 1));
  }

  @Test
  void doPacotePadraoONomeNuDoPacotePadraoFica() {
    final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);

    assertThat(names("", drops).exact("Root"))
        .isEqualTo(
            new Target(CodeElementId.ofType("Root"), true, ResolutionQuality.INFERRED, false));
    assertThat(drops).isEmpty();
  }

  private static TargetNames names(String ownPackage, Map<DropReason, Integer> drops) {
    final UnitHomes.Home home =
        new UnitHomes.Home(
            ownPackage,
            INDEX.scope(ownPackage, Set.of(), Map.of()),
            ImportScope.of(ownPackage, Set.of(), Map.of()));
    return new TargetNames(
        TargetNames.Rules.of(INDEX, THIS_BATCH, new JavaLangTypes()), home, drops);
  }

  private static SourceFile analyzed(String path, String packageName, String... types) {
    return new SourceFile(
        path,
        ROOT,
        SourceFileStatus.ANALYZED,
        null,
        new SourceFile.Declarations(packageName, List.of(types)));
  }
}
