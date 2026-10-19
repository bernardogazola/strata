package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
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

final class DependencyDaoTest {

  private static final int TIPOS = 12;

  private static final List<DependencyType> ARESTAS =
      List.of(DependencyType.values()).stream().filter(DependencyType::isTypeEdge).toList();

  @TempDir private Path temp;

  private static long analiseComTipos(Connection banco, String analysisId) throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis(analysisId));
    final long primeiro = ElementDao.nextId(banco);
    final List<ElementRow> tipos = new ArrayList<>(TIPOS);
    for (int i = 0; i < TIPOS; i++) {
      tipos.add(
          new ElementRow(
              primeiro + i,
              new CodeElementId("type:a.T" + i),
              ElementKind.TYPE,
              null,
              "T" + i,
              "a.T" + i,
              "a.T" + i,
              null,
              "",
              false,
              false,
              null,
              null,
              null,
              null));
    }
    ElementDao.insert(banco, analise, tipos);
    return analise;
  }

  private static List<DependencyRow> linhas(long primeiraChave, long tipo, int quantas) {
    final List<DependencyRow> linhas = new ArrayList<>(quantas);
    for (int i = 0; i < quantas; i++) {
      final DependencyType aresta = ARESTAS.get(i % ARESTAS.size());
      final int par = i / ARESTAS.size();
      linhas.add(
          new DependencyRow(
              primeiraChave + i,
              tipo + par / TIPOS,
              tipo + par % TIPOS,
              aresta,
              ResolutionQuality.values()[i % ResolutionQuality.values().length],
              1 + i % 7,
              2 * (1 + i % 7)));
    }
    return linhas;
  }

  @Test
  void numUmBancoVazioOPrimeiroIdEUm() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(DependencyDao.nextId(banco)).isEqualTo(1);
    }
  }

  @Test
  void gravaELeTodasAsColunasComOsEnumsPeloNome() throws Exception {
    final DependencyRow chamada =
        new DependencyRow(1, 1, 2, DependencyType.CALLS, ResolutionQuality.INFERRED, 3, 6);
    final DependencyRow propria =
        new DependencyRow(
            2, 1, 1, DependencyType.ANNOTATION_REFERENCE, ResolutionQuality.RESOLVED, 1, 1);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComTipos(banco, "an_01");

      DependencyDao.insert(banco, analise, List.of(chamada, propria));

      assertThat(DependencyDao.list(banco, analise)).containsExactly(chamada, propria);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT group_concat(type || ':' || resolution, ' ' ORDER BY id) FROM dependency"))
          .isEqualTo("CALLS:INFERRED ANNOTATION_REFERENCE:RESOLVED");
    }
  }

  @ParameterizedTest(name = "{0} linhas")
  @ValueSource(ints = {0, 1, 500, 501, 1_001})
  void asFronteirasDasInstrucoesDeVariasLinhasVoltamExatas(int quantas) throws Exception {
    final List<DependencyRow> esperadas = linhas(1, 1, quantas);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComTipos(banco, "an_01");

      DependencyDao.insert(banco, analise, esperadas);

      assertThat(DependencyDao.list(banco, analise)).isEqualTo(esperadas);
      assertThat(DependencyDao.nextId(banco)).isEqualTo(quantas + 1L);
    }
  }

  @Test
  void oProximoIdContinuaDepoisDasLinhasDeOutraAnaliseECadaListaSoTemAsSuas() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeira = analiseComTipos(banco, "an_01");
      final List<DependencyRow> daPrimeira = linhas(1, 1, 3);
      DependencyDao.insert(banco, primeira, daPrimeira);
      final long segunda = analiseComTipos(banco, "an_02");

      assertThat(DependencyDao.nextId(banco)).isEqualTo(4);
      final List<DependencyRow> daSegunda = linhas(4, 1 + TIPOS, 2);
      DependencyDao.insert(banco, segunda, daSegunda);

      assertThat(DependencyDao.list(banco, primeira)).isEqualTo(daPrimeira);
      assertThat(DependencyDao.list(banco, segunda)).isEqualTo(daSegunda);
    }
  }

  @Test
  void umaLinhaRepetidaFalhaNoUniqueENaoEntra() throws Exception {
    final DependencyRow primeira =
        new DependencyRow(1, 1, 2, DependencyType.CALLS, ResolutionQuality.RESOLVED, 1, 2);
    final DependencyRow repetida =
        new DependencyRow(2, 1, 2, DependencyType.CALLS, ResolutionQuality.RESOLVED, 4, 8);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComTipos(banco, "an_01");
      DependencyDao.insert(banco, analise, List.of(primeira));

      assertThatThrownBy(() -> DependencyDao.insert(banco, analise, List.of(repetida)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE));
      assertThat(DependencyDao.list(banco, analise)).containsExactly(primeira);
    }
  }

  @Test
  void umExtremoQueNaoExisteERecusadoPelaChaveEstrangeira() throws Exception {
    final DependencyRow orfa =
        new DependencyRow(1, 1, 999, DependencyType.EXTENDS, ResolutionQuality.RESOLVED, 1, 4);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long analise = analiseComTipos(banco, "an_01");

      assertThatThrownBy(() -> DependencyDao.insert(banco, analise, List.of(orfa)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
    }
  }

  @Test
  void umaLinhaDeModuloOuSemOcorrenciaNaoChegaASerConstruida() {
    assertThatThrownBy(
            () ->
                new DependencyRow(
                    1, 1, 2, DependencyType.MODULE_DEPENDENCY, ResolutionQuality.RESOLVED, 1, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MODULE_DEPENDENCY");
    assertThatThrownBy(
            () ->
                new DependencyRow(1, 1, 2, DependencyType.CALLS, ResolutionQuality.RESOLVED, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
