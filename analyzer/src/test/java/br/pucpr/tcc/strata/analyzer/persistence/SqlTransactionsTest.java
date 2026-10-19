package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteException;

final class SqlTransactionsTest {

  private static final int SQLITE_FULL = 13;

  @TempDir private Path temp;

  private static void executar(Connection connection, String... sqls) throws SQLException {
    for (final String sql : sqls) {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.execute();
      }
    }
  }

  @Test
  void umSqliteFullDeVerdadeSobeComoAOriginalComALimpezaSuprimida() throws Exception {
    try (Connection raw = TestCaches.raw(temp.toRealPath().resolve("cheio.sqlite"))) {
      executar(
          raw, "PRAGMA journal_mode = WAL", "CREATE TABLE t (x BLOB)", "PRAGMA max_page_count = 8");

      final Throwable falha =
          catchThrowable(
              () ->
                  SqlTransactions.run(
                      raw,
                      c -> {
                        try (PreparedStatement insert =
                            c.prepareStatement("INSERT INTO t (x) VALUES (randomblob(4000))")) {
                          for (int row = 0; row < 100; row++) {
                            insert.executeUpdate();
                          }
                        }
                        return null;
                      }));

      assertThat(falha).isInstanceOf(SQLiteException.class);
      assertThat(((SQLiteException) falha).getResultCode().code & 0xFF).isEqualTo(SQLITE_FULL);
      assertThat(CacheRecovery.classify(falha)).isEqualTo(CacheRecovery.Verdict.ENVIRONMENT);
      assertThat(Arrays.stream(falha.getSuppressed()).map(Throwable::getMessage))
          .as("a limpeza falhou, e foi suprimida na original em vez de substituí-la")
          .isNotEmpty()
          .allMatch(message -> message.contains("no transaction is active"));
      assertThat(TestCaches.scalar(raw, "SELECT count(*) FROM t")).isEqualTo("0");
    }
  }

  @Test
  void umErrorNoMeioDoTrabalhoDesfazATransacaoEDevolveOAutocommit() throws Exception {
    try (Connection raw = TestCaches.raw(temp.toRealPath().resolve("erro.sqlite"))) {
      executar(raw, "PRAGMA journal_mode = WAL", "CREATE TABLE t (x INTEGER)");

      final Throwable falha =
          catchThrowable(
              () ->
                  SqlTransactions.run(
                      raw,
                      c -> {
                        executar(c, "INSERT INTO t (x) VALUES (1)");
                        throw new StackOverflowError("probe");
                      }));

      assertThat(falha).isInstanceOf(StackOverflowError.class).hasMessage("probe");
      assertThat(raw.getAutoCommit()).isTrue();
      assertThat(TestCaches.scalar(raw, "SELECT count(*) FROM t")).isEqualTo("0");
    }
  }
}
