package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

final class SqlitePragmas {

  private static final String AUTO_VACUUM_INCREMENTAL = "PRAGMA auto_vacuum = INCREMENTAL";
  private static final String FOREIGN_KEYS_ON = "PRAGMA foreign_keys = ON";
  private static final String FOREIGN_KEYS = "PRAGMA foreign_keys";
  private static final String JOURNAL_MODE_WAL = "PRAGMA journal_mode = WAL";
  private static final String JOURNAL_MODE = "PRAGMA journal_mode";

  private static final String FOREIGN_KEYS_ENFORCED = "1";
  private static final String WAL = "wal";

  private static final List<String> CONNECTION_PRAGMAS =
      List.of(
          "PRAGMA busy_timeout = " + AnalysisConstants.SQLITE_BUSY_TIMEOUT_MILLIS,
          "PRAGMA synchronous = NORMAL",
          "PRAGMA temp_store = MEMORY",
          "PRAGMA cache_size = -" + AnalysisConstants.SQLITE_CACHE_SIZE_KIB,
          "PRAGMA mmap_size = " + AnalysisConstants.SQLITE_MMAP_SIZE_BYTES,
          "PRAGMA journal_size_limit = " + AnalysisConstants.SQLITE_JOURNAL_SIZE_LIMIT_BYTES,
          "PRAGMA trusted_schema = OFF");

  private SqlitePragmas() {}

  static void applyToWriter(Connection writer) throws SQLException {
    execute(writer, AUTO_VACUUM_INCREMENTAL);
    enforceForeignKeys(writer);
    require("journal_mode", WAL, firstColumn(writer, JOURNAL_MODE_WAL));
    applyConnectionPragmas(writer);
  }

  static void applyToReader(Connection reader) throws SQLException {
    enforceForeignKeys(reader);
    // Só confere: mudar o journal_mode em uma conexão somente leitura lança SQLITE_READONLY.
    // Quem põe o arquivo em WAL é o escritor, que abre antes.
    require("journal_mode", WAL, firstColumn(reader, JOURNAL_MODE));
    applyConnectionPragmas(reader);
  }

  private static void enforceForeignKeys(Connection connection) throws SQLException {
    execute(connection, FOREIGN_KEYS_ON);
    require("foreign_keys", FOREIGN_KEYS_ENFORCED, firstColumn(connection, FOREIGN_KEYS));
  }

  private static void applyConnectionPragmas(Connection connection) throws SQLException {
    for (final String pragma : CONNECTION_PRAGMAS) {
      execute(connection, pragma);
    }
  }

  private static void require(String pragma, String expected, String actual)
      throws PragmaRefusedException {
    if (!expected.equals(actual)) {
      throw new PragmaRefusedException(pragma, expected, String.valueOf(actual));
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }

  private static String firstColumn(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getString(1) : null;
    }
  }
}
