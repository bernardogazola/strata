package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SyntaxPreParser.PreParse;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
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

  @Test
  void aFormaSeparaOsImportsPorEspecieComoEscritos() {
    final UnitShape shape =
        parse(
                "p/Uso.java",
                """
                package p;

                import a.b.Unico;
                import a.b.Api.Interno;
                import a.c.*;
                import a.Api.*;
                import static a.Util.metodo;
                import static a.Util.*;

                class Uso {}
                """)
            .shape();

    assertThat(shape.packageName()).isEqualTo("p");
    assertThat(shape.singleTypeImports()).containsExactly("a.b.Unico", "a.b.Api.Interno");
    assertThat(shape.onDemandImports()).containsExactly("a.c", "a.Api");
    assertThat(shape.singleStaticImports()).containsExactly("a.Util.metodo");
    assertThat(shape.staticOnDemandImports()).containsExactly("a.Util");
    assertThat(shape.moduleImport()).isFalse();
  }

  @Test
  void umImportDeModuloMarcaAForma() {
    final UnitShape shape =
        parse("p/Modulo.java", "package p;\nimport module java.base;\nclass Modulo {}\n").shape();

    assertThat(shape.moduleImport()).isTrue();
    assertThat(shape.onDemandImports()).isEmpty();
  }

  @Test
  void aFormaDeUmTipoTemEspecieVisibilidadeESupertiposComoEscritos() {
    final UnitShape shape =
        parse(
                "p/Tipos.java",
                """
                package p;

                public class Tipos<K, V extends Comparable<V>>
                    extends java.util.AbstractMap<K, V> implements Map.Entry<K, V>, Cloneable {
                  interface Membro {}
                  enum Cor { VERMELHO }
                  record Par(int a) {}
                  @interface Marca {}
                  class Interna {}
                  private static class Escondida {}
                  protected class Protegida {}
                }
                """)
            .shape();
    final UnitShape.TypeShape tipos = shape.types().getFirst();

    assertThat(tipos.name()).isEqualTo("Tipos");
    assertThat(tipos.kind()).isEqualTo(TypeKind.CLASS);
    assertThat(MemberTable.visibility(tipos.modifiers())).isEqualTo(MemberTable.PUBLIC);
    assertThat(tipos.typeParameters()).containsExactly("K", "V");
    assertThat(tipos.superclass()).isEqualTo("java.util.AbstractMap");
    assertThat(tipos.superInterfaces()).containsExactly("Map.Entry", "Cloneable");
    assertThat(MemberTable.isStatic(member(tipos, "Membro").modifiers())).isTrue();
    assertThat(member(tipos, "Membro").kind()).isEqualTo(TypeKind.INTERFACE);
    assertThat(MemberTable.isStatic(member(tipos, "Cor").modifiers())).isTrue();
    assertThat(member(tipos, "Cor").kind()).isEqualTo(TypeKind.ENUM);
    assertThat(MemberTable.isStatic(member(tipos, "Par").modifiers())).isTrue();
    assertThat(member(tipos, "Marca").kind()).isEqualTo(TypeKind.ANNOTATION);
    assertThat(MemberTable.isStatic(member(tipos, "Interna").modifiers())).isFalse();
    assertThat(MemberTable.visibility(member(tipos, "Interna").modifiers()))
        .isEqualTo(MemberTable.PACKAGE);
    assertThat(MemberTable.visibility(member(tipos, "Escondida").modifiers()))
        .isEqualTo(MemberTable.PRIVATE);
    assertThat(MemberTable.isStatic(member(tipos, "Escondida").modifiers())).isTrue();
    assertThat(MemberTable.visibility(member(tipos, "Protegida").modifiers()))
        .isEqualTo(MemberTable.PROTECTED);
  }

  @Test
  void osMembrosDeUmTipoTemAsMarcasDasSobrecargas() {
    final UnitShape.TypeShape tipo =
        parse(
                "p/Metodos.java",
                """
                package p;

                class Metodos {
                  private void sobrecarga(int a) {}
                  public static void sobrecarga(String a) {}
                  void pacote() {}
                  protected int[] campo, outro[];
                  static final String CONSTANTE = "x";
                  Metodos() {}
                  static {}
                  { }
                  void local() { class Local {} new Object() {}; }
                }
                """)
            .shape()
            .types()
            .getFirst();

    assertThat(namesOf(tipo.methods())).containsExactly("local", "pacote", "sobrecarga");
    assertThat(tipo.memberTypes()).isEmpty();
    final byte sobrecarga = flagsOf(tipo.methods(), "sobrecarga");
    assertThat(MemberTable.visibility(sobrecarga)).isEqualTo(MemberTable.PUBLIC);
    assertThat(sobrecarga & MemberTable.STATIC).isNotZero();
    assertThat(sobrecarga & MemberTable.INSTANCE).isNotZero();
    assertThat(MemberTable.visibility(flagsOf(tipo.methods(), "pacote")))
        .isEqualTo(MemberTable.PACKAGE);
    assertThat(namesOf(tipo.fields())).containsExactly("CONSTANTE", "campo", "outro");
    assertThat(tipo.fieldTypes()).containsExactly("String", "int[]", "int[][]");
    assertThat(MemberTable.isStatic(flagsOf(tipo.fields(), "CONSTANTE"))).isTrue();
    assertThat(MemberTable.visibility(flagsOf(tipo.fields(), "campo")))
        .isEqualTo(MemberTable.PROTECTED);
  }

  @Test
  void osMembrosImplicitosDeInterfaceEnumERecordEntramNaForma() {
    final UnitShape shape =
        parse(
                "p/Implicitos.java",
                """
                package p;

                interface Contrato {
                  int LIMITE = 1;
                  void abstrato();
                  static void utilidade() {}
                  private void ajuda() {}
                  class Aninhada {}
                }

                enum Estado { LIGADO, DESLIGADO; void alterna() {} }

                record Ponto(int x, java.util.List<String> nomes) {}
                """)
            .shape();
    final UnitShape.TypeShape contrato = shape.types().get(0);
    final UnitShape.TypeShape estado = shape.types().get(1);
    final UnitShape.TypeShape ponto = shape.types().get(2);

    assertThat(MemberTable.visibility(flagsOf(contrato.fields(), "LIMITE")))
        .isEqualTo(MemberTable.PUBLIC);
    assertThat(MemberTable.isStatic(flagsOf(contrato.fields(), "LIMITE"))).isTrue();
    assertThat(MemberTable.visibility(flagsOf(contrato.methods(), "abstrato")))
        .isEqualTo(MemberTable.PUBLIC);
    assertThat(flagsOf(contrato.methods(), "utilidade") & MemberTable.INSTANCE).isZero();
    assertThat(MemberTable.visibility(flagsOf(contrato.methods(), "ajuda")))
        .isEqualTo(MemberTable.PRIVATE);
    assertThat(MemberTable.visibility(member(contrato, "Aninhada").modifiers()))
        .isEqualTo(MemberTable.PUBLIC);
    assertThat(MemberTable.isStatic(member(contrato, "Aninhada").modifiers())).isTrue();
    assertThat(namesOf(estado.methods())).containsExactly("alterna", "valueOf", "values");
    assertThat(namesOf(estado.fields())).containsExactly("DESLIGADO", "LIGADO");
    assertThat(estado.fieldTypes()).containsExactly("Estado", "Estado");
    assertThat(MemberTable.isStatic(flagsOf(estado.fields(), "LIGADO"))).isTrue();
    assertThat(namesOf(ponto.fields())).containsExactly("nomes", "x");
    assertThat(ponto.fieldTypes()).containsExactly("java.util.List", "int");
    assertThat(MemberTable.visibility(flagsOf(ponto.methods(), "x"))).isEqualTo(MemberTable.PUBLIC);
    assertThat(ponto.kind()).isEqualTo(TypeKind.RECORD);
  }

  @Test
  void oPackageInfoNaoTemTipoNaForma() {
    final PreParse preParse = parse("p/package-info.java", "@Deprecated\npackage p;\n");

    assertThat(preParse.shape().types()).isEmpty();
    assertThat(preParse.shape().packageName()).isEqualTo("p");
  }

  @Test
  void oInternadorDaExecucaoFazOMesmoTextoUmaStringSo() {
    final Map<String, String> interned = new HashMap<>();
    final UnaryOperator<String> interner =
        text -> {
          final String known = interned.putIfAbsent(text, text);
          return known == null ? text : known;
        };

    final UnitShape one =
        SyntaxPreParser.parse(
                "p/A.java", "package p;\nimport q.Comum;\nclass A {}\n".toCharArray(), interner)
            .shape();
    final UnitShape other =
        SyntaxPreParser.parse(
                "p/B.java", "package p;\nimport q.Comum;\nclass B {}\n".toCharArray(), interner)
            .shape();

    assertThat(one.singleTypeImports().getFirst()).isSameAs(other.singleTypeImports().getFirst());
    assertThat(one.packageName()).isSameAs(other.packageName());
  }

  private static PreParse parse(String fileName, String source) {
    return SyntaxPreParser.parse(fileName, source.toCharArray());
  }

  private static void assertSyntaxError(PreParse preParse) {
    assertThat(preParse.syntaxError()).isTrue();
    assertThat(preParse.declarations()).isNull();
    assertThat(preParse.shape()).isNull();
    assertThat(preParse.empty()).isFalse();
  }

  private static UnitShape.TypeShape member(UnitShape.TypeShape type, String name) {
    return type.memberTypes().stream()
        .filter(member -> member.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static byte flagsOf(MemberTable table, String name) {
    final int at = table.find(name);
    assertThat(at).as(name).isNotNegative();
    return table.flags(at);
  }

  private static List<String> namesOf(MemberTable table) {
    final List<String> names = new ArrayList<>();
    for (int at = 0; at < table.size(); at++) {
      names.add(table.name(at));
    }
    return names;
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
