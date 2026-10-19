package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

final class CacheRecoveryClassifierTest {

  private static CacheRecovery.Verdict classificar(SQLiteErrorCode code) {
    return CacheRecovery.classify(new SQLiteException("probe", code));
  }

  @ParameterizedTest
  @EnumSource(
      value = SQLiteErrorCode.class,
      names = {
        "SQLITE_CORRUPT",
        "SQLITE_CORRUPT_INDEX",
        "SQLITE_CORRUPT_SEQUENCE",
        "SQLITE_NOTADB"
      })
  void corrupcaoECorrupcaoComTodosOsEstendidos(SQLiteErrorCode code) {
    assertThat(classificar(code)).isEqualTo(CacheRecovery.Verdict.CORRUPT);
  }

  @ParameterizedTest
  @EnumSource(
      value = SQLiteErrorCode.class,
      names = {
        "SQLITE_FULL",
        "SQLITE_BUSY",
        "SQLITE_LOCKED",
        "SQLITE_READONLY",
        "SQLITE_READONLY_DIRECTORY",
        "SQLITE_IOERR",
        "SQLITE_IOERR_SHORT_READ",
        "SQLITE_IOERR_SHMSIZE",
        "SQLITE_CANTOPEN",
        "SQLITE_PERM",
        "SQLITE_NOMEM"
      })
  void ambienteNuncaReconstroi(SQLiteErrorCode code) {
    assertThat(classificar(code)).isEqualTo(CacheRecovery.Verdict.ENVIRONMENT);
  }

  @ParameterizedTest
  @EnumSource(
      value = SQLiteErrorCode.class,
      names = {"SQLITE_ERROR", "SQLITE_CONSTRAINT", "SQLITE_CONSTRAINT_CHECK", "SQLITE_MISMATCH"})
  void oNossoSqlEhDefeitoENaoReconstroi(SQLiteErrorCode code) {
    assertThat(classificar(code)).isEqualTo(CacheRecovery.Verdict.BUG);
  }

  @Test
  void umaExcecaoQueNaoVemDoSqliteEhDefeito() {
    assertThat(CacheRecovery.classify(new IOException("disk")))
        .isEqualTo(CacheRecovery.Verdict.BUG);
    assertThat(CacheRecovery.classify(new PragmaRefusedException("foreign_keys", "1", "0")))
        .isEqualTo(CacheRecovery.Verdict.BUG);
    assertThat(CacheRecovery.isCorruption(new SQLiteException("x", SQLiteErrorCode.SQLITE_NOTADB)))
        .isTrue();
  }
}
