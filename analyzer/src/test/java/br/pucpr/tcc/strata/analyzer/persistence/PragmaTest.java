package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

final class PragmaTest {

  @TempDir private Path temp;

  private Path database;

  @BeforeEach
  void arquivo() throws Exception {
    database = temp.toRealPath().resolve("analysis.sqlite");
  }

  private static Map<String, String> esperados() {
    final Map<String, String> pragmas = new LinkedHashMap<>();
    pragmas.put("journal_mode", "wal");
    pragmas.put("foreign_keys", "1");
    pragmas.put("busy_timeout", Integer.toString(AnalysisConstants.SQLITE_BUSY_TIMEOUT_MILLIS));
    pragmas.put("synchronous", "1");
    pragmas.put("temp_store", "2");
    pragmas.put("cache_size", Integer.toString(-AnalysisConstants.SQLITE_CACHE_SIZE_KIB));
    pragmas.put("mmap_size", Long.toString(AnalysisConstants.SQLITE_MMAP_SIZE_BYTES));
    pragmas.put(
        "journal_size_limit", Long.toString(AnalysisConstants.SQLITE_JOURNAL_SIZE_LIMIT_BYTES));
    pragmas.put("trusted_schema", "0");
    return pragmas;
  }

  private static String escrito(SqliteConnections connections, String sql) throws SQLException {
    return connections.write(c -> TestCaches.scalar(c, sql));
  }

  private static String lido(SqliteConnections connections, String sql) throws SQLException {
    return connections.read(c -> TestCaches.scalar(c, sql));
  }

  private static void awaitAll(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("os leitores não rodaram em paralelo");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static Map<String, String> lidos(Connection connection) throws SQLException {
    final Map<String, String> pragmas = new LinkedHashMap<>();
    for (final String name : esperados().keySet()) {
      pragmas.put(name, TestCaches.scalar(connection, "PRAGMA " + name));
    }
    return pragmas;
  }

  @Test
  void umArquivoCriadoTemAutoVacuumIncrementalPorqueOPragmaVemAntesDoWal() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      assertThat(connections.created()).isTrue();
      assertThat(escrito(connections, "PRAGMA auto_vacuum"))
          .as("a ordem WAL ao abrir, depois o V001, deixava 0 sem erro (medido)")
          .isEqualTo("2");
      assertThat(escrito(connections, "PRAGMA journal_mode")).isEqualTo("wal");
    }
    try (SqliteConnections reopened = SqliteConnections.open(database)) {
      assertThat(reopened.created()).isFalse();
      assertThat(escrito(reopened, "PRAGMA auto_vacuum")).isEqualTo("2");
    }
  }

  @Test
  void oEscritorLeDeVoltaOsNovePragmas() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      assertThat(connections.write(PragmaTest::lidos)).isEqualTo(esperados());
    }
  }

  @Test
  void cadaUmDosQuatroLeitoresLeDeVoltaOsPragmasENaoEscreve() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      connections.write(
          c -> {
            try (PreparedStatement create =
                c.prepareStatement("CREATE TABLE probe (x INTEGER) STRICT")) {
              create.executeUpdate();
            }
            return null;
          });

      final int readers = AnalysisConstants.SQLITE_READER_CONNECTIONS;
      final CountDownLatch allInside = new CountDownLatch(readers);
      final ExecutorService pool = Executors.newFixedThreadPool(readers);
      try {
        final List<Future<Map<String, String>>> seen = new ArrayList<>();
        for (int index = 0; index < readers; index++) {
          seen.add(
              pool.submit(
                  () ->
                      connections.read(
                          c -> {
                            allInside.countDown();
                            awaitAll(allInside);
                            return lidos(c);
                          })));
        }
        for (final Future<Map<String, String>> reader : seen) {
          assertThat(reader.get(20, TimeUnit.SECONDS)).isEqualTo(esperados());
        }
      } finally {
        pool.shutdownNow();
      }

      assertThatThrownBy(
              () ->
                  connections.read(
                      c -> {
                        try (PreparedStatement insert =
                            c.prepareStatement("INSERT INTO probe (x) VALUES (1)")) {
                          return insert.executeUpdate();
                        }
                      }))
          .isInstanceOf(SQLiteException.class)
          .satisfies(
              failure ->
                  assertThat(((SQLiteException) failure).getResultCode().code & 0xFF)
                      .as("SQLITE_OPEN_READONLY, e não query_only, que se desliga sozinho")
                      .isEqualTo(SQLiteErrorCode.SQLITE_READONLY.code));
    }
  }

  @Test
  void umaConexaoDentroDeUmaTransacaoLeForeignKeysZeroEERecusada() throws Exception {
    try (Connection raw = TestCaches.raw(database)) {
      raw.setAutoCommit(false);
      try (PreparedStatement on = raw.prepareStatement("PRAGMA foreign_keys = ON")) {
        on.execute();
      }
      assertThat(TestCaches.scalar(raw, "PRAGMA foreign_keys"))
          .as("ligado de dentro de uma transação, o pragma não liga nada")
          .isEqualTo("0");

      assertThatThrownBy(() -> SqlitePragmas.applyToWriter(raw))
          .isInstanceOf(PragmaRefusedException.class)
          .hasMessageContaining("foreign_keys");
      raw.rollback();
    }
  }

  @Test
  void fecharTruncaOWalEOsDadosSobrevivemAReabertura() throws Exception {
    final SqliteConnections connections = SqliteConnections.open(database);
    connections.write(
        c -> {
          try (PreparedStatement create =
              c.prepareStatement("CREATE TABLE probe (x INTEGER) STRICT")) {
            create.executeUpdate();
          }
          try (PreparedStatement insert = c.prepareStatement("INSERT INTO probe (x) VALUES (?)")) {
            for (int value = 0; value < 1_000; value++) {
              insert.setInt(1, value);
              insert.addBatch();
            }
            insert.executeBatch();
          }
          return null;
        });
    assertThat(lido(connections, "SELECT count(*) FROM probe")).isEqualTo("1000");
    final Path wal = TestCaches.sibling(database, "-wal");
    assertThat(wal).exists();

    connections.close();
    connections.close();

    assertThat(Files.notExists(wal) || Files.size(wal) == 0)
        .as("o checkpoint(TRUNCATE) do fechamento esvazia o -wal")
        .isTrue();
    try (Connection raw = TestCaches.raw(database)) {
      assertThat(TestCaches.scalar(raw, "SELECT count(*) FROM probe")).isEqualTo("1000");
    }
  }

  @Test
  void umaUnidadeDeEscritaQueLancaNaoDeixaNada() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      connections.write(
          c -> {
            try (PreparedStatement create =
                c.prepareStatement("CREATE TABLE probe (x INTEGER) STRICT")) {
              create.executeUpdate();
            }
            return null;
          });
      assertThatThrownBy(
              () ->
                  connections.write(
                      c -> {
                        try (PreparedStatement insert =
                            c.prepareStatement("INSERT INTO probe (x) VALUES (1)")) {
                          insert.executeUpdate();
                        }
                        throw new SQLException("forced");
                      }))
          .hasMessage("forced");
      assertThat(lido(connections, "SELECT count(*) FROM probe")).isEqualTo("0");
    }
  }
}
