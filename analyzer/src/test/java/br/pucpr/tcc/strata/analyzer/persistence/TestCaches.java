package br.pucpr.tcc.strata.analyzer.persistence;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

final class TestCaches {

  static final int V001_TABLES = 30;

  static final String V01_PATH = "/Users/alice/work/acme-payments-legacy";

  static final String V01_ROOT = "fa7da60406a5f3ea0cbf4335426a66d992d3c3d1";

  static final String V01_KEY = "a6d309ef4118082d";

  private TestCaches() {}

  static RepositoryIdentity identityV01() {
    return RepositoryIdentity.of(V01_PATH, PathFlavor.POSIX, V01_ROOT);
  }

  static InstantSource steppingClock() {
    final AtomicLong millis = new AtomicLong(1_792_000_000_000L);
    return () -> Instant.ofEpochMilli(millis.getAndIncrement());
  }

  static Connection raw(Path databaseFile) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
  }

  static String scalar(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

  static List<String> column(Connection connection, String sql) throws SQLException {
    final List<String> values = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return List.copyOf(values);
  }

  static List<String> userTables(Connection connection) throws SQLException {
    return column(
        connection,
        "SELECT name FROM sqlite_master WHERE type = 'table'"
            + " AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name");
  }

  static int strictTables(Connection connection) throws SQLException {
    return Integer.parseInt(
        scalar(
            connection,
            "SELECT count(*) FROM sqlite_master WHERE type = 'table'"
                + " AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND sql LIKE '%STRICT%'"));
  }

  static byte[] overwrite(Path file, long offset, int count, long seed) throws IOException {
    final byte[] garbage = new byte[count];
    new Random(seed).nextBytes(garbage);
    try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
      handle.seek(offset);
      handle.write(garbage);
    }
    return garbage;
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("todo JDK traz SHA-256", impossible);
    }
  }

  static Path sibling(Path databaseFile, String suffix) {
    return databaseFile.resolveSibling(databaseFile.getFileName() + suffix);
  }

  static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (final Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
        Files.delete(path);
      }
    }
  }
}
