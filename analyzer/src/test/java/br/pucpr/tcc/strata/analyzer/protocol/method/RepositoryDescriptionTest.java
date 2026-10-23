package br.pucpr.tcc.strata.analyzer.protocol.method;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import br.pucpr.tcc.strata.analyzer.repository.HeadState;
import br.pucpr.tcc.strata.analyzer.repository.ProjectLayout;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryInventory;
import br.pucpr.tcc.strata.analyzer.repository.RepositorySnapshot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class RepositoryDescriptionTest {

  private static final String RAIZ = "core/src/main/java";

  @ParameterizedTest(name = "{0} módulos declarados")
  @ValueSource(ints = {0, 1, 255, 256, 257, 1_000})
  void aListaParaNoTetoNaOrdemDoDocumento(int declarados) {
    final List<String> modulos =
        IntStream.range(0, declarados)
            .mapToObj(indice -> "m%04d".formatted(declarados - indice))
            .toList();

    final List<String> descritos = RepositoryDescription.of(repositorio(modulos)).declaredModules();

    assertThat(descritos)
        .containsExactlyElementsOf(
            modulos.subList(0, Math.min(declarados, AnalysisConstants.MAX_DESCRIBED_MODULES)));
  }

  @Test
  void cadaModuloECortadoExatamenteComoOLabelDeUmaRaiz() {
    final String longo = "a".repeat(AnalysisConstants.MAX_PATH_BYTES) + "b";
    final String parteOPar =
        "a".repeat(AnalysisConstants.MAX_PATH_BYTES - 1) + Character.toString(0x1F600);
    final String comControle = "co" + (char) 0x01 + "re";

    final RepositoryDescription descricao =
        RepositoryDescription.of(repositorio(List.of(longo, parteOPar, comControle), longo));

    assertThat(descricao.declaredModules())
        .containsExactly(
            "a".repeat(AnalysisConstants.MAX_PATH_BYTES),
            "a".repeat(AnalysisConstants.MAX_PATH_BYTES - 1),
            "core");
    assertThat(descricao.declaredModules().getFirst())
        .isEqualTo(descricao.sourceRoots().getFirst().label());
  }

  private static ValidatedRepository repositorio(List<String> modulos) {
    return repositorio(modulos, RAIZ);
  }

  private static ValidatedRepository repositorio(List<String> modulos, String caminhoDaRaiz) {
    final SourceRoot raiz =
        new SourceRoot(SourceRootId.derive(caminhoDaRaiz), caminhoDaRaiz, SourceRootKind.MAIN, 1);
    return new ValidatedRepository(
        new RepositorySnapshot(
            Path.of("multi-module").toAbsolutePath(),
            "multi-module",
            new HeadState(null, "main", false),
            new RepositoryInventory(1, List.of(), List.of())),
        new ProjectLayout(BuildSystem.MAVEN, modulos, List.of(raiz)));
  }
}
