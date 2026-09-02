package br.pucpr.tcc.strata.analyzer;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(
    packages = "br.pucpr.tcc.strata.analyzer",
    importOptions = ImportOption.DoNotIncludeTests.class)
final class ArchitectureRulesTest {

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

  @Test
  void oConjuntoImportadoNaoEstaVazio() {
    final JavaClasses importadas =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("br.pucpr.tcc.strata.analyzer");

    assertThat(importadas)
        .as("um pacote errado devolve conjunto vazio e deixa as três regras verdes para sempre")
        .isNotEmpty();
    assertThat(importadas.stream().map(c -> c.getFullName()))
        .contains("br.pucpr.tcc.strata.analyzer.bootstrap.Main");
  }
}
