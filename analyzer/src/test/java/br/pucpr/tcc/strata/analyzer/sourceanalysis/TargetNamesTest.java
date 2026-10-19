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
  void aQualidadeEProvisoriaPorTextoEPorLote() {
    final TargetNames names = names("b", new EnumMap<>(DropReason.class));

    assertThat(names.quality("b.Peer")).isEqualTo(ResolutionQuality.RESOLVED);
    assertThat(names.quality("a.Api$Inner")).isEqualTo(ResolutionQuality.INFERRED);
    assertThat(names.quality("java.util.Map$Entry")).isEqualTo(ResolutionQuality.RESOLVED);
    assertThat(names.quality("javax.inject.Inject"))
        .isEqualTo(ResolutionQuality.UNRESOLVED_EXTERNAL);
    assertThat(names.quality("Ghost")).isEqualTo(ResolutionQuality.UNRESOLVED_EXTERNAL);
  }

  @Test
  void cadaNomeRecusadoEContadoPeloMotivo() {
    final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);
    final TargetNames names = names("b", drops);

    assertThat(names.named("Root")).as("nu, de um pacote com nome: JLS §7.5").isNull();
    assertThat(names.named("p.HashMap<>")).isNull();
    assertThat(names.named("p." + "X".repeat(5_000))).isNull();
    assertThat(names.callWithoutTarget()).isNull();
    assertThat(names.named("a.Api"))
        .isEqualTo(new Target(CodeElementId.ofType("a.Api"), true, ResolutionQuality.INFERRED));
    assertThat(names.named("com.acme.Missing"))
        .isEqualTo(
            new Target(
                CodeElementId.ofType("com.acme.Missing"),
                false,
                ResolutionQuality.UNRESOLVED_EXTERNAL));

    assertThat(drops)
        .containsExactly(
            Map.entry(DropReason.INVALID_NAME, 1),
            Map.entry(DropReason.ID_TOO_LONG, 1),
            Map.entry(DropReason.DEFAULT_PACKAGE_COLLISION, 1),
            Map.entry(DropReason.CALL_WITHOUT_TARGET, 1));
  }

  @Test
  void doPacotePadraoONomeNuDoPacotePadraoFica() {
    final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);

    assertThat(names("", drops).named("Root"))
        .isEqualTo(new Target(CodeElementId.ofType("Root"), true, ResolutionQuality.INFERRED));
    assertThat(drops).isEmpty();
  }

  private static TargetNames names(String ownPackage, Map<DropReason, Integer> drops) {
    final SimpleNameIndex.Scope scope = INDEX.scope(ownPackage, Set.of(), Map.of());
    return new TargetNames(INDEX, THIS_BATCH, ownPackage, Map.of(), scope, drops);
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
