package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PomReaderTest {

  private static final String SECRET = "TOP-SECRET-CONTENT";

  @TempDir private Path temp;

  private static byte[] utf8(String xml) {
    return xml.getBytes(StandardCharsets.UTF_8);
  }

  private static String fileUrl(Path file) {
    final String slashed = file.toAbsolutePath().toString().replace('\\', '/');
    return "file://" + (slashed.startsWith("/") ? "" : "/") + slashed;
  }

  private static List<String> modulesReadByTheDefaultFactory(byte[] content) throws Exception {
    final XMLStreamReader reader =
        XMLInputFactory.newDefaultFactory()
            .createXMLStreamReader(new ByteArrayInputStream(content));
    final List<String> modules = new ArrayList<>();
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT
          && "module".equals(reader.getLocalName())) {
        modules.add(reader.getElementText());
      }
    }
    reader.close();
    return modules;
  }

  @Test
  void leOsModulosDoTopoNaOrdemDoDocumentoComNamespacePadrao() throws Exception {
    final PomReader.Pom pom =
        PomReader.read(
            utf8(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modules>
                    <module>  core  </module>
                    <module>api</module>
                    <module></module>
                  </modules>
                </project>
                """));

    assertThat(pom.modules())
        .as("brancos das pontas fora, vazio descartado")
        .containsExactly("core", "api");
    assertThat(pom.allModules()).containsExactly("core", "api");
  }

  @Test
  void prefixoDeNamespaceNaoMudaALeitura() throws Exception {
    final PomReader.Pom pom =
        PomReader.read(
            utf8(
                """
                <pom:project xmlns:pom="http://maven.apache.org/POM/4.0.0">
                  <pom:modules><pom:module>core</pom:module></pom:modules>
                </pom:project>
                """));

    assertThat(pom.modules()).containsExactly("core");
  }

  @Test
  void moduloComentadoNaoELidoEOEmCdataE() throws Exception {
    final PomReader.Pom pom =
        PomReader.read(
            utf8(
                """
                <project>
                  <modules>
                    <!-- <module>commented-out</module> -->
                    <module><![CDATA[legacy]]></module>
                    <module>co<!-- partido -->re</module>
                  </modules>
                </project>
                """));

    assertThat(pom.modules())
        .as("o comentário no meio do valor não o parte em dois")
        .containsExactly("legacy", "core");
  }

  @Test
  void modulosDePerfilSaoLidosDepoisDosDoTopoSemRepetir() throws Exception {
    final PomReader.Pom pom =
        PomReader.read(
            utf8(
                """
                <project>
                  <profiles>
                    <profile><modules><module>extra</module><module>core</module></modules></profile>
                    <profile><modules><module>tools</module></modules></profile>
                  </profiles>
                  <modules><module>core</module><module>api</module></modules>
                </project>
                """));

    assertThat(pom.modules()).containsExactly("core", "api");
    assertThat(pom.profileModules()).containsExactly("extra", "core", "tools");
    assertThat(pom.allModules())
        .as("o topo primeiro, mesmo vindo depois no documento; o repetido some")
        .containsExactly("core", "api", "extra", "tools");
  }

  @Test
  void modulesForaDeProjectModulesNaoSaoLidos() throws Exception {
    final PomReader.Pom pom =
        PomReader.read(
            utf8(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <configuration><modules><module>plugin-config</module></modules></configuration>
                      </plugin>
                    </plugins>
                    <modules><module>under-build</module></modules>
                  </build>
                  <reporting><modules><module>under-reporting</module></modules></reporting>
                  <module>loose</module>
                  <modules><module>real</module></modules>
                </project>
                """));

    assertThat(pom.allModules()).containsExactly("real");
  }

  @Test
  void lePastasDeCodigoDeclaradasEmBuild() throws Exception {
    final PomReader.Pom pom =
        PomReader.read(
            utf8(
                """
                <project>
                  <build>
                    <sourceDirectory>${project.basedir}/src/java</sourceDirectory>
                    <testSourceDirectory>test</testSourceDirectory>
                  </build>
                  <profiles><profile><build>
                    <sourceDirectory>profile-only</sourceDirectory>
                  </build></profile></profiles>
                </project>
                """));

    assertThat(pom.sourceDirectories()).containsExactly("${project.basedir}/src/java");
    assertThat(pom.testSourceDirectories()).containsExactly("test");
  }

  @Test
  void aCodificacaoVemDaDeclaracaoXml() throws Exception {
    final byte[] latin1 =
        """
        <?xml version="1.0" encoding="ISO-8859-1"?>
        <project><modules><module>avaliação</module></modules></project>
        """
            .getBytes(StandardCharsets.ISO_8859_1);

    assertThat(PomReader.read(latin1).modules()).containsExactly("avaliação");
  }

  @Test
  void doctypeSemEntidadeNaoERecusado() throws Exception {
    final PomReader.Pom pom =
        PomReader.read(
            utf8(
                """
                <?xml version="1.0"?>
                <!DOCTYPE project>
                <project><modules><module>m&amp;n</module></modules></project>
                """));

    assertThat(pom.modules()).containsExactly("m&n");
  }

  @Test
  void raizQueNaoEProjectERecusada() {
    assertThatThrownBy(
            () ->
                PomReader.read(utf8("<settings><modules><module>a</module></modules></settings>")))
        .isInstanceOf(XMLStreamException.class);
  }

  @Test
  void documentoMalformadoERecusado() {
    assertThatThrownBy(() -> PomReader.read(utf8("<project><modules><module>a</module>")))
        .isInstanceOf(XMLStreamException.class);
    assertThatThrownBy(() -> PomReader.read(new byte[] {(byte) 0xFF, 0x00, 0x7F, '<'}))
        .isInstanceOf(XMLStreamException.class);
    assertThatThrownBy(() -> PomReader.read(new byte[0])).isInstanceOf(XMLStreamException.class);
  }

  @Test
  void xxeComEntidadeExternaERecusadoEOSegredoNuncaAparece() throws Exception {
    final Path secret = Files.writeString(temp.resolve("secret.txt"), SECRET);
    final byte[] attack =
        utf8(
            """
            <?xml version="1.0"?>
            <!DOCTYPE project [ <!ENTITY xxe SYSTEM "%s"> ]>
            <project><modules><module>&xxe;</module></modules></project>
            """
                .formatted(fileUrl(secret)));

    assertThat(modulesReadByTheDefaultFactory(attack))
        .as("controle: sem endurecimento, o StAX do JDK lê o arquivo local")
        .containsExactly(SECRET);
    assertThatThrownBy(() -> PomReader.read(attack))
        .isInstanceOf(XMLStreamException.class)
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void xxeComEntidadeDeParametroERecusado() throws Exception {
    final Path secret = Files.writeString(temp.resolve("secret.txt"), SECRET);
    final Path dtd =
        Files.writeString(
            temp.resolve("evil.dtd"), "<!ENTITY leak SYSTEM \"%s\">".formatted(fileUrl(secret)));
    final byte[] attack =
        utf8(
            """
            <?xml version="1.0"?>
            <!DOCTYPE project [ <!ENTITY %% remote SYSTEM "%s"> %%remote; ]>
            <project><modules><module>&leak;</module></modules></project>
            """
                .formatted(fileUrl(dtd)));

    assertThat(modulesReadByTheDefaultFactory(attack))
        .as("controle: sem endurecimento, a DTD externa é buscada e a entidade vaza")
        .containsExactly(SECRET);
    assertThatThrownBy(() -> PomReader.read(attack))
        .isInstanceOf(XMLStreamException.class)
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void billionLaughsERecusadoSemExpandir() {
    final StringBuilder entities = new StringBuilder("<!ENTITY lol0 \"lol\">");
    for (int level = 1; level <= 9; level++) {
      final String previous = "&lol" + (level - 1) + ";";
      entities.append("<!ENTITY lol").append(level).append(" \"").append(previous.repeat(10));
      entities.append("\">");
    }
    final byte[] attack =
        utf8(
            "<?xml version=\"1.0\"?><!DOCTYPE project ["
                + entities
                + "]><project><modules><module>&lol9;</module></modules></project>");

    assertThatThrownBy(() -> PomReader.read(attack)).isInstanceOf(XMLStreamException.class);
  }
}
