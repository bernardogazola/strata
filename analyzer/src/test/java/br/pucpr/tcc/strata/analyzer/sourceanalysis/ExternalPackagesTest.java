package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExternalPackagesTest {

  private static final int PINNED_FEATURE = 25;

  private static final char COMMENT = '#';

  @Test
  void oRecursoEExatamenteAsExportacoesNaoQualificadasDoJdk25() throws IOException {
    assumeTrue(
        Runtime.version().feature() == PINNED_FEATURE,
        "jdk-packages.txt é a lista de exports do JDK 25; esta JVM é a "
            + Runtime.version()
            + ", então a comparação acusaria uma troca de JDK, e não um defeito");

    final List<String> expected =
        ModuleFinder.ofSystem().findAll().stream()
            .map(ModuleReference::descriptor)
            .filter(module -> module.name().startsWith("java.") || module.name().startsWith("jdk."))
            .flatMap(module -> module.exports().stream())
            .filter(export -> !export.isQualified())
            .map(ModuleDescriptor.Exports::source)
            .distinct()
            .sorted(CodeElementId::compareCodePoints)
            .toList();

    assertThat(expected).as("pré-condição: o JDK em execução exporta pacotes").isNotEmpty();
    assertThat(packageLines("/jdk-packages.txt"))
        .as("as linhas do recurso, na ordem do arquivo: iguais, ordenadas e sem repetição")
        .containsExactlyElementsOf(expected);
    assertThat(ExternalPackages.load().jdkPackages()).containsExactlyElementsOf(expected);
  }

  @Test
  void aListaDeExclusaoEJavaLangEJavaUtilDentroDoJdk() throws IOException {
    final ExternalPackages packages = ExternalPackages.load();

    assertThat(packageLines("/external-denylist.txt"))
        .as("exatamente java.lang e java.util, na ordem do arquivo")
        .containsExactly("java.lang", "java.util");
    assertThat(packages.denylistedPackages()).containsExactly("java.lang", "java.util");
    assertThat(packages.jdkPackages()).containsAll(packages.denylistedPackages());
  }

  @Test
  void aPertencaAoJdkEPeloPacoteExato() {
    final ExternalPackages packages = ExternalPackages.load();

    assertThat(
            List.of(
                "java.lang.String",
                "java.util.Map$Entry",
                "java.util.concurrent.Callable",
                "javax.annotation.processing.Processor",
                "org.w3c.dom.Document",
                "org.xml.sax.SAXException",
                "sun.misc.Unsafe",
                "java.net.http.HttpClient"))
        .allMatch(packages::isJdk);
    assertThat(
            List.of(
                "javax.servlet.http.HttpServlet",
                "javax.annotation.PostConstruct",
                "javax.inject.Inject",
                "javax.xml.bind.JAXBContext",
                "jdk.internal.misc.Unsafe",
                "java.utilities.Tool",
                "com.thirdparty.Missing",
                "Config"))
        .noneMatch(packages::isJdk);
    assertThat(packages.isJdkPackage("javax.annotation.processing")).isTrue();
    assertThat(packages.isJdkPackage("javax.annotation")).isFalse();
    assertThat(packages.isJdkPackage("java")).isFalse();
  }

  @Test
  void aExclusaoEPeloPacoteExatoDoAlvo() {
    final ExternalPackages packages = ExternalPackages.load();

    assertThat(
            List.of(
                "java.lang.String", "java.util.List", "java.util.Map$Entry", "java.lang.Integer"))
        .allMatch(name -> packages.isDenylisted(CodeElementId.ofType(name)));
    assertThat(
            List.of(
                "java.util.concurrent.Callable",
                "java.lang.reflect.Method",
                "javax.annotation.processing.Processor",
                "com.thirdparty.Missing",
                "Config"))
        .noneMatch(name -> packages.isDenylisted(CodeElementId.ofType(name)));
  }

  private static List<String> packageLines(String resource) throws IOException {
    try (InputStream in = ExternalPackagesTest.class.getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      final BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      final List<String> lines = new ArrayList<>();
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        final String name = line.strip();
        if (!name.isEmpty() && name.charAt(0) != COMMENT) {
          lines.add(name);
        }
      }
      return lines;
    }
  }
}
