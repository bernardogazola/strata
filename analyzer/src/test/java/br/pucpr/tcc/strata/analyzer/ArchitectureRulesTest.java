package br.pucpr.tcc.strata.analyzer;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "br.pucpr.tcc.strata.analyzer")
final class ArchitectureRulesTest {

  private static final String PACOTE_DO_PIPELINE = "br.pucpr.tcc.strata.analyzer.analysis..";

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
        .as("um pacote errado devolve conjunto vazio e deixa as cinco regras verdes para sempre")
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
        .contains("br.pucpr.tcc.strata.analyzer.analysis.CancellationToken");
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
}
