package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;

final class CacheMetadataDaoTest {

  private static final CacheMetadata METADATA =
      new CacheMetadata("a6d309ef4118082d", 1, "0.1.0", 1, NOW, NOW);

  @TempDir private Path temp;

  @Test
  void gravaELeALinhaUnica() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      CacheMetadataDao.insert(banco, METADATA);

      assertThat(CacheMetadataDao.find(banco)).contains(METADATA);
      assertThat(DaoTestDatabase.scalar(banco, "SELECT id FROM cache_metadata")).isEqualTo("1");
    }
  }

  @Test
  void umArquivoCujaCriacaoFoiInterrompidaNaoTemLinha() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(CacheMetadataDao.find(banco)).isEmpty();
    }
  }

  @Test
  void umaSegundaLinhaERecusada() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      CacheMetadataDao.insert(banco, METADATA);

      assertThatThrownBy(() -> CacheMetadataDao.insert(banco, METADATA))
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY));
      assertThat(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM cache_metadata"))
          .isEqualTo("1");
    }
  }

  @Test
  void touchAtualizaAsVersoesEOUpdatedAtEPreservaORestante() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      CacheMetadataDao.insert(banco, METADATA);

      CacheMetadataDao.touch(banco, 2, "0.2.0", NOW + 1_000);

      assertThat(CacheMetadataDao.find(banco))
          .contains(new CacheMetadata("a6d309ef4118082d", 2, "0.2.0", 1, NOW, NOW + 1_000));
    }
  }

  @Test
  void touchSemALinhaLancaEmVezDeFingirSucesso() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThatThrownBy(() -> CacheMetadataDao.touch(banco, 1, "0.1.0", NOW))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("não tem linha");
      assertThat(CacheMetadataDao.find(banco)).isEmpty();
    }
  }
}
