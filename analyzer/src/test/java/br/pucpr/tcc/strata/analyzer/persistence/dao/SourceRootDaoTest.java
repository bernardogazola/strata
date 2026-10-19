package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteErrorCode;

final class SourceRootDaoTest {

  private static final SourceRootRow MAIN =
      new SourceRootRow(
          1, new SourceRootId("sr_a7f3k2m9"), "src/main/java", SourceRootKind.MAIN, 12);
  private static final SourceRootRow TESTE =
      new SourceRootRow(
          2, new SourceRootId("sr_b1c4d7e2"), "src/test/java", SourceRootKind.TEST, 3);
  private static final SourceRootRow GERADA =
      new SourceRootRow(
          3,
          new SourceRootId("sr_0123abcd"),
          "target/generated-sources/annotations",
          SourceRootKind.GENERATED,
          1);

  @TempDir private Path temp;

  private static List<SourceRootRow> raizes(long primeira, int quantas) {
    return LongStream.range(primeira, primeira + quantas)
        .mapToObj(
            pk ->
                new SourceRootRow(
                    pk,
                    SourceRootId.derive("modulo-" + pk + "/src/main/java"),
                    "modulo-" + pk + "/src/main/java",
                    SourceRootKind.MAIN,
                    (int) pk))
        .toList();
  }

  @Test
  void numUmBancoVazioOPrimeiroIdEUm() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(SourceRootDao.nextId(banco)).isEqualTo(1);
    }
  }

  @Test
  void gravaELeAsRaizesNaOrdemDasChaves() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      SourceRootDao.insert(banco, analise, List.of(MAIN, TESTE, GERADA));

      assertThat(SourceRootDao.list(banco, analise)).containsExactly(MAIN, TESTE, GERADA);
    }
  }

  @ParameterizedTest(name = "{0} linhas")
  @ValueSource(ints = {0, 1, 500, 501, 1_001})
  void asFronteirasDasInstrucoesDeVariasLinhasVoltamExatas(int linhas) throws Exception {
    final List<SourceRootRow> esperadas = raizes(1, linhas);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      SourceRootDao.insert(banco, analise, esperadas);

      assertThat(SourceRootDao.list(banco, analise)).isEqualTo(esperadas);
      assertThat(SourceRootDao.nextId(banco)).isEqualTo(linhas + 1L);
    }
  }

  @Test
  void oProximoIdContinuaDepoisDasRaizesDeOutraAnalise() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      final long segunda = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_02"));
      SourceRootDao.insert(banco, primeira, List.of(MAIN, TESTE));

      assertThat(SourceRootDao.nextId(banco)).isEqualTo(3);
      final SourceRootRow daSegunda =
          new SourceRootRow(3, MAIN.sourceRootId(), "src/main/java", SourceRootKind.MAIN, 40);
      SourceRootDao.insert(banco, segunda, List.of(daSegunda));

      assertThat(SourceRootDao.list(banco, primeira)).containsExactly(MAIN, TESTE);
      assertThat(SourceRootDao.list(banco, segunda)).containsExactly(daSegunda);
      assertThat(SourceRootDao.nextId(banco)).isEqualTo(4);
    }
  }

  @Test
  void umaSegundaAnaliseNumeradaDeUmFalhaPelaChavePrimaria() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));
      final long segunda = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_02"));
      SourceRootDao.insert(banco, primeira, List.of(MAIN));

      assertThatThrownBy(() -> SourceRootDao.insert(banco, segunda, List.of(MAIN)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY));
    }
  }

  @Test
  void aMesmaRaizDuasVezesNaMesmaAnaliseERecusada() throws Exception {
    final SourceRootRow repetida =
        new SourceRootRow(2, MAIN.sourceRootId(), "outro/caminho", SourceRootKind.TEST, 1);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis("an_01"));

      assertThatThrownBy(() -> SourceRootDao.insert(banco, analise, List.of(MAIN, repetida)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE));
    }
  }

  @Test
  void umaRaizDeUmaAnaliseInexistenteERecusadaPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThatThrownBy(() -> SourceRootDao.insert(banco, 99, List.of(MAIN)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
    }
  }
}
