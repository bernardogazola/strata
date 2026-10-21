package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import br.pucpr.tcc.strata.analyzer.repository.ProjectLayout;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class SourceSelectionTest {

  private static final String ANALYSIS_START = "analysis.start";

  private static final SourceRoot API_MAIN = raiz("api/src/main/java", SourceRootKind.MAIN, 12);
  private static final SourceRoot API_TEST = raiz("api/src/test/java", SourceRootKind.TEST, 4);
  private static final SourceRoot APP_GEN =
      raiz(
          "app/build/generated/sources/annotationProcessor/java/main", SourceRootKind.GENERATED, 3);
  private static final SourceRoot APP_MAIN = raiz("app/src/main/java", SourceRootKind.MAIN, 20);
  private static final SourceRoot APP_TEST = raiz("app/src/test/java", SourceRootKind.TEST, 9);
  private static final SourceRoot CORE_GEN =
      raiz("core/target/generated-sources/annotations", SourceRootKind.GENERATED, 2);
  private static final SourceRoot CORE_MAIN = raiz("core/src/main/java", SourceRootKind.MAIN, 30);

  private static final ProjectLayout MULTI =
      disposicao(API_MAIN, API_TEST, APP_GEN, APP_MAIN, APP_TEST, CORE_GEN, CORE_MAIN);

  @Test
  void oPadraoSemTestesEMainDepoisGenerated() {
    assertThat(SourceSelection.select(MULTI, padrao(false)))
        .containsExactly(API_MAIN, APP_MAIN, CORE_MAIN, APP_GEN, CORE_GEN);
  }

  @Test
  void oPadraoComTestesAcrescentaTestNoFim() {
    assertThat(SourceSelection.select(MULTI, padrao(true)))
        .containsExactly(API_MAIN, APP_MAIN, CORE_MAIN, APP_GEN, CORE_GEN, API_TEST, APP_TEST);
  }

  @Test
  void aListaExplicitaVoltaReordenadaPorTipoEDepoisPorCaminho() {
    final List<SourceRoot> escolhidas = List.of(APP_TEST, CORE_MAIN, APP_GEN, API_TEST, APP_MAIN);

    final List<SourceRoot> selecionadas =
        SourceSelection.select(MULTI, explicita(false, escolhidas));

    assertThat(selecionadas)
        .as("MAIN → GENERATED → TEST, e caminho dentro de cada tipo: a ordem da regra 11")
        .containsExactly(APP_MAIN, CORE_MAIN, APP_GEN, API_TEST, APP_TEST);
  }

  @Test
  void aListaExplicitaVenceIncludeTestsNosDoisSentidos() {
    assertThat(SourceSelection.select(MULTI, explicita(false, List.of(API_TEST))))
        .as("includeTests falso não tira a raiz de teste que a lista pediu")
        .containsExactly(API_TEST);
    assertThat(SourceSelection.select(MULTI, explicita(true, List.of(CORE_MAIN))))
        .as("includeTests verdadeiro não acrescenta teste a uma lista explícita")
        .containsExactly(CORE_MAIN);
  }

  @Test
  void umIdBemFormadoQueNaoERaizDetectadaERecusadoComoPayloadInvalido() {
    final String desconhecido = SourceRootId.derive("nao/existe/src/main/java").value();
    final AnalysisConfiguration configuracao =
        new AnalysisConfiguration(
            false, HistoryScope.ALL, null, ordenados(API_MAIN.id().value(), desconhecido), false);

    final ProtocolError erro = recusa(MULTI, configuracao);

    assertThat(erro.code()).isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);
    assertThat(erro.params()).isEqualTo(Map.of("method", ANALYSIS_START));
    assertThat(erro.origin()).isEqualTo(ErrorCodes.ORIGIN_ANALYZER);
    assertThat(erro.detail())
        .isNotBlank()
        .doesNotContain(desconhecido)
        .doesNotContain("nao/existe");
  }

  @Test
  void umaSelecaoComZeroArquivosJavaERecusadaComoPayloadInvalido() {
    final ProjectLayout soTestes = disposicao(API_TEST, APP_TEST);

    final ProtocolError erro = recusa(soTestes, padrao(false));

    assertThat(erro.code())
        .as("includeTests falso em um repositório só de testes: a interface honesta nem manda")
        .isEqualTo(ErrorCodes.PROTO_INVALID_PAYLOAD);
    assertThat(erro.params()).isEqualTo(Map.of("method", ANALYSIS_START));
    assertThat(erro.detail()).isNotBlank();
  }

  @Test
  void asDuasRecusasDePayloadTemFrasesDiferentes() {
    final String desconhecido = SourceRootId.derive("outra/src/main/java").value();

    final ProtocolError idDesconhecido =
        recusa(
            MULTI,
            new AnalysisConfiguration(false, HistoryScope.ALL, null, List.of(desconhecido), false));
    final ProtocolError vazia = recusa(disposicao(API_TEST), padrao(false));

    assertThat(idDesconhecido.detail())
        .as("uma frase fixa por regra: quem lê o log sabe qual das duas foi")
        .isNotEqualTo(vazia.detail());
  }

  @Test
  void noTetoDeFontesJavaPassaEUmAcimaRecusaComOEncontradoExato() {
    final int teto = AnalysisConstants.MAX_JAVA_SOURCES;
    final SourceRoot metade = raiz("a/src/main/java", SourceRootKind.MAIN, teto / 2);
    final SourceRoot outraMetade = raiz("b/src/main/java", SourceRootKind.MAIN, teto - teto / 2);
    final SourceRoot umAMais = raiz("c/src/main/java", SourceRootKind.MAIN, 1);

    assertThat(SourceSelection.select(disposicao(metade, outraMetade), padrao(false)))
        .as("o teto é inclusivo: 60 000 passa")
        .containsExactly(metade, outraMetade);

    final ProtocolError erro = recusa(disposicao(metade, outraMetade, umAMais), padrao(false));

    assertThat(erro).isEqualTo(ErrorCodes.tooManyJavaSources(teto, teto + 1));
  }

  @Test
  void oTetoContaSoAsRaizesSelecionadas() {
    final int teto = AnalysisConstants.MAX_JAVA_SOURCES;
    final SourceRoot principal = raiz("src/main/java", SourceRootKind.MAIN, teto - 10_000);
    final SourceRoot testes = raiz("src/test/java", SourceRootKind.TEST, 20_000);
    final ProjectLayout layout = disposicao(principal, testes);

    assertThat(SourceSelection.select(layout, padrao(false)))
        .as("deixar os testes de fora é o que a dica do erro sugere, e tem de funcionar")
        .containsExactly(principal);
    assertThat(recusa(layout, padrao(true)))
        .as("found é a soma da seleção, a mesma população do totalFiles")
        .isEqualTo(ErrorCodes.tooManyJavaSources(teto, teto + 10_000));
    assertThat(SourceSelection.select(layout, explicita(true, List.of(testes))))
        .containsExactly(testes);
  }

  private static ProtocolError recusa(ProjectLayout layout, AnalysisConfiguration configuracao) {
    final StageFailedException recusada =
        catchThrowableOfType(
            StageFailedException.class, () -> SourceSelection.select(layout, configuracao));
    assertThat(recusada).as("a seleção tinha de recusar com StageFailedException").isNotNull();
    return recusada.error();
  }

  private static AnalysisConfiguration padrao(boolean includeTests) {
    return new AnalysisConfiguration(includeTests, HistoryScope.ALL, null, null, false);
  }

  private static AnalysisConfiguration explicita(boolean includeTests, List<SourceRoot> raizes) {
    return new AnalysisConfiguration(
        includeTests,
        HistoryScope.ALL,
        null,
        ordenados(raizes.stream().map(raiz -> raiz.id().value()).toArray(String[]::new)),
        false);
  }

  private static List<String> ordenados(String... ids) {
    return Stream.of(ids).distinct().sorted(Comparator.naturalOrder()).toList();
  }

  private static SourceRoot raiz(String caminho, SourceRootKind tipo, int arquivosJava) {
    return new SourceRoot(SourceRootId.derive(caminho), caminho, tipo, arquivosJava);
  }

  private static ProjectLayout disposicao(SourceRoot... raizes) {
    return new ProjectLayout(BuildSystem.GRADLE, List.of(), List.of(raizes));
  }
}
