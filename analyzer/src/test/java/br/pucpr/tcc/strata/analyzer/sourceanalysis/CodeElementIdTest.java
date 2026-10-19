package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.Launcher;
import spoon.reflect.code.CtLambda;
import spoon.reflect.declaration.CtAnonymousExecutable;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

final class CodeElementIdTest {

  private static final String E_ACUTE = Character.toString(0xE9);

  private static final String EMOJI = Character.toString(0x1_F6_00);

  private static final String LAST_BMP = Character.toString(0xFF_FD);

  private static final UnaryOperator<String> AS_IS = UnaryOperator.identity();

  private static Factory factory;

  @BeforeAll
  static void build(@TempDir Path emptyClasspath) {
    final Launcher launcher = SpoonLaunchers.configured(emptyClasspath, () -> false);
    for (final Map.Entry<String, String> source : SOURCES.entrySet()) {
      launcher.addInputResource(new VirtualFile(source.getValue(), source.getKey()));
    }
    launcher.buildModel();
    factory = launcher.getFactory();
  }

  @Test
  void cadaTipoDeElementoTemOSeuPrefixo() {
    assertThat(CodeElementId.ofPackage("br.pucpr").value()).isEqualTo("pkg:br.pucpr");
    assertThat(CodeElementId.ofPackage("").value()).isEqualTo("pkg:");
    assertThat(CodeElementId.ofPackage("").sortKey()).isEmpty();
    assertThat(CodeElementId.ofType("a.b.Order$Builder").value())
        .isEqualTo("type:a.b.Order$Builder");
    assertThat(
            CodeElementId.ofMethod("a.b.Order", "find", List.of("java.lang.String", "int")).value())
        .isEqualTo("method:a.b.Order#find(java.lang.String,int)");
    assertThat(CodeElementId.ofFile("src/main/java/A.java").value())
        .isEqualTo("file:src/main/java/A.java");
    assertThat(
            List.of(
                    CodeElementId.ofPackage("a"),
                    CodeElementId.ofType("a.A"),
                    CodeElementId.ofMethod("a.A", "m", List.of()),
                    CodeElementId.ofFile("A.java"))
                .stream()
                .map(CodeElementId::kind))
        .containsExactly(
            ElementKind.PACKAGE, ElementKind.TYPE, ElementKind.METHOD, ElementKind.FILE);
  }

  @Test
  void umTipoAninhadoUsaDolarNoIdEPontoNoNomeCanonico() {
    final CtType<?> builder = type("a.b.Order$Builder");
    final CtType<?> deep = type("a.b.Order$Builder$Deep");
    final CtType<?> root = type("Root");

    assertThat(ElementIds.idName(builder)).isEqualTo("a.b.Order$Builder");
    assertThat(ElementIds.canonicalName(builder)).isEqualTo("a.b.Order.Builder");
    assertThat(ElementIds.idName(deep)).isEqualTo("a.b.Order$Builder$Deep");
    assertThat(ElementIds.canonicalName(deep)).isEqualTo("a.b.Order.Builder.Deep");
    assertThat(ElementIds.packageName(deep)).isEqualTo("a.b");
    assertThat(ElementIds.idName(root)).isEqualTo("Root");
    assertThat(ElementIds.canonicalName(root)).isEqualTo("Root");
    assertThat(ElementIds.packageName(root)).isEmpty();
  }

  @Test
  void oNomeCanonicoSaiDaCadeiaENuncaDeTrocarDolar() {
    final CtType<?> inner = type("a.b.Odd$Name$In");

    assertThat(ElementIds.idName(inner)).isEqualTo("a.b.Odd$Name$In");
    assertThat(ElementIds.canonicalName(inner)).isEqualTo("a.b.Odd$Name.In");
  }

  @Test
  void construtorEBlocoEstaticoTemNomeDaJvmEBlocoDeInstanciaELambdaNaoTemNome() {
    final CtType<?> inits = type("a.b.Inits");
    final List<CtAnonymousExecutable> blocks =
        inits.getElements(new TypeFilter<>(CtAnonymousExecutable.class));

    assertThat(
            ElementIds.methodName(
                inits
                    .getElements(new TypeFilter<CtConstructor<?>>(CtConstructor.class))
                    .getFirst()))
        .contains("<init>");
    assertThat(blocks).hasSize(2);
    assertThat(blocks.stream().filter(CtAnonymousExecutable::isStatic).map(ElementIds::methodName))
        .containsExactly(Optional.of("<clinit>"));
    assertThat(blocks.stream().filter(block -> !block.isStatic()).map(ElementIds::methodName))
        .containsExactly(Optional.empty());
    assertThat(ElementIds.methodName(method(inits, "work"))).contains("work");
    assertThat(
            inits.getElements(new TypeFilter<CtLambda<?>>(CtLambda.class)).stream()
                .map(ElementIds::methodName))
        .containsExactly(Optional.empty());
  }

  @Test
  void varargsApagaParaVetor() {
    final CtType<?> erasure = type("a.b.Erasure");

    assertThat(parameters(method(erasure, "varargs"))).containsExactly("java.lang.Object[]");
    assertThat(parameters(method(erasure, "nestedVarargs")))
        .containsExactly("java.lang.String[][]");
    assertThat(parameters(method(erasure, "primitiveVarargs"))).containsExactly("int[]");
  }

  @Test
  void umaVariavelDeTipoApagaParaOPrimeiroLimiteSenaoParaObject() {
    final CtType<?> erasure = type("a.b.Erasure");

    assertThat(parameters(method(erasure, "bounded"))).containsExactly("java.lang.Number");
    assertThat(parameters(method(erasure, "intersection"))).containsExactly("java.lang.Number");
    assertThat(parameters(method(erasure, "free"))).containsExactly("java.lang.Object");
    assertThat(parameters(method(erasure, "ofClass"))).containsExactly("java.lang.Object");
    assertThat(parameters(method(erasure, "chained"))).containsExactly("java.lang.Number");
    assertThat(parameters(method(erasure, "array"))).containsExactly("java.lang.Number[]");
    assertThat(parameters(method(erasure, "generic"))).containsExactly("java.util.List");
  }

  @Test
  void umLimiteCiclicoApagaParaObjectSemEstourarAPilha() {
    final CtMethod<?> method = factory.Core().createMethod();
    final CtTypeParameter a = factory.Core().createTypeParameter();
    a.setSimpleName("A");
    final CtTypeParameter b = factory.Core().createTypeParameter();
    b.setSimpleName("B");
    method.addFormalCtTypeParameter(a);
    method.addFormalCtTypeParameter(b);
    a.setSuperclass(factory.Type().createTypeParameterReference("B"));
    b.setSuperclass(factory.Type().createTypeParameterReference("A"));
    final CtTypeParameter self = factory.Core().createTypeParameter();
    self.setSimpleName("S");
    method.addFormalCtTypeParameter(self);
    self.setSuperclass(factory.Type().createTypeParameterReference("S"));
    final CtParameter<Object> parameter = factory.Core().createParameter();
    method.addParameter(parameter);
    final CtTypeParameterReference first = factory.Type().createTypeParameterReference("A");
    parameter.setType(first);

    assertThat(ElementIds.erase(parameter.getType(), AS_IS)).isEqualTo("java.lang.Object");
    parameter.setType(factory.Type().createTypeParameterReference("S"));
    assertThat(ElementIds.erase(parameter.getType(), AS_IS)).isEqualTo("java.lang.Object");
  }

  @Test
  void umaVariavelDeTipoSemDeclaracaoOuUmTipoNuloApagamParaObject() {
    assertThat(ElementIds.erase(factory.Type().createTypeParameterReference("T"), AS_IS))
        .isEqualTo("java.lang.Object");
    assertThat(ElementIds.erase(null, AS_IS)).isEqualTo("java.lang.Object");
  }

  @Test
  void umTipoLocalNoParametroViraONomeSimplesDaFonte() {
    final CtMethod<?> self =
        type("a.b.Host").getElements(new TypeFilter<CtMethod<?>>(CtMethod.class)).stream()
            .filter(candidate -> candidate.getSimpleName().equals("self"))
            .findFirst()
            .orElseThrow();

    assertThat(parameters(self)).containsExactly("Local");
  }

  @Test
  void oNomeQueOSpoonDeuPassaPelaCanonicalizacaoDaUnidade() {
    final List<String> seen = new ArrayList<>();

    final String erased =
        ElementIds.erase(
            method(type("a.b.Erasure"), "generic").getParameters().getFirst().getType(),
            name -> {
              seen.add(name);
              return "canonical." + name;
            });

    assertThat(seen).containsExactly("java.util.List");
    assertThat(erased).isEqualTo("canonical.java.util.List");
  }

  @Test
  void oTetoE4096BytesUtf8Inclusivo() {
    final CodeElementId atCeiling = CodeElementId.ofType(E_ACUTE.repeat(2_045) + "a");
    final CodeElementId overCeiling = CodeElementId.ofType(E_ACUTE.repeat(2_045) + "ab");
    final CodeElementId supplementary = CodeElementId.ofType(EMOJI.repeat(1_022) + "abc");

    assertThat(atCeiling.value().getBytes(StandardCharsets.UTF_8)).hasSize(4_096);
    assertThat(atCeiling.value()).hasSize(2_051);
    assertThat(atCeiling.utf8Length()).isEqualTo(AnalysisConstants.MAX_ELEMENT_ID_BYTES);
    assertThat(atCeiling.fitsCeiling()).isTrue();
    assertThat(overCeiling.utf8Length()).isEqualTo(4_097);
    assertThat(overCeiling.fitsCeiling()).isFalse();
    assertThat(supplementary.value().getBytes(StandardCharsets.UTF_8)).hasSize(4_096);
    assertThat(supplementary.fitsCeiling()).isTrue();
  }

  @Test
  void aOrdemEDePontoDeCodigoInclusiveAcimaDeUmaUnidadeUtf16() {
    final CodeElementId high = CodeElementId.ofType("a." + EMOJI);
    final CodeElementId low = CodeElementId.ofType("a." + LAST_BMP);

    final List<CodeElementId> sorted = new ArrayList<>(List.of(high, low));
    sorted.sort(Comparator.naturalOrder());

    assertThat(high.value().compareTo(low.value())).isNegative();
    assertThat(sorted).containsExactly(low, high);
    assertThat(CodeElementId.compareCodePoints(low.value(), high.value())).isNegative();
  }

  @Test
  void oCaminhoDoArquivoSaiEmNfc() {
    final String nfd = "src/Ca" + Character.toString(0x03_03) + "o.java";
    final String nfc = "src/C" + Character.toString(0xE3) + "o.java";

    assertThat(CodeElementId.ofFile(nfd).value()).isEqualTo("file:" + nfc);
    assertThat(CodeElementId.ofFile(nfd)).isEqualTo(CodeElementId.ofFile(nfc));
  }

  @Test
  void umCaminhoAbsolutoERecusadoEUmaBarraInvertidaENomeDeArquivo() {
    assertThatThrownBy(() -> CodeElementId.ofFile("/home/u/A.java"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(CodeElementId.ofFile("src/We\\ird.java").value()).isEqualTo("file:src/We\\ird.java");
  }

  @Test
  void oRotuloCortaEmPontosDeCodigoSemPartirUmPar() {
    final String label = ElementIds.label("a" + EMOJI.repeat(600));

    assertThat(label.codePointCount(0, label.length()))
        .isEqualTo(AnalysisConstants.MAX_LABEL_CHARS);
    assertThat(label).hasSize(1 + 2 * 511);
    assertThat(Character.isLowSurrogate(label.charAt(label.length() - 1))).isTrue();
    assertThat(ElementIds.label("Order")).isEqualTo("Order");
  }

  private static final Map<String, String> SOURCES =
      Map.of(
          "a/b/Order.java",
          """
          package a.b;

          public class Order {
            public static class Builder {
              class Deep {}
            }
          }
          """,
          "a/b/Odd.java",
          """
          package a.b;

          class Odd$Name {
            class In {}
          }
          """,
          "Root.java",
          """
          class Root {}
          """,
          "a/b/Inits.java",
          """
          package a.b;

          class Inits {
            static int a;
            int b;

            static {
              a = 1;
            }

            {
              b = 2;
            }

            Inits() {}

            void work() {
              Runnable r = () -> {};
            }
          }
          """,
          "a/b/Erasure.java",
          """
          package a.b;

          import java.util.List;

          class Erasure<E> {
            void varargs(Object... args) {}

            void nestedVarargs(String[]... rows) {}

            void primitiveVarargs(int... values) {}

            <T extends Number> void bounded(T value) {}

            <T extends Number & Comparable<T>> void intersection(T value) {}

            <T> void free(T value) {}

            void ofClass(E value) {}

            <K extends Number, V extends K> void chained(V value) {}

            <T extends Number> void array(T[] values) {}

            void generic(List<String> names) {}
          }
          """,
          "a/b/Host.java",
          """
          package a.b;

          class Host {
            void scope() {
              class Local {
                void self(Local other) {}
              }
            }
          }
          """);

  private static CtType<?> type(String qualifiedName) {
    final CtType<?> type = factory.Type().get(qualifiedName);
    assertThat(type).as(qualifiedName).isNotNull();
    return type;
  }

  private static CtMethod<?> method(CtType<?> type, String name) {
    return type.getMethodsByName(name).getFirst();
  }

  private static List<String> parameters(CtExecutable<?> executable) {
    return executable.getParameters().stream()
        .map(parameter -> ElementIds.erase(parameter.getType(), AS_IS))
        .toList();
  }
}
