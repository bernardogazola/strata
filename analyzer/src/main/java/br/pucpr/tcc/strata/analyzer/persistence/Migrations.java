package br.pucpr.tcc.strata.analyzer.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;

public final class Migrations {

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
    throw new UnsupportedOperationException("Migrations.migrate em implementação");
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
      throw new UnsupportedOperationException("Migration.script em implementação");
    }

    public String checksum() {
      throw new UnsupportedOperationException("Migration.checksum em implementação");
    }
  }
}
