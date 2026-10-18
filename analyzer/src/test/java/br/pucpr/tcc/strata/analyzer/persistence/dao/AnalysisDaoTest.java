package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase.CONFIG_HASH;
import static br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStatus;
import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sqlite.SQLiteErrorCode;

final class AnalysisDaoTest {

  private static final String HEAD = "fa7da60406a5f3ea0cbf4335426a66d992d3c3d1";
  private static final String SOURCE_FINGERPRINT = "f".repeat(64);

  @TempDir private Path temp;

  private static AnalysisRecord registro(Connection banco, String analysisId) throws SQLException {
    return AnalysisDao.findByAnalysisId(banco, analysisId).orElseThrow();
  }

  @Test
  void insertRunningDevolveAChaveDaLinhaRunningComOsContadoresZerados() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThat(
              DaoTestDatabase.scalar(
                  banco, "SELECT id FROM analysis WHERE analysis_id = ?", "an_01"))
          .isEqualTo(Long.toString(pk));
      assertThat(registro(banco, "an_01").status()).isEqualTo(AnalysisStatus.RUNNING);
      assertThat(registro(banco, "an_01").counters()).isEqualTo(AnalysisCounters.none());
    }
  }

  @Test
  void duasAnalisesRecebemChavesDiferentes() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      final long segunda = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_02"));

      assertThat(segunda).isNotEqualTo(primeira);
      assertThat(registro(banco, "an_02").pk()).isEqualTo(segunda);
    }
  }

  @Test
  void oMesmoAnalysisIdDuasVezesERecusado() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThatThrownBy(() -> AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01")))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE));
    }
  }

  @Test
  void leTodasAsColunasComOsNulosComoNull() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThat(AnalysisDao.findByAnalysisId(banco, "an_01"))
          .contains(
              new AnalysisRecord(
                  pk,
                  "an_01",
                  AnalysisStatus.RUNNING,
                  NOW,
                  null,
                  null,
                  "0.1.0",
                  1,
                  1,
                  CONFIG_HASH,
                  null,
                  false,
                  HistoryScope.ALL,
                  null,
                  AnalysisCounters.none(),
                  null,
                  null));
    }
  }

  @Test
  void leTodasAsColunasPreenchidas() throws Exception {
    final NewAnalysis completa =
        new NewAnalysis(
            "an_01",
            NOW,
            HEAD,
            "0.2.0",
            1,
            2,
            CONFIG_HASH,
            "{\"includeTests\":true}",
            SOURCE_FINGERPRINT,
            true,
            HistoryScope.LAST_N_COMMITS,
            500);
    final AnalysisCounters contadores = new AnalysisCounters(10, 4, 2, true, 1_234);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, completa);
      AnalysisDao.updateCounters(banco, pk, contadores);
      AnalysisDao.finish(
          banco, pk, AnalysisStatus.FAILED, NOW + 7, "INTERNAL_UNEXPECTED", "fixed sentence");

      assertThat(AnalysisDao.findByAnalysisId(banco, "an_01"))
          .contains(
              new AnalysisRecord(
                  pk,
                  "an_01",
                  AnalysisStatus.FAILED,
                  NOW,
                  NOW + 7,
                  HEAD,
                  "0.2.0",
                  1,
                  2,
                  CONFIG_HASH,
                  SOURCE_FINGERPRINT,
                  true,
                  HistoryScope.LAST_N_COMMITS,
                  500,
                  contadores,
                  "INTERNAL_UNEXPECTED",
                  "fixed sentence"));
    }
  }

  @Test
  void umaAnaliseDesconhecidaDevolveVazio() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThat(AnalysisDao.findByAnalysisId(banco, "an_02")).isEmpty();
    }
  }

  @Test
  void contadoresQueDescemPassamNumUpdateSoOndeColunaPorColunaSeriamRecusados() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      AnalysisDao.updateCounters(banco, pk, new AnalysisCounters(10, 5, 2, false, null));

      assertThatThrownBy(
              () ->
                  DaoTestDatabase.execute(
                      banco, "UPDATE analysis SET total_files = ? WHERE id = ?", 1, pk))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK));
      AnalysisDao.updateCounters(banco, pk, new AnalysisCounters(1, 1, 1, true, 42));

      assertThat(registro(banco, "an_01").counters())
          .isEqualTo(new AnalysisCounters(1, 1, 1, true, 42));
    }
  }

  @ParameterizedTest
  @CsvSource({"1, 2, 0", "5, 2, 3", "0, 0, -1"})
  void oRecordRecusaContadoresForaDoAninhamentoAntesDoBanco(int total, int ignorados, int falhas) {
    assertThatThrownBy(() -> new AnalysisCounters(total, ignorados, falhas, false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"1, 2, 0", "5, 2, 3", "0, 0, -1"})
  void oCheckDoBancoRecusaOsMesmosContadores(int total, int ignorados, int falhas)
      throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThatThrownBy(
              () ->
                  DaoTestDatabase.execute(
                      banco,
                      "UPDATE analysis SET total_files = ?, skipped_files = ?, parse_failures = ?"
                          + " WHERE id = ?",
                      total,
                      ignorados,
                      falhas,
                      pk))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK));
      assertThat(registro(banco, "an_01").counters()).isEqualTo(AnalysisCounters.none());
    }
  }

  @Test
  void contadoresDeUmaAnaliseInexistenteLancam() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThatThrownBy(() -> AnalysisDao.updateCounters(banco, 99, AnalysisCounters.none()))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("99");
    }
  }

  @Test
  void finishEncerraUmaAnaliseRunning() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      AnalysisDao.finish(banco, pk, AnalysisStatus.COMPLETED, NOW + 3, null, null);

      final AnalysisRecord encerrada = registro(banco, "an_01");
      assertThat(encerrada.status()).isEqualTo(AnalysisStatus.COMPLETED);
      assertThat(encerrada.finishedAt()).isEqualTo(NOW + 3);
      assertThat(encerrada.failureCode()).isNull();
      assertThat(encerrada.failureDetail()).isNull();
    }
  }

  @Test
  void umSegundoFinishERecusadoEOPrimeiroDesfechoFica() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      AnalysisDao.finish(banco, pk, AnalysisStatus.CANCELLED, NOW + 3, null, null);

      assertThatThrownBy(
              () ->
                  AnalysisDao.finish(
                      banco, pk, AnalysisStatus.FAILED, NOW + 9, "INTERNAL_UNEXPECTED", "x"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("não está RUNNING");

      final AnalysisRecord encerrada = registro(banco, "an_01");
      assertThat(encerrada.status()).isEqualTo(AnalysisStatus.CANCELLED);
      assertThat(encerrada.finishedAt()).isEqualTo(NOW + 3);
      assertThat(encerrada.failureCode()).isNull();
    }
  }

  @Test
  void finishDeUmaAnaliseInexistenteLanca() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThatThrownBy(
              () -> AnalysisDao.finish(banco, 99, AnalysisStatus.COMPLETED, NOW, null, null))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("não está RUNNING");
    }
  }

  @Test
  void finishRecusaUmStatusQueNaoEncerra() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThatThrownBy(
              () -> AnalysisDao.finish(banco, pk, AnalysisStatus.RUNNING, NOW, null, null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(registro(banco, "an_01").finishedAt()).isNull();
    }
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      value = {
        "FAILED, NULL, NULL",
        "FAILED, INTERNAL_UNEXPECTED, NULL",
        "FAILED, NULL, fixed sentence",
        "COMPLETED, INTERNAL_UNEXPECTED, fixed sentence",
        "CANCELLED, NULL, fixed sentence"
      })
  void codigoEDetalheDeFalhaSoAcompanhamFailed(AnalysisStatus status, String codigo, String detalhe)
      throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThatThrownBy(() -> AnalysisDao.finish(banco, pk, status, NOW, codigo, detalhe))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(registro(banco, "an_01").status()).isEqualTo(AnalysisStatus.RUNNING);
    }
  }
}
