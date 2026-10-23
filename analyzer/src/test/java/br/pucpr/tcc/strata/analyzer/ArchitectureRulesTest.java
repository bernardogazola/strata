package br.pucpr.tcc.strata.analyzer;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "br.pucpr.tcc.strata.analyzer")
final class ArchitectureRulesTest {

  private static final String PACOTE_DO_PIPELINE = "br.pucpr.tcc.strata.analyzer.analysis..";

  private static final String PACOTE_DO_GRAFO = "br.pucpr.tcc.strata.analyzer.graph..";

  private static final Set<String> PESOS =
      Set.of(
          "br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator#weightOf",
          "br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator#rowWeight",
          "br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge#weight");

  private static final DescribedPredicate<JavaAccess<?>> UM_PESO =
      describe(
          "DependencyAggregator.weightOf, DependencyAggregator.rowWeight or DependencyEdge.weight()",
          acesso ->
              acesso instanceof JavaCodeUnitAccess<?>
                  && PESOS.contains(
                      acesso.getTargetOwner().getFullName() + "#" + acesso.getName()));

  @ArchTest
  static final ArchRule nenhumaClasseUsaProcessBuilderOuRuntimeExec =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.lang.ProcessBuilder")
          .orShould()
          .callMethodWhere(
              describe(
                  "Runtime.exec(..)",
                  call ->
                      "java.lang.Runtime".equals(call.getTargetOwner().getFullName())
                          && "exec".equals(call.getName())))
          .because(
              "abrir um repositório é abrir um documento não confiável para análise, não executar"
                  + " um projeto desconhecido");

  @ArchTest
  static final ArchRule nenhumaClasseReferenciaOMavenLauncher =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("spoon.MavenLauncher")
          .because("ele roda o Maven do repositório analisado");

  @ArchTest
  static final ArchRule nenhumaClasseForaDeIntegrationTocaJavaNet =
      noClasses()
          .that()
          .resideOutsideOfPackage("..integration..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.net..")
          .because(
              "a análise é local; a única rede permitida é a integração opt-in, desligada por"
                  + " padrão");

  @ArchTest
  static final ArchRule aPersistenciaNaoDependeDoProtocolo =
      semDependencia("..persistence..", "..protocol..")
          .because(
              "a persistência devolve valores e quem abre o cache emite o evento; o contrário"
                  + " acoplaria o formato do fio ao SQLite");

  @ArchTest
  static final ArchRule aAnaliseDeFonteNaoDependeDaPersistencia =
      semDependencia("..sourceanalysis..", "..persistence..")
          .because(
              "o extrator devolve valores por uma porta e a persistência os grava; o contrário"
                  + " acoplaria a visita ao modelo do Spoon ao SQLite");

  @ArchTest
  static final ArchRule nemAAnaliseDeFonteNemORepositorioDependemDoPipeline =
      noClasses()
          .that()
          .resideInAnyPackage("..sourceanalysis..", "..repository..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(PACOTE_DO_PIPELINE)
          .because(
              "o pipeline compõe a análise de fonte e o repositório por tipos do JDK; o contrário"
                  + " acoplaria a varredura e o Spoon ao registro de análises");

  @ArchTest
  static final ArchRule oGrafoNaoDependeDaPersistencia =
      semDependencia(PACOTE_DO_GRAFO, "..persistence..")
          .because(
              "o estágio lê pelos DAOs, chama o graph/ e grava pelos DAOs; o graph/ só recebe e"
                  + " devolve valores");

  @ArchTest
  static final ArchRule oGrafoNaoDependeDoPipeline =
      semDependencia(PACOTE_DO_GRAFO, PACOTE_DO_PIPELINE)
          .because(
              "o pipeline compõe o graph/ por tipos do JDK; o contrário traria o registro de"
                  + " análises para dentro do teste de cada algoritmo");

  @ArchTest
  static final ArchRule oGrafoNaoAlcancaPesoNenhum =
      semPeso(PACOTE_DO_GRAFO)
          .because(
              "o grafo é simples e sem peso: um peso no PageRank premiaria o laço mais tagarela, e"
                  + " a intermediação o leria como distância");

  private static ArchRule semPeso(String pacote) {
    return noClasses().that().resideInAPackage(pacote).should().accessTargetWhere(UM_PESO);
  }

  private static ArchRule semDependencia(String origem, String destino) {
    return noClasses()
        .that()
        .resideInAPackage(origem)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(destino);
  }

  private static JavaClasses importar() {
    return new ClassFileImporter().importPackages("br.pucpr.tcc.strata.analyzer");
  }

  @Test
  void oConjuntoImportadoNaoEstaVazio() {
    final JavaClasses importadas = importar();

    assertThat(importadas)
        .as("um pacote errado devolve conjunto vazio e deixa as regras verdes para sempre")
        .isNotEmpty();
    assertThat(importadas.stream().map(c -> c.getFullName()))
        .as(
            "produção e teste: as regras valem para os dois, e um grep não sabe distinguir"
                + " a proibição do violador")
        .contains("br.pucpr.tcc.strata.analyzer.bootstrap.Main")
        .contains("br.pucpr.tcc.strata.analyzer.protocol.FramingTest")
        .as("os dois lados da quarta regra: sem um deles ela é verde por vacuidade")
        .contains("br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidation")
        .contains("br.pucpr.tcc.strata.analyzer.protocol.Envelope")
        .as("os dois lados da quinta regra")
        .contains("br.pucpr.tcc.strata.analyzer.sourceanalysis.ElementSink")
        .contains("br.pucpr.tcc.strata.analyzer.persistence.ElementWriter")
        .as("os três pacotes da sexta regra")
        .contains("br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder")
        .contains("br.pucpr.tcc.strata.analyzer.repository.RepositoryWalker")
        .contains("br.pucpr.tcc.strata.analyzer.analysis.CancellationToken")
        .as("o graph/ das três regras do grafo, e os dois lados da regra do peso")
        .contains("br.pucpr.tcc.strata.analyzer.graph.GraphAlgorithms")
        .contains("br.pucpr.tcc.strata.analyzer.graph.StructuralGraphAnalysis")
        .contains("br.pucpr.tcc.strata.analyzer.sourceanalysis.DependencyAggregator")
        .contains("br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyEdge")
        .contains("br.pucpr.tcc.strata.analyzer.persistence.DependencyFlush");
  }

  @Test
  void aFronteiraInvertidaReprovaNoEnvelope() {
    final JavaClasses importadas = importar();

    final EvaluationResult invertida =
        semDependencia("..protocol..", "..persistence..").evaluate(importadas);

    assertThat(invertida.hasViolation()).isTrue();
    assertThat(invertida.getFailureReport().getDetails())
        .anySatisfy(
            detalhe ->
                assertThat(detalhe)
                    .contains("br.pucpr.tcc.strata.analyzer.protocol.Envelope")
                    .contains("br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidation"));
  }

  @Test
  void aFronteiraInvertidaReprovaNoElementWriter() {
    final JavaClasses importadas = importar();

    final EvaluationResult invertida =
        semDependencia("..persistence..", "..sourceanalysis..").evaluate(importadas);

    assertThat(invertida.hasViolation()).isTrue();
    assertThat(invertida.getFailureReport().getDetails())
        .anySatisfy(
            detalhe ->
                assertThat(detalhe)
                    .contains("br.pucpr.tcc.strata.analyzer.persistence.ElementWriter")
                    .contains("br.pucpr.tcc.strata.analyzer.sourceanalysis.ElementSink"));
  }

  @Test
  void aFronteiraInvertidaReprovaNoRunInputs() {
    final JavaClasses importadas = importar();

    final EvaluationResult invertida =
        semDependencia(PACOTE_DO_PIPELINE, "..repository..").evaluate(importadas);

    assertThat(invertida.hasViolation()).isTrue();
    assertThat(invertida.getFailureReport().getDetails())
        .anySatisfy(
            detalhe ->
                assertThat(detalhe)
                    .contains("br.pucpr.tcc.strata.analyzer.analysis.RunInputs")
                    .contains("br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository"));
  }

  @Test
  void aRegraDoPesoInvertidaReprovaNoDependencyFlush() {
    final EvaluationResult invertida = semPeso("..persistence..").evaluate(importar());

    assertThat(invertida.hasViolation()).isTrue();
    assertThat(invertida.getFailureReport().getDetails())
        .anySatisfy(
            detalhe ->
                assertThat(detalhe)
                    .contains("br.pucpr.tcc.strata.analyzer.persistence.DependencyFlush")
                    .contains("DependencyAggregator.rowWeight"));
  }

  @Test
  void aRegraDoPesoPegaUmaReferenciaDeMetodo() {
    final EvaluationResult naFixture =
        noClasses()
            .that()
            .haveFullyQualifiedName(ReferenciaAoPeso.class.getName())
            .should()
            .accessTargetWhere(UM_PESO)
            .evaluate(importar());

    assertThat(ReferenciaAoPeso.PESO).isNotNull();
    assertThat(naFixture.hasViolation()).isTrue();
    assertThat(naFixture.getFailureReport().getDetails())
        .anySatisfy(detalhe -> assertThat(detalhe).contains("DependencyEdge.weight()"));
  }

  @Test
  void oImportadorEnxergaAsArestasQueSaemDoGrafo() {
    final EvaluationResult saindo =
        semDependencia(PACOTE_DO_GRAFO, "..sourceanalysis..").evaluate(importar());

    assertThat(saindo.hasViolation()).isTrue();
    assertThat(saindo.getFailureReport().getDetails())
        .anySatisfy(
            detalhe ->
                assertThat(detalhe)
                    .contains("br.pucpr.tcc.strata.analyzer.graph.")
                    .contains("br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId"));
  }

  private static final class ReferenciaAoPeso {

    static final ToIntFunction<DependencyEdge> PESO = DependencyEdge::weight;

    private ReferenciaAoPeso() {}
  }
}
