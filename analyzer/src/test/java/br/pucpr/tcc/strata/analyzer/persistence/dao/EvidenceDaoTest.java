package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteErrorCode;

final class EvidenceDaoTest {

  @TempDir private Path temp;

  private static long analiseComUmaLinha(Connection banco, String analysisId) throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis(analysisId));
    final long arquivo = SourceFileDao.nextId(banco);
    SourceFileDao.insert(
        banco,
        analise,
        List.of(new SourceFileRow(arquivo, "a/A.java", 10, 1L, SourceFileStatus.ANALYZED)));
    final long tipo = ElementDao.nextId(banco);
    ElementDao.insert(
        banco,
        analise,
        List.of(
            elemento(tipo, "type:a.A", ElementKind.TYPE, null),
            elemento(tipo + 1, "type:a.B", ElementKind.TYPE, null),
            elemento(tipo + 2, "method:a.A#run()", ElementKind.METHOD, tipo)));
    final long linha = DependencyDao.nextId(banco);
    DependencyDao.insert(
        banco,
        analise,
        List.of(
            new DependencyRow(
                linha,
                tipo,
                tipo + 1,
                DependencyType.CALLS,
                ResolutionQuality.RESOLVED,
                1,
                2,
                false)));
    return linha;
  }

  private static ElementRow elemento(long pk, String id, ElementKind kind, Long pai) {
    final CodeElementId elementId = new CodeElementId(id);
    return new ElementRow(
        pk,
        elementId,
        kind,
        pai,
        "n",
        elementId.sortKey(),
        elementId.sortKey(),
        null,
        "",
        false,
        false,
        null,
        null,
        null,
        null);
  }

  private static List<EvidenceRow> evidencias(long linha, int quantas) {
    final List<EvidenceRow> evidencias = new ArrayList<>(quantas);
    for (int i = 0; i < quantas; i++) {
      evidencias.add(new EvidenceRow(linha, 3L, null, 1L, i + 1, ResolutionQuality.RESOLVED));
    }
    return evidencias;
  }

  @Test
  void gravaELeTodasAsColunasComOsNulosComoNull() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long linha = analiseComUmaLinha(banco, "an_01");
      final EvidenceRow completa =
          new EvidenceRow(linha, 3L, 3L, 1L, 27, ResolutionQuality.INFERRED);
      final EvidenceRow vazia =
          new EvidenceRow(linha, null, null, null, null, ResolutionQuality.UNRESOLVED_EXTERNAL);

      EvidenceDao.insert(banco, List.of(completa, vazia));

      assertThat(EvidenceDao.list(banco, 1)).containsExactly(completa, vazia);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT group_concat(coalesce(line, 'NULL') || ':' || resolution, ' ' ORDER BY id)"
                      + " FROM dependency_evidence"))
          .isEqualTo("27:INFERRED NULL:UNRESOLVED_EXTERNAL");
    }
  }

  @ParameterizedTest(name = "{0} linhas")
  @ValueSource(ints = {0, 1, 500, 501, 1_001})
  void asFronteirasDasInstrucoesDeVariasLinhasVoltamExatas(int quantas) throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long linha = analiseComUmaLinha(banco, "an_01");
      final List<EvidenceRow> esperadas = evidencias(linha, quantas);

      EvidenceDao.insert(banco, esperadas);

      assertThat(EvidenceDao.list(banco, 1)).isEqualTo(esperadas);
    }
  }

  @Test
  void cadaAnaliseListaSoAsSuasEAsChavesSaoContiguas() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long daPrimeira = analiseComUmaLinha(banco, "an_01");
      final long daSegunda = analiseComUmaLinha(banco, "an_02");
      final EvidenceRow primeira =
          new EvidenceRow(daPrimeira, null, null, 1L, 5, ResolutionQuality.RESOLVED);
      final EvidenceRow segunda =
          new EvidenceRow(daSegunda, null, null, 2L, 7, ResolutionQuality.RESOLVED);

      EvidenceDao.insert(banco, List.of(primeira));
      EvidenceDao.insert(banco, List.of(segunda, primeira));

      assertThat(EvidenceDao.list(banco, 1)).containsExactly(primeira, primeira);
      assertThat(EvidenceDao.list(banco, 2)).containsExactly(segunda);
      assertThat(
              DaoTestDatabase.scalar(
                  banco, "SELECT group_concat(id, ',' ORDER BY id) FROM dependency_evidence"))
          .isEqualTo("1,2,3");
    }
  }

  @Test
  void umaLinhaCanonicaQueNaoExisteERecusadaPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      analiseComUmaLinha(banco, "an_01");
      final EvidenceRow orfa =
          new EvidenceRow(999, null, null, null, 1, ResolutionQuality.RESOLVED);

      assertThatThrownBy(() -> EvidenceDao.insert(banco, List.of(orfa)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
    }
  }

  @Test
  void aLinhaZeroNaoChegaASerConstruida() {
    assertThatThrownBy(() -> new EvidenceRow(1, null, null, null, 0, ResolutionQuality.RESOLVED))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
