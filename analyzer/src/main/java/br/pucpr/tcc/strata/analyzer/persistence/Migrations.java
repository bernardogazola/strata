package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class Migrations {

  private static final String COUNT_SCHEMA_OBJECTS =
      "SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite\\_%' ESCAPE '\\'";

  private static final String COUNT_LEDGER_COLUMNS =
      "SELECT count(*) FROM sqlite_master AS m, pragma_table_info(m.name) AS c"
          + " WHERE m.type = 'table' AND m.name = 'schema_migration'"
          + " AND c.name IN ('version', 'name', 'checksum')";

  private static final int LEDGER_COLUMNS_READ = 3;

  private static final String READ_LEDGER =
      "SELECT version, name, checksum FROM schema_migration ORDER BY version";

  private static final String RECORD_IN_LEDGER =
      "INSERT INTO schema_migration (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)";

  private final List<Migration> migrations;

  private Migrations(List<Migration> migrations) {
    this.migrations = List.copyOf(migrations);
  }

  public static Migrations bundled() {
    return of(
        List.of(new Migration(1, "initial_schema", "/db/migration/V001__initial_schema.sql")));
  }

  static Migrations of(List<Migration> migrations) {
    for (int index = 0; index < migrations.size(); index++) {
      if (migrations.get(index).version() != index + 1) {
        throw new IllegalArgumentException(
            "as migrações devem rodar de 1..n sem lacuna: " + migrations);
      }
    }
    return new Migrations(migrations);
  }

  public List<Migration> migrations() {
    return migrations;
  }

  public int latestVersion() {
    return migrations.size();
  }

  public int migrate(Connection writer, InstantSource clock)
      throws SQLException, CacheDiscardException {
    Objects.requireNonNull(writer, "writer");
    Objects.requireNonNull(clock, "clock");
    if (!writer.getAutoCommit()) {
      throw new IllegalStateException(
          "a conexão de escrita deve estar em autocommit antes de migrar");
    }
    final int found = Math.toIntExact(scalar(writer, "PRAGMA user_version"));
    requireKnownSchema(writer, found);
    for (final Migration migration : migrations.subList(found, latestVersion())) {
      apply(writer, migration, found, clock);
    }
    return latestVersion();
  }

  private void requireKnownSchema(Connection writer, int found)
      throws SQLException, CacheDiscardException {
    if (found > latestVersion()) {
      throw new CacheDiscardException(
          new CacheInvalidation.SchemaVersion(found, latestVersion()),
          "a versão "
              + found
              + " do schema do cache é mais nova que a desta build, "
              + latestVersion());
    }
    if (found < 0) {
      throw corrupt("versão negativa do schema do cache: " + found);
    }
    if (found == 0) {
      if (scalar(writer, COUNT_SCHEMA_OBJECTS) > 0) {
        throw corrupt("um schema não vazio na versão 0 do schema do cache");
      }
      return;
    }
    requireLedger(writer, found);
  }

  private void requireLedger(Connection writer, int applied)
      throws SQLException, CacheDiscardException {
    if (scalar(writer, COUNT_LEDGER_COLUMNS) != LEDGER_COLUMNS_READ) {
      throw corrupt("versão " + applied + " do schema do cache sem a tabela schema_migration");
    }
    int rowsRead = 0;
    try (PreparedStatement statement = writer.prepareStatement(READ_LEDGER);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        if (rowsRead == applied || !isRecordOf(migrations.get(rowsRead), rows)) {
          throw corrupt("a linha " + (rowsRead + 1) + " de schema_migration não é desta build");
        }
        rowsRead++;
      }
    }
    if (rowsRead != applied) {
      throw corrupt("schema_migration tem " + rowsRead + " de " + applied + " versões");
    }
  }

  private static boolean isRecordOf(Migration expected, ResultSet row) throws SQLException {
    return row.getLong(1) == expected.version()
        && expected.name().equals(row.getString(2))
        && expected.checksum().equals(row.getString(3));
  }

  private void apply(Connection writer, Migration migration, int found, InstantSource clock)
      throws SQLException, CacheDiscardException {
    final byte[] script = migration.script();
    final String checksum = sha256Hex(script);
    final String sql = new String(script, StandardCharsets.UTF_8);
    try {
      SqlTransactions.run(
          writer,
          transaction -> {
            runScript(transaction, sql);
            setUserVersion(transaction, migration.version());
            recordInLedger(transaction, migration, checksum, clock.millis());
            return migration.version();
          });
    } catch (SQLException failure) {
      throw discardOrRethrow(failure, migration, found);
    }
    Log.info(
        "schema do cache migrado para a versão " + migration.version() + " " + migration.name());
  }

  private CacheDiscardException discardOrRethrow(
      SQLException failure, Migration migration, int found) throws SQLException {
    final CacheInvalidation invalidation =
        switch (CacheRecovery.classify(failure)) {
          case CORRUPT -> new CacheInvalidation.Corrupt();
          case ENVIRONMENT -> throw failure;
          case BUG -> {
            if (found == 0) {
              throw failure;
            }
            yield new CacheInvalidation.SchemaVersion(found, latestVersion());
          }
        };
    final CacheDiscardException discard =
        new CacheDiscardException(
            invalidation,
            migration.resourcePath() + " falhou em um cache na versão do schema " + found);
    discard.initCause(failure);
    return discard;
  }

  private static void runScript(Connection writer, String sql) throws SQLException {
    try (Statement script = writer.createStatement()) {
      script.executeUpdate(sql);
    }
  }

  private static void setUserVersion(Connection writer, int version) throws SQLException {
    try (PreparedStatement statement =
        writer.prepareStatement("PRAGMA user_version = %d".formatted(version))) {
      statement.executeUpdate();
    }
  }

  private static void recordInLedger(
      Connection writer, Migration migration, String checksum, long appliedAtMillis)
      throws SQLException {
    try (PreparedStatement statement = writer.prepareStatement(RECORD_IN_LEDGER)) {
      statement.setInt(1, migration.version());
      statement.setString(2, migration.name());
      statement.setString(3, checksum);
      statement.setLong(4, appliedAtMillis);
      statement.executeUpdate();
    }
  }

  private static long scalar(Connection writer, String sql) throws SQLException {
    try (PreparedStatement statement = writer.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        throw new IllegalStateException("nenhuma linha de " + sql);
      }
      return rows.getLong(1);
    }
  }

  private static CacheDiscardException corrupt(String message) {
    return new CacheDiscardException(new CacheInvalidation.Corrupt(), message);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("todo JDK traz SHA-256", impossible);
    }
  }

  public record Migration(int version, String name, String resourcePath) {

    public Migration {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(resourcePath, "resourcePath");
      final String fileName = "V%03d__%s.sql".formatted(version, name);
      if (version < 1 || !resourcePath.endsWith("/" + fileName)) {
        throw new IllegalArgumentException("não é o recurso de " + fileName + ": " + resourcePath);
      }
    }

    public byte[] script() {
      try (InputStream resource = Migrations.class.getResourceAsStream(resourcePath)) {
        if (resource == null) {
          throw new IllegalStateException("recurso de migração fora do classpath: " + resourcePath);
        }
        return resource.readAllBytes();
      } catch (IOException unreadable) {
        throw new UncheckedIOException(
            "não foi possível ler o recurso de migração " + resourcePath, unreadable);
      }
    }

    public String checksum() {
      return sha256Hex(script());
    }
  }
}
