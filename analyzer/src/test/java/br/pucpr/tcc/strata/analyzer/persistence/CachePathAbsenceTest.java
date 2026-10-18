package br.pucpr.tcc.strata.analyzer.persistence;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import br.pucpr.tcc.strata.analyzer.persistence.dao.AnalysisDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.NewAnalysis;
import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.RepositoryRecord;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StageDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.StageRecord;
import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CachePathAbsenceTest {

  private static final String DISPLAY_NAME = "strata-caminho-que-nunca-e-gravado-7f3e9b";

  private static final List<Charset> ENCODINGS = List.of(UTF_8, UTF_16LE, UTF_16BE);

  @TempDir private Path temp;

  @Test
  void nenhumArquivoDaPastaDoCacheGuardaOCaminhoDoRepositorio() throws Exception {
    final Path real = temp.toRealPath();
    final Path repositoryRoot =
        Files.createDirectories(real.resolve("projetos").resolve(DISPLAY_NAME));
    final String repositoryPath = repositoryRoot.toString();
    final RepositoryIdentity identity =
        RepositoryIdentity.of(repositoryPath, PathFlavor.host(), TestCaches.V01_ROOT);
    final PersistenceRoot root = PersistenceRoot.prepare(real.resolve("data"));
    final InstantSource clock = TestCaches.steppingClock();
    final List<String> proibidos = List.of(repositoryPath, repositoryRoot.getParent().toString());

    final CacheOpening primeiro =
        RepositoryCache.open(root, identity, repositoryRoot, "0.0.0-test", clock);
    registrar(primeiro.cache(), "an_%026d".formatted(1));
    final Path pasta = root.directory().resolve(identity.key().value());
    final Map<String, byte[]> aberto = arquivos(pasta);
    primeiro.cache().close();

    TestCaches.overwrite(pasta.resolve(RepositoryCache.DATABASE_FILE), 0, 512, 35L);
    final CacheOpening reconstruido =
        RepositoryCache.open(root, identity, repositoryRoot, "0.0.0-test", clock);
    assertThat(reconstruido).isInstanceOf(CacheOpening.Rebuilt.class);
    registrar(reconstruido.cache(), "an_%026d".formatted(2));
    reconstruido.cache().close();
    final Map<String, byte[]> depois = arquivos(pasta);

    assertThat(aberto).containsKeys("analysis.sqlite", "analysis.sqlite-wal", "analysis.lock");
    assertThat(aberto.get("analysis.sqlite-wal")).as("o -wal tinha quadros").isNotEmpty();
    assertThat(depois.keySet())
        .anyMatch(name -> name.matches("analysis\\.sqlite\\.\\d{13,}\\.bak"));
    for (final Map<String, byte[]> momento : List.of(aberto, depois)) {
      assertThat(contem(momento, DISPLAY_NAME))
          .as("controle positivo: o display_name está lá, então a busca funciona")
          .isNotEmpty();
      for (final String proibido : proibidos) {
        assertThat(contem(momento, proibido)).as("arquivos que guardam %s", proibido).isEmpty();
      }
    }
  }

  private static void registrar(RepositoryCache cache, String analysisId) throws Exception {
    final RepositoryIdentity identity = cache.identity();
    cache
        .connections()
        .write(
            c -> {
              RepositoryDao.upsert(
                  c,
                  new RepositoryRecord(
                      identity.key().value(),
                      DISPLAY_NAME,
                      identity.pathFingerprint().value(),
                      identity.rootCommitId(),
                      "main",
                      false,
                      BuildSystem.MAVEN,
                      1L,
                      2L));
              final long pk =
                  AnalysisDao.insertRunning(
                      c,
                      new NewAnalysis(
                          analysisId,
                          3L,
                          TestCaches.V01_ROOT,
                          "0.0.0-test",
                          1,
                          1,
                          "0".repeat(64),
                          "{\"includeTests\":false}",
                          null,
                          false,
                          HistoryScope.ALL,
                          null));
              StageDao.upsert(
                  c,
                  pk,
                  new StageRecord(
                      AnalysisStage.SOURCE_ANALYSIS, StageStatus.RUNNING, 4L, null, "{}"));
              return null;
            });
  }

  private static Map<String, byte[]> arquivos(Path pasta) throws IOException {
    final Map<String, byte[]> conteudo = new TreeMap<>();
    try (Stream<Path> entradas = Files.list(pasta)) {
      for (final Path entrada : entradas.toList()) {
        if (Files.isRegularFile(entrada, LinkOption.NOFOLLOW_LINKS)) {
          conteudo.put(entrada.getFileName().toString(), Files.readAllBytes(entrada));
        }
      }
    }
    return conteudo;
  }

  private static List<String> contem(Map<String, byte[]> arquivos, String texto) {
    final List<String> achados = new ArrayList<>();
    for (final Map.Entry<String, byte[]> arquivo : arquivos.entrySet()) {
      final String bytes = new String(arquivo.getValue(), ISO_8859_1);
      for (final Charset encoding : ENCODINGS) {
        if (bytes.contains(new String(texto.getBytes(encoding), ISO_8859_1))) {
          achados.add(arquivo.getKey() + " (" + encoding + ")");
        }
      }
    }
    return List.copyOf(achados);
  }
}
