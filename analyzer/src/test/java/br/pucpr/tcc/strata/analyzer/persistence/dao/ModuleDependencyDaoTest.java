package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;

final class ModuleDependencyDaoTest {

  @TempDir private Path temp;

  private static long analiseComModuleInfo(Connection banco, String analysisId)
      throws SQLException {
    final long analise = AnalysisDao.insertRunning(banco, DaoTestDatabase.analysis(analysisId));
    final long arquivo = SourceFileDao.nextId(banco);
    SourceFileDao.insert(
        banco,
        analise,
        List.of(
            new SourceFileRow(
                arquivo, "src/main/java/module-info.java", 10, 1L, SourceFileStatus.ANALYZED)));
    return arquivo;
  }

  @Test
  void numUmBancoVazioOPrimeiroIdEUm() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(ModuleDependencyDao.nextId(banco)).isEqualTo(1);
    }
  }

  @Test
  void gravaELeTodasAsColunasComOsBooleanosComoZeroEUm() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long arquivo = analiseComModuleInfo(banco, "an_01");
      final ModuleDependencyRow base =
          new ModuleDependencyRow(1, arquivo, "com.acme.app", "java.sql", true, false, 3);
      final ModuleDependencyRow semLinha =
          new ModuleDependencyRow(2, arquivo, "com.acme.app", "com.acme.api", false, true, null);

      ModuleDependencyDao.insert(banco, 1, List.of(base, semLinha));

      assertThat(ModuleDependencyDao.list(banco, 1)).containsExactly(base, semLinha);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT group_concat(is_transitive || is_static || ':' || coalesce(line, 'NULL'),"
                      + " ' ' ORDER BY id) FROM module_dependency"))
          .isEqualTo("10:3 01:NULL");
    }
  }

  @Test
  void oProximoIdContinuaDepoisDasLinhasDeOutraAnaliseECadaListaSoTemAsSuas() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long primeiroArquivo = analiseComModuleInfo(banco, "an_01");
      final ModuleDependencyRow daPrimeira =
          new ModuleDependencyRow(1, primeiroArquivo, "m", "java.sql", false, false, 2);
      ModuleDependencyDao.insert(banco, 1, List.of(daPrimeira));
      final long segundoArquivo = analiseComModuleInfo(banco, "an_02");

      assertThat(ModuleDependencyDao.nextId(banco)).isEqualTo(2);
      final ModuleDependencyRow daSegunda =
          new ModuleDependencyRow(2, segundoArquivo, "m", "java.sql", false, false, 2);
      ModuleDependencyDao.insert(banco, 2, List.of(daSegunda));

      assertThat(ModuleDependencyDao.list(banco, 1)).containsExactly(daPrimeira);
      assertThat(ModuleDependencyDao.list(banco, 2)).containsExactly(daSegunda);
    }
  }

  @Test
  void umRequiresRepetidoNoMesmoArquivoFalhaNoUnique() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      final long arquivo = analiseComModuleInfo(banco, "an_01");
      final List<ModuleDependencyRow> repetidos =
          List.of(
              new ModuleDependencyRow(1, arquivo, "m", "java.sql", false, false, 2),
              new ModuleDependencyRow(2, arquivo, "m", "java.sql", true, false, 3));

      assertThatThrownBy(() -> ModuleDependencyDao.insert(banco, 1, repetidos))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE));
      assertThat(ModuleDependencyDao.list(banco, 1)).isEmpty();
    }
  }

  @Test
  void umArquivoQueNaoExisteERecusadoPelaChaveEstrangeira() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      analiseComModuleInfo(banco, "an_01");
      final ModuleDependencyRow orfa =
          new ModuleDependencyRow(1, 999, "m", "java.sql", false, false, 2);

      assertThatThrownBy(() -> ModuleDependencyDao.insert(banco, 1, List.of(orfa)))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
    }
  }

  @Test
  void aLinhaZeroNaoChegaASerConstruida() {
    assertThatThrownBy(() -> new ModuleDependencyRow(1, 1, "m", "java.sql", false, false, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
