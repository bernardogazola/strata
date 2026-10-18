package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class RepositoryDaoTest {

  private static final String KEY = "a6d309ef4118082d";
  private static final String FINGERPRINT =
      "8e86fb872d1fbafd6fcf984a150568f5c27198084f86f7edc8569f7ceb136993";
  private static final String ROOT = "fa7da60406a5f3ea0cbf4335426a66d992d3c3d1";

  @TempDir private Path temp;

  private static RepositoryRecord repositorio(
      String raiz, String ramo, boolean raso, BuildSystem build, long primeiro, long ultimo) {
    return new RepositoryRecord(
        KEY, "acme-payments-legacy", FINGERPRINT, raiz, ramo, raso, build, primeiro, ultimo);
  }

  @Test
  void antesDaPrimeiraGravacaoNaoHaLinha() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(RepositoryDao.find(banco)).isEmpty();
    }
  }

  @Test
  void gravaELeTodasAsColunas() throws Exception {
    final RepositoryRecord gravado =
        repositorio(ROOT, "main", true, BuildSystem.GRADLE, NOW, NOW + 5);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      RepositoryDao.upsert(banco, gravado);

      assertThat(RepositoryDao.find(banco)).contains(gravado);
      assertThat(DaoTestDatabase.scalar(banco, "SELECT id FROM repository")).isEqualTo("1");
    }
  }

  @Test
  void raizERamoNulosVoltamNulos() throws Exception {
    final RepositoryRecord semRaiz = repositorio(null, null, false, BuildSystem.UNKNOWN, NOW, NOW);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      RepositoryDao.upsert(banco, semRaiz);

      assertThat(RepositoryDao.find(banco)).contains(semRaiz);
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT typeof(root_commit_id) || '|' || typeof(default_branch) FROM repository"))
          .as("NULL de verdade, e não o texto 'null'")
          .isEqualTo("null|null");
    }
  }

  @Test
  void aSegundaGravacaoPreservaOFirstSeenAtEAtualizaTodoORestante() throws Exception {
    final RepositoryRecord primeira = repositorio(ROOT, "main", false, BuildSystem.MAVEN, NOW, NOW);
    final RepositoryRecord segunda =
        new RepositoryRecord(
            KEY,
            "renomeado",
            FINGERPRINT,
            null,
            null,
            true,
            BuildSystem.NONE,
            NOW + 999_999,
            NOW + 60_000);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      RepositoryDao.upsert(banco, primeira);
      RepositoryDao.upsert(banco, segunda);

      assertThat(RepositoryDao.find(banco))
          .contains(
              new RepositoryRecord(
                  KEY,
                  "renomeado",
                  FINGERPRINT,
                  null,
                  null,
                  true,
                  BuildSystem.NONE,
                  NOW,
                  NOW + 60_000));
      assertThat(DaoTestDatabase.scalar(banco, "SELECT count(*) FROM repository")).isEqualTo("1");
    }
  }

  @Test
  void gravaOBooleanoComoInteiroEOEnumPeloNome() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      RepositoryDao.upsert(banco, repositorio(ROOT, "main", true, BuildSystem.ANT, NOW, NOW));

      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT typeof(is_shallow) || '|' || is_shallow || '|' || build_system"
                      + " FROM repository"))
          .isEqualTo("integer|1|ANT");
    }
  }

  @ParameterizedTest
  @EnumSource(BuildSystem.class)
  void todoSistemaDeBuildVaiEVoltaPeloNome(BuildSystem build) throws Exception {
    final RepositoryRecord gravado = repositorio(ROOT, "main", false, build, NOW, NOW);

    try (Connection banco = DaoTestDatabase.create(temp)) {
      RepositoryDao.upsert(banco, gravado);

      assertThat(RepositoryDao.find(banco)).contains(gravado);
    }
  }
}
