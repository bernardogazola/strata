package br.pucpr.tcc.strata.analyzer.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import org.junit.jupiter.api.Test;

final class PackageGroupTest {

  @Test
  void umGrupoExatoSaiComoOProprioPacote() {
    final PackageGroup grupo = PackageGroup.exact("br.pucpr.legacy.core");

    assertThat(grupo.nodeId()).isEqualTo("pkg:br.pucpr.legacy.core");
    assertThat(grupo.nodeId()).isEqualTo(CodeElementId.ofPackage("br.pucpr.legacy.core").value());
    assertThat(grupo.kind()).isEqualTo(GraphNodeKind.PACKAGE);
  }

  @Test
  void oPacotePadraoEUmGrupoExatoDeIdPkgSemNome() {
    final PackageGroup grupo = PackageGroup.exact("");

    assertThat(grupo.nodeId()).isEqualTo("pkg:");
    assertThat(grupo.kind()).isEqualTo(GraphNodeKind.PACKAGE);
  }

  @Test
  void umGrupoDePrefixoSaiComoPfx() {
    final PackageGroup grupo = PackageGroup.prefix("br.pucpr.legacy.core");

    assertThat(grupo.nodeId()).isEqualTo("pfx:br.pucpr.legacy.core");
    assertThat(grupo.kind()).isEqualTo(GraphNodeKind.PACKAGE_PREFIX);
  }

  @Test
  void umPrefixoEUmPacoteDeMesmoNomeTemIdsDiferentes() {
    assertThat(PackageGroup.prefix("org.x").nodeId())
        .isNotEqualTo(PackageGroup.exact("org.x").nodeId());
  }

  @Test
  void umNomeNaoAsciiEntraNoIdComoEsta() {
    final String nome = "br.pucpr.pedidos.avaliação";

    assertThat(PackageGroup.exact(nome).nodeId()).isEqualTo("pkg:" + nome);
    assertThat(PackageGroup.prefix(nome).nodeId()).isEqualTo("pfx:" + nome);
  }

  @Test
  void oIdEOTipoDoGrupoFormamUmNoDeFatiaValido() {
    for (final PackageGroup grupo :
        new PackageGroup[] {
          PackageGroup.exact("a.b"), PackageGroup.exact(""), PackageGroup.prefix("a.b")
        }) {
      assertThatCode(
              () ->
                  new SliceNode(
                      grupo.nodeId(), grupo.kind(), "r", 0, 1, 0, 0, 0.5, ResolutionMix.NONE))
          .as(grupo.toString())
          .doesNotThrowAnyException();
    }
  }

  @Test
  void oPrefixoVazioNaoEGrupo() {
    assertThatThrownBy(() -> PackageGroup.prefix("")).isInstanceOf(IllegalArgumentException.class);
  }
}
