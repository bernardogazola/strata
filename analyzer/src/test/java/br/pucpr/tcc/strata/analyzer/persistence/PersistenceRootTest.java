package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class PersistenceRootTest {

  @TempDir private Path temp;

  private Path real;

  @BeforeEach
  void raiz() throws Exception {
    real = temp.toRealPath();
  }

  private static String permissoes(Path path) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void criaRepositoriesSoParaODonoComAsPastasQueFaltamAcima() throws Exception {
    final Path dataDir = real.resolve("nao").resolve("existe").resolve("data");

    final PersistenceRoot root = PersistenceRoot.prepare(dataDir);

    assertThat(root.directory()).isEqualTo(dataDir.resolve(PersistenceRoot.DIRECTORY_NAME));
    assertThat(permissoes(root.directory())).isEqualTo("rwx------");
  }

  @Test
  void prepararDuasVezesReusaAMesmaPasta() throws Exception {
    final PersistenceRoot primeira = PersistenceRoot.prepare(real.resolve("data"));

    final PersistenceRoot segunda = PersistenceRoot.prepare(real.resolve("data"));

    assertThat(segunda.directory()).isEqualTo(primeira.directory()).isDirectory();
  }

  @Test
  void aPastaDevolvidaEOCaminhoRealMesmoQuandoODataDirNaoE() throws Exception {
    final PersistenceRoot root = PersistenceRoot.prepare(temp.resolve("data"));

    assertThat(root.directory()).isEqualTo(root.directory().toRealPath());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void umLinkNoLugarDeRepositoriesERecusado() throws Exception {
    final Path dataDir = Files.createDirectories(real.resolve("data"));
    final Path alvo = Files.createDirectories(real.resolve("repositorio-analisado"));
    Files.createSymbolicLink(dataDir.resolve(PersistenceRoot.DIRECTORY_NAME), alvo);

    assertThatThrownBy(() -> PersistenceRoot.prepare(dataDir))
        .as("um link levaria toda escrita de cache para onde quem o plantou quisesse")
        .isInstanceOf(IOException.class)
        .hasMessageContaining("não é um diretório de verdade");
    assertThat(alvo).isEmptyDirectory();
  }

  @Test
  void umArquivoNoLugarDeRepositoriesERecusado() throws Exception {
    final Path dataDir = Files.createDirectories(real.resolve("data"));
    Files.writeString(dataDir.resolve(PersistenceRoot.DIRECTORY_NAME), "não é pasta");

    assertThatThrownBy(() -> PersistenceRoot.prepare(dataDir))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("não é um diretório de verdade");
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "o Windows proíbe '?' em nomes")
  void umCaminhoComInterrogacaoERecusadoPorqueAUrlDoJdbcOCortaria() throws Exception {
    final Path dataDir = real.resolve("a?journal_mode=delete").resolve("data");

    assertThatThrownBy(() -> PersistenceRoot.prepare(dataDir))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("URL JDBC");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aPastaDeUmCacheNasceSoParaODonoESeReusa() throws Exception {
    final PersistenceRoot root = PersistenceRoot.prepare(real.resolve("data"));

    final Path pasta = root.cacheDirectory(TestCaches.identityV01().key());

    assertThat(pasta).isEqualTo(root.directory().resolve(TestCaches.V01_KEY));
    assertThat(permissoes(pasta)).isEqualTo("rwx------");
    assertThat(root.cacheDirectory(TestCaches.identityV01().key())).isEqualTo(pasta);
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void umLinkPlantadoNoLugarDaPastaDeUmCacheERecusado() throws Exception {
    final PersistenceRoot root = PersistenceRoot.prepare(real.resolve("data"));
    final Path alvo = Files.createDirectories(real.resolve("outro-lugar"));
    Files.createSymbolicLink(root.directory().resolve(TestCaches.V01_KEY), alvo);

    assertThatThrownBy(() -> root.cacheDirectory(TestCaches.identityV01().key()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("não é um diretório de verdade");
    assertThat(alvo).isEmptyDirectory();
  }

  @Test
  void dentroDoRepositorioEPorComponenteENaoPorPrefixoDeTexto() throws Exception {
    final Path home = real.resolve("home");
    final PersistenceRoot root = PersistenceRoot.prepare(home.resolve("Library").resolve("Strata"));

    assertThat(root.isInside(home)).isTrue();
    assertThat(root.isInside(root.directory())).isTrue();
    assertThat(root.isInside(real.resolve("hom"))).as("prefixo de texto não é ancestral").isFalse();
    assertThat(root.isInside(real.resolve("outro"))).isFalse();
    assertThat(root.isInside(root.directory().resolve(TestCaches.V01_KEY))).isFalse();
  }
}
