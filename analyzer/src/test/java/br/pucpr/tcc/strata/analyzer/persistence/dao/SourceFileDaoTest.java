package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteErrorCode;

final class SourceFileDaoTest {

  private static final long MTIME = 1_791_000_000_123L;

  private static final SourceFileRow ORDER =
      new SourceFileRow(1, "src/main/java/a/Order.java", 2_048, MTIME, SourceFileStatus.ANALYZED);
  private static final SourceFileRow GRANDE =
      new SourceFileRow(
          2, "src/main/java/a/Huge.java", 3_000_000, MTIME + 1, SourceFileStatus.SKIPPED_TOO_LARGE);
  private static final SourceFileRow QUEBRADO =
      new SourceFileRow(
          3, "src/main/java/a/Broken.java", 17, MTIME + 2, SourceFileStatus.PARSE_FAILED);

  @TempDir private Path temp;

  private static List<SourceFileRow> arquivos(long primeiro, int quantos) {
    return LongStream.range(primeiro, primeiro + quantos)
        .mapToObj(
            pk ->
                new SourceFileRow(
                    pk,
                    "src/main/java/p/T" + pk + ".java",
                    pk * 10,
                    MTIME + pk,
                    SourceFileStatus.ANALYZED))
        .toList();
  }

  @Test
  void numUmBancoVazioOPrimeiroIdEUm() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(SourceFileDao.nextId(banco)).isEqualTo(1);
    }
  }

  @Test
  void gravaELeOsArquivosComContagemDeLinhasEHashNulos() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      SourceFileDao.insert(banco, analise, List.of(ORDER, GRANDE, QUEBRADO));

      assertThat(SourceFileDao.list(banco, analise)).containsExactly(ORDER, GRANDE, QUEBRADO);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT count(*) FROM source_file"
                      + " WHERE line_count IS NULL AND content_hash IS NULL"))
          .isEqualTo("3");
    }
  }

  @Test
  void oCaminhoVaiNaGrafiaDoDiscoSemNormalizar() throws Exception {
    final String agudo = new String(Character.toChars(0x301));
    final SourceFileRow nfd =
        new SourceFileRow(
            1, "src/main/java/a/Cafe" + agudo + ".java", 10, MTIME, SourceFileStatus.ANALYZED);
    final SourceFileRow nfc =
        new SourceFileRow(
            2,
            "src/main/java/a/Caf" + new String(Character.toChars(0xE9)) + ".java",
            10,
            MTIME,
            SourceFileStatus.ANALYZED);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      SourceFileDao.insert(banco, analise, List.of(nfd, nfc));

      assertThat(SourceFileDao.list(banco, analise)).containsExactly(nfd, nfc);
    }
  }

  @ParameterizedTest(name = "{0} linhas")
  @ValueSource(ints = {0, 1, 500, 501, 1_001})
  void asFronteirasDasInstrucoesDeVariasLinhasVoltamExatas(int linhas) throws Exception {
    final List<SourceFileRow> esperados = arquivos(1, linhas);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      SourceFileDao.insert(banco, analise, esperados);

      assertThat(SourceFileDao.list(banco, analise)).isEqualTo(esperados);
      assertThat(SourceFileDao.nextId(banco)).isEqualTo(linhas + 1L);
    }
  }

  @Test
  void oProximoIdContinuaDepoisDosArquivosDeOutraAnalise() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      final long segunda = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_02"));
      SourceFileDao.insert(banco, primeira, List.of(ORDER, GRANDE));

      assertThat(SourceFileDao.nextId(banco)).isEqualTo(3);
      final SourceFileRow daSegunda =
          new SourceFileRow(3, ORDER.relativePath(), 4_096, MTIME + 9, SourceFileStatus.ANALYZED);
      SourceFileDao.insert(banco, segunda, List.of(daSegunda));

      assertThat(SourceFileDao.list(banco, primeira)).containsExactly(ORDER, GRANDE);
      assertThat(SourceFileDao.list(banco, segunda)).containsExactly(daSegunda);
    }
  }

  @Test
  void umaSegundaAnaliseNumeradaDeUmFalhaPelaChavePrimaria() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      final long segunda = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_02"));
      SourceFileDao.insert(banco, primeira, List.of(ORDER));

      assertThatThrownBy(() -> SourceFileDao.insert(banco, segunda, List.of(ORDER)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY));
    }
  }

  @Test
  void oMesmoCaminhoDuasVezesNaMesmaAnaliseERecusado() throws Exception {
    final SourceFileRow repetido =
        new SourceFileRow(2, ORDER.relativePath(), 1, MTIME, SourceFileStatus.PARSE_FAILED);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThatThrownBy(() -> SourceFileDao.insert(banco, analise, List.of(ORDER, repetido)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE));
    }
  }

  @Test
  void trocaODesfechoSoDoArquivoPedido() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      SourceFileDao.insert(banco, analise, List.of(ORDER, GRANDE));

      SourceFileDao.updateStatus(banco, 1, SourceFileStatus.PARSE_FAILED);

      assertThat(SourceFileDao.list(banco, analise))
          .containsExactly(
              new SourceFileRow(
                  1, "src/main/java/a/Order.java", 2_048, MTIME, SourceFileStatus.PARSE_FAILED),
              GRANDE);
    }
  }

  @Test
  void trocarODesfechoDeUmArquivoInexistenteLanca() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThatThrownBy(() -> SourceFileDao.updateStatus(banco, 99, SourceFileStatus.ANALYZED))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("99");
    }
  }

  @Test
  void umArquivoDeUmaAnaliseInexistenteERecusadoPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThatThrownBy(() -> SourceFileDao.insert(banco, 99, List.of(ORDER)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
    }
  }
}
