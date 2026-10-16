package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SyntaxPreParser.PreParse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.junit.jupiter.api.Test;

final class SyntaxPreParserTest {

  private static final String METHOD_BODY_MISSING_SEMICOLON =
      """
      class Corpo {
        int soma(int a, int b) {
          int total = a + b
          return total;
        }
      }
      """;

  private static final String METHOD_BODY_STRAY_PARENTHESIS =
      """
      class Parenteses {
        void executa() {
          System.out.println("x"));
        }
      }
      """;

  private static final String CLASS_BODY_MISSING_SEMICOLON =
      """
      class Campo {
        int contador
        void executa() {}
      }
      """;

  private static final String CLEAN =
      """
      class Limpo {
        int soma(int a, int b) {
          return a + b;
        }
      }
      """;

  @Test
  void umPontoEVirgulaFaltandoNoCorpoDeMetodoEErroDeSintaxe() {
    final PreParse preParse = parse("Corpo.java", METHOD_BODY_MISSING_SEMICOLON);

    assertSyntaxError(preParse);
  }

  @Test
  void umParentesesSobrandoNoCorpoDeMetodoEErroDeSintaxe() {
    final PreParse preParse = parse("Parenteses.java", METHOD_BODY_STRAY_PARENTHESIS);

    assertSyntaxError(preParse);
  }

  @Test
  void umPontoEVirgulaFaltandoNoCorpoDaClasseEErroDeSintaxe() {
    final PreParse preParse = parse("Campo.java", CLASS_BODY_MISSING_SEMICOLON);

    assertSyntaxError(preParse);
  }

  @Test
  void oReportPathDaFixtureBrokenSourceEErroDeSintaxe() throws Exception {
    final String relativePath = "src/main/java/br/pucpr/broken/ReportPath.java";
    final String source =
        Files.readString(TestRepositories.corpus("broken-source").resolve(relativePath));

    final PreParse preParse = parse(relativePath, source);

    assertSyntaxError(preParse);
  }

  @Test
  void umBomUtf8ComoPrimeiroCaractereEErroDeSintaxeComoNoJavac() {
    final PreParse preParse = parse("ComBom.java", "﻿class ComBom {}\n");

    assertSyntaxError(preParse);
  }

  @Test
  void umParserCompartilhadoPerdeOErroNoCorpoDeMetodoDepoisDeUmArquivoQuebrado() {
    final CompilerOptions options = spoonOptions();
    final Parser shared = parser(options);

    final boolean brokenHasErrors =
        hasErrors(shared, options, "Campo.java", CLASS_BODY_MISSING_SEMICOLON);
    final boolean bodyHasErrors =
        hasErrors(shared, options, "Corpo.java", METHOD_BODY_MISSING_SEMICOLON);

    assertThat(brokenHasErrors).isTrue();
    assertThat(bodyHasErrors).as("o diet ligado pelo primeiro erro pulou o corpo").isFalse();
  }

  @Test
  void oErroNoCorpoDeMetodoContinuaAchadoDepoisDeUmArquivoQuebrado() {
    final PreParse broken = parse("Campo.java", CLASS_BODY_MISSING_SEMICOLON);
    final PreParse body = parse("Corpo.java", METHOD_BODY_MISSING_SEMICOLON);

    assertSyntaxError(broken);
    assertSyntaxError(body);
  }

  @Test
  void naOrdemInversaTodoArquivoComErroContinuaAchado() {
    final PreParse body = parse("Corpo.java", METHOD_BODY_MISSING_SEMICOLON);
    final PreParse broken = parse("Campo.java", CLASS_BODY_MISSING_SEMICOLON);
    final PreParse parenthesis = parse("Parenteses.java", METHOD_BODY_STRAY_PARENTHESIS);

    assertSyntaxError(body);
    assertSyntaxError(broken);
    assertSyntaxError(parenthesis);
  }

  @Test
  void umArquivoLimpoDepoisDeUmQuebradoContinuaLimpo() {
    final PreParse broken = parse("Campo.java", CLASS_BODY_MISSING_SEMICOLON);
    final PreParse clean = parse("Limpo.java", CLEAN);

    assertSyntaxError(broken);
    assertThat(clean.syntaxError()).isFalse();
    assertThat(clean.declarations().topLevelTypes()).containsExactly("Limpo");
  }

  @Test
  void chamadasConcorrentesDaoAMesmaRespostaQueChamadasEmSequencia() throws Exception {
    final Map<String, Boolean> expected =
        Map.of(
            CLASS_BODY_MISSING_SEMICOLON, true,
            METHOD_BODY_MISSING_SEMICOLON, true,
            METHOD_BODY_STRAY_PARENTHESIS, true,
            CLEAN, false);
    final List<String> sources = List.copyOf(expected.keySet());
    final List<Future<Boolean>> results = new ArrayList<>();
    final List<String> submitted = new ArrayList<>();

    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      for (int index = 0; index < 200; index++) {
        final String source = sources.get(index % sources.size());
        submitted.add(source);
        results.add(pool.submit(() -> parse("Concorrente.java", source).syntaxError()));
      }
    }

    for (int index = 0; index < results.size(); index++) {
      assertThat(results.get(index).get())
          .as("chamada %d", index)
          .isEqualTo(expected.get(submitted.get(index)));
    }
  }

  @Test
  void umArquivoDeZeroCaracteresEVazioSemErro() {
    final PreParse preParse = parse("Vazio.java", "");

    assertEmpty(preParse);
  }

  @Test
  void umArquivoSoDeEspacosEVazioSemErro() {
    final PreParse preParse = parse("Espacos.java", "  \n\t\r\n   \n");

    assertEmpty(preParse);
  }

  @Test
  void umArquivoSoDeComentariosEVazioSemErro() {
    final PreParse preParse =
        parse(
            "Comentarios.java",
            """
            // linha
            /* bloco */
            /** javadoc sem nada depois */
            """);

    assertEmpty(preParse);
  }

  @Test
  void umArquivoSoComPontoEVirgulaEVazioSemErro() {
    final PreParse preParse = parse("PontoEVirgula.java", ";\n");

    assertEmpty(preParse);
  }

  @Test
  void umPackageInfoComJavadocNaoEVazio() {
    final PreParse preParse =
        parse(
            "src/main/java/br/pucpr/exemplo/package-info.java",
            """
            /** A documentação do pacote. */
            package br.pucpr.exemplo;
            """);

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.empty()).isFalse();
    assertThat(preParse.moduleDescriptor()).isFalse();
    assertThat(preParse.declarations())
        .isEqualTo(new SourceFile.Declarations("br.pucpr.exemplo", List.of()));
  }

  @Test
  void umPackageInfoAnotadoNaoEVazioENaoTemTipoDeTopo() {
    final PreParse preParse =
        parse(
            "src/main/java/br/pucpr/exemplo/package-info.java",
            """
            @Deprecated
            package br.pucpr.exemplo;
            """);

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.empty()).isFalse();
    assertThat(preParse.declarations())
        .isEqualTo(new SourceFile.Declarations("br.pucpr.exemplo", List.of()));
  }

  @Test
  void umPackageInfoSemDeclaracaoDePacoteNaoEVazioPorqueOSpoonCriaUnidadeParaEle() {
    final PreParse zeroChars = parse("src/main/java/br/pucpr/exemplo/package-info.java", "");
    final PreParse javadocOnly =
        parse("src/main/java/br/pucpr/exemplo/package-info.java", "/** Só o javadoc. */\n");

    for (final PreParse preParse : List.of(zeroChars, javadocOnly)) {
      assertThat(preParse.syntaxError()).isFalse();
      assertThat(preParse.empty()).isFalse();
      assertThat(preParse.declarations()).isEqualTo(new SourceFile.Declarations("", List.of()));
    }
  }

  @Test
  void umArquivoSoDeImportNaoEVazio() {
    final PreParse preParse = parse("SoImport.java", "import java.util.List;\n");

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.empty()).isFalse();
    assertThat(preParse.declarations()).isEqualTo(new SourceFile.Declarations("", List.of()));
  }

  @Test
  void umArquivoSoDePacoteNaoEVazio() {
    final PreParse preParse = parse("SoPacote.java", "package br.pucpr.sozinho;\n");

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.empty()).isFalse();
    assertThat(preParse.declarations())
        .isEqualTo(new SourceFile.Declarations("br.pucpr.sozinho", List.of()));
  }

  @Test
  void umModuleInfoEDescritorDeModuloENaoEVazio() {
    final PreParse preParse =
        parse(
            "src/main/java/module-info.java",
            """
            module br.pucpr.exemplo {
              requires java.sql;
              exports br.pucpr.exemplo;
            }
            """);

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.moduleDescriptor()).isTrue();
    assertThat(preParse.empty()).isFalse();
    assertThat(preParse.declarations()).isEqualTo(new SourceFile.Declarations("", List.of()));
  }

  @Test
  void umaClasseComumNaoEDescritorDeModulo() {
    final PreParse preParse = parse("src/main/java/Limpo.java", CLEAN);

    assertThat(preParse.moduleDescriptor()).isFalse();
    assertThat(preParse.empty()).isFalse();
  }

  @Test
  void asDeclaracoesTrazemOPacoteEOsTiposDeTopoNaOrdemDoArquivo() {
    final PreParse preParse =
        parse(
            "src/main/java/br/pucpr/tipos/Zeta.java",
            """
            package br.pucpr.tipos;

            import java.util.List;

            class Zeta {
              class Aninhada {}
            }

            interface Alfa {}

            enum Meio { UM, DOIS }

            record Beta(List<String> nomes) {}

            @interface Omega {}
            """);

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.empty()).isFalse();
    assertThat(preParse.declarations())
        .isEqualTo(
            new SourceFile.Declarations(
                "br.pucpr.tipos", List.of("Zeta", "Alfa", "Meio", "Beta", "Omega")));
  }

  @Test
  void oPacotePadraoEUmTextoVazio() {
    final PreParse preParse = parse("Limpo.java", CLEAN);

    assertThat(preParse.declarations())
        .isEqualTo(new SourceFile.Declarations("", List.of("Limpo")));
  }

  @Test
  void recordsSealedESwitchComPadraoDeRecordEUnderscoreNaoSaoErro() {
    final PreParse preParse =
        parse(
            "src/main/java/br/pucpr/moderno/Forma.java",
            """
            package br.pucpr.moderno;

            sealed interface Forma permits Circulo, Retangulo {}

            record Circulo(double raio) implements Forma {}

            record Retangulo(double largura, double altura) implements Forma {}

            final class Areas {
              static double area(Forma forma) {
                return switch (forma) {
                  case Circulo(var raio) -> Math.PI * raio * raio;
                  case Retangulo(double largura, _) when largura == 0 -> 0;
                  case Retangulo(var largura, var altura) -> largura * altura;
                };
              }

              static int seguro(String texto) {
                try {
                  return Integer.parseInt(texto);
                } catch (NumberFormatException _) {
                  return 0;
                }
              }
            }
            """);

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.declarations())
        .isEqualTo(
            new SourceFile.Declarations(
                "br.pucpr.moderno", List.of("Forma", "Circulo", "Retangulo", "Areas")));
  }

  @Test
  void importDeModuloNaoEErro() {
    final PreParse preParse =
        parse(
            "UsaModulo.java",
            """
            import module java.base;

            class UsaModulo {
              List<String> nomes = new ArrayList<>();
            }
            """);

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.empty()).isFalse();
    assertThat(preParse.declarations().topLevelTypes()).containsExactly("UsaModulo");
  }

  @Test
  void umArquivoCompactoComMainDeInstanciaNaoEErroETemAClasseImplicitaComONomeDoArquivo() {
    final PreParse preParse =
        parse(
            "src/Ola.java",
            """
            void main() {
              IO.println("olá");
            }
            """);

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.empty()).isFalse();
    assertThat(preParse.declarations()).isEqualTo(new SourceFile.Declarations("", List.of("Ola")));
  }

  @Test
  void instrucoesAntesDoSuperNoConstrutorNaoSaoErro() {
    final PreParse preParse =
        parse(
            "Filha.java",
            """
            class Mae {
              Mae(int valor) {}
            }

            class Filha extends Mae {
              Filha(int valor) {
                if (valor < 0) {
                  throw new IllegalArgumentException("negativo");
                }
                super(valor);
              }
            }
            """);

    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.declarations().topLevelTypes()).containsExactly("Mae", "Filha");
  }

  private static PreParse parse(String fileName, String source) {
    return SyntaxPreParser.parse(fileName, source.toCharArray());
  }

  private static void assertSyntaxError(PreParse preParse) {
    assertThat(preParse.syntaxError()).isTrue();
    assertThat(preParse.declarations()).isNull();
    assertThat(preParse.empty()).isFalse();
  }

  private static void assertEmpty(PreParse preParse) {
    assertThat(preParse.syntaxError()).isFalse();
    assertThat(preParse.empty()).isTrue();
    assertThat(preParse.moduleDescriptor()).isFalse();
    assertThat(preParse.declarations()).isEqualTo(new SourceFile.Declarations("", List.of()));
  }

  private static CompilerOptions spoonOptions() {
    final CompilerOptions options =
        new CompilerOptions(
            Map.of(
                CompilerOptions.OPTION_Compliance, "25",
                CompilerOptions.OPTION_Source, "25",
                CompilerOptions.OPTION_DocCommentSupport, CompilerOptions.ENABLED));
    options.parseLiteralExpressionsAsConstants = false;
    return options;
  }

  private static Parser parser(CompilerOptions options) {
    return new Parser(
        new ProblemReporter(
            DefaultErrorHandlingPolicies.proceedWithAllProblems(),
            options,
            new DefaultProblemFactory(Locale.ROOT)),
        false);
  }

  private static boolean hasErrors(
      Parser parser, CompilerOptions options, String fileName, String source) {
    final CompilationUnit unit = new CompilationUnit(source.toCharArray(), fileName, null);
    return parser
        .parse(unit, new CompilationResult(unit, 0, 1, options.maxProblemsPerUnit))
        .hasErrors();
  }
}
