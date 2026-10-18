package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;

final class StageDaoTest {

  @TempDir private Path temp;

  private static StageRecord pendente(AnalysisStage estagio) {
    return new StageRecord(estagio, StageStatus.PENDING, null, null, "{}");
  }

  @Test
  void gravaELeUmEstagioPendenteComOsNulosComoNull() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      StageDao.upsert(banco, pk, pendente(AnalysisStage.SOURCE_ANALYSIS));

      assertThat(StageDao.list(banco, pk)).containsExactly(pendente(AnalysisStage.SOURCE_ANALYSIS));
    }
  }

  @Test
  void aSegundaGravacaoDoMesmoEstagioSubstituiALinha() throws Exception {
    final StageRecord concluido =
        new StageRecord(
            AnalysisStage.SOURCE_ANALYSIS,
            StageStatus.COMPLETED,
            NOW,
            1_250L,
            "{\"analyzedFiles\":12}");

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      StageDao.upsert(banco, pk, pendente(AnalysisStage.SOURCE_ANALYSIS));

      StageDao.upsert(banco, pk, concluido);

      assertThat(StageDao.list(banco, pk)).containsExactly(concluido);
    }
  }

  @Test
  void listaNaOrdemDoPipelineENaoNaDoTexto() throws Exception {
    final List<AnalysisStage> pipeline = List.of(AnalysisStage.values());

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      for (final AnalysisStage estagio : pipeline.reversed()) {
        StageDao.upsert(banco, pk, pendente(estagio));
      }

      assertThat(StageDao.list(banco, pk)).extracting(StageRecord::stage).isEqualTo(pipeline);
      assertThat(Arrays.stream(AnalysisStage.values()).map(Enum::name).sorted().toList())
          .isNotEqualTo(pipeline.stream().map(Enum::name).toList());
    }
  }

  @Test
  void listaSoOsEstagiosDaAnalisePedida() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      final long segunda = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_02"));
      StageDao.upsert(banco, primeira, pendente(AnalysisStage.REPOSITORY_VALIDATION));
      StageDao.upsert(banco, segunda, pendente(AnalysisStage.PERSISTENCE));

      assertThat(StageDao.list(banco, segunda))
          .containsExactly(pendente(AnalysisStage.PERSISTENCE));
    }
  }

  @Test
  void umaAnaliseSemEstagiosDevolveListaVazia() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThat(StageDao.list(banco, pk)).isEmpty();
    }
  }

  @Test
  void umEstagioDeUmaAnaliseInexistenteERecusadoPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThatThrownBy(() -> StageDao.upsert(banco, 99, pendente(AnalysisStage.PERSISTENCE)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
    }
  }

  @Test
  void apagarAAnaliseApagaOsEstagiosEmCascata() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long pk = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      StageDao.upsert(banco, pk, pendente(AnalysisStage.REPOSITORY_VALIDATION));
      StageDao.upsert(banco, pk, pendente(AnalysisStage.LAYOUT_DISCOVERY));

      DaoTestDatabase.execute(banco, "DELETE FROM analysis WHERE id = ?", pk);

      assertThat(StageDao.list(banco, pk)).isEmpty();
      assertThat(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM analysis_stage"))
          .isEqualTo("0");
    }
  }
}
