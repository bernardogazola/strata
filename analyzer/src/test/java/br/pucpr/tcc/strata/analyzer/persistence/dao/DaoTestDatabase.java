package br.pucpr.tcc.strata.analyzer.persistence.dao;

import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

public final class DaoTestDatabase {

  private static final String V001 = "/db/migration/V001__initial_schema.sql";

  static final long NOW = 1_792_000_000_000L;

  static final String CONFIG_HASH = "c".repeat(64);

  private DaoTestDatabase() {}

  public static Connection create(Path directory) throws SQLException, IOException {
    final Connection connection =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("analysis.sqlite"));
    try {
      execute(connection, "PRAGMA foreign_keys = ON");
      if (!"1".equals(scalar(connection, "PRAGMA foreign_keys"))) {
        throw new IllegalStateException("foreign_keys não ligou");
      }
      try (Statement script = connection.createStatement()) {
        script.executeUpdate(v001());
      }
      return connection;
    } catch (SQLException | IOException | RuntimeException failure) {
      try {
        connection.close();
      } catch (SQLException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public static int execute(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = prepare(connection, sql, values)) {
      return statement.executeUpdate();
    }
  }

  public static String scalar(Connection connection, String sql, Object... values)
      throws SQLException {
    try (PreparedStatement statement = prepare(connection, sql, values);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

  public static SQLiteErrorCode resultCode(Throwable failure) {
    if (!(failure instanceof SQLiteException sqlite)) {
      throw new AssertionError("não é uma SQLiteException: " + failure);
    }
    return sqlite.getResultCode();
  }

  static NewAnalysis analysis(String analysisId) {
    return new NewAnalysis(
        analysisId,
        NOW,
        null,
        "0.1.0",
        1,
        1,
        CONFIG_HASH,
        "{}",
        null,
        false,
        HistoryScope.ALL,
        null);
  }

  private static PreparedStatement prepare(Connection connection, String sql, Object... values)
      throws SQLException {
    final PreparedStatement statement = connection.prepareStatement(sql);
    try {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      return statement;
    } catch (SQLException failure) {
      statement.close();
      throw failure;
    }
  }

  private static String v001() throws IOException {
    try (InputStream resource = DaoTestDatabase.class.getResourceAsStream(V001)) {
      if (resource == null) {
        throw new IOException("recurso ausente no classpath: " + V001);
      }
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
