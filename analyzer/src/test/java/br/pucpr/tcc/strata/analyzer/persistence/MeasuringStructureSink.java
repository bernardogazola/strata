package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Flush;
import br.pucpr.tcc.strata.analyzer.persistence.DependencyExtractionReport.Write;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchStructure;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureSink;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class MeasuringStructureSink implements StructureSink {

  private static final long NANOS_PER_MILLI = 1_000_000;

  private static final List<String> MAX_IDS =
      List.of(
          "SELECT coalesce(max(id), 0) FROM code_element",
          "SELECT coalesce(max(id), 0) FROM dependency",
          "SELECT coalesce(max(id), 0) FROM dependency_evidence",
          "SELECT coalesce(max(id), 0) FROM module_dependency");

  private final ElementWriter writer;
  private final SqliteConnections connections;
  private final Path wal;
  private final List<Flush> flushes = new ArrayList<>();
  private Committed committed = Committed.NONE;
  private int acceptCalls;
  private int elementOnlyAccepts;
  private boolean ruleDisagreed;
  private int pendingBatches;
  private int pendingRows;
  private int pendingElements;
  private int pendingDependencies;
  private int pendingEvidence;
  private int pendingModules;
  private long deliveredDependencies;
  private long beginNanos;
  private long acceptNanos;
  private long flushNanos;
  private long probeNanosInVisit;
  private long probeNanos;
  private long walPeak;
  private Flush end;

  MeasuringStructureSink(ElementWriter writer, SqliteConnections connections, Path database) {
    this.writer = writer;
    this.connections = connections;
    this.wal = database.resolveSibling(database.getFileName() + "-wal");
  }

  @Override
  public void begin(List<SourceFile> files) {
    final long started = System.nanoTime();
    writer.begin(files);
    beginNanos = System.nanoTime() - started;
    committed = probe(true);
  }

  @Override
  public void accept(BatchElements elements) {
    elementOnlyAccepts++;
    writer.accept(elements);
  }

  @Override
  public void accept(BatchStructure batch) {
    acceptCalls++;
    pendingBatches++;
    pendingRows += batch.size();
    pendingElements += batch.elements().size();
    final int dependencies = batch.dependencies().dependencies().size();
    final int modules = batch.dependencies().modules().size();
    pendingDependencies += dependencies;
    pendingModules += modules;
    pendingEvidence += batch.dependencies().rows() - dependencies - modules;
    deliveredDependencies += dependencies;
    final long started = System.nanoTime();
    writer.accept(batch);
    final long spent = System.nanoTime() - started;
    acceptNanos += spent;
    final Committed now = probe(true);
    final boolean flushed = !now.equals(committed);
    if (flushed != pendingRows >= AnalysisConstants.ELEMENT_FLUSH_ROWS) {
      ruleDisagreed = true;
    }
    if (flushed) {
      flushes.add(transaction(now, spent, null));
      flushNanos += spent;
      committed = now;
      pendingBatches = 0;
      pendingRows = 0;
      pendingElements = 0;
      pendingDependencies = 0;
      pendingEvidence = 0;
      pendingModules = 0;
    }
  }

  @Override
  public void end(List<SourceFile> files, List<FileElement> fileElements) {
    final long started = System.nanoTime();
    writer.end(files, fileElements);
    final long spent = System.nanoTime() - started;
    final Committed now = probe(false);
    end = transaction(now, spent, fileElements.size());
    committed = now;
  }

  private Flush transaction(Committed now, long nanos, Integer fileElements) {
    final long walBytes = walBytes();
    walPeak = Math.max(walPeak, walBytes);
    final int files = fileElements == null ? 0 : fileElements;
    return new Flush(
        pendingBatches,
        pendingRows,
        pendingElements,
        pendingDependencies,
        pendingEvidence,
        pendingModules,
        fileElements,
        nanos / NANOS_PER_MILLI,
        now.elements() - committed.elements() - pendingElements - files,
        now.dependencies() - committed.dependencies(),
        now.evidence() - committed.evidence(),
        now.modules() - committed.modules(),
        deliveredDependencies - now.dependencies(),
        walBytes);
  }

  private Committed probe(boolean insideVisit) {
    final long started = System.nanoTime();
    try {
      return connections.read(
          c ->
              new Committed(
                  maxId(c, MAX_IDS.get(0)),
                  maxId(c, MAX_IDS.get(1)),
                  maxId(c, MAX_IDS.get(2)),
                  maxId(c, MAX_IDS.get(3))));
    } catch (SQLException failure) {
      throw new IllegalStateException("a sonda de escrita não conseguiu ler o cache", failure);
    } finally {
      final long spent = System.nanoTime() - started;
      probeNanos += spent;
      if (insideVisit) {
        probeNanosInVisit += spent;
      }
    }
  }

  private static long maxId(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private long walBytes() {
    try {
      return Files.exists(wal) ? Files.size(wal) : 0;
    } catch (IOException unreadable) {
      throw new IllegalStateException("o tamanho do -wal não pôde ser lido", unreadable);
    }
  }

  boolean flushRuleReproduced() {
    return !ruleDisagreed;
  }

  int elementOnlyAccepts() {
    return elementOnlyAccepts;
  }

  long millisInsideVisit() {
    return (beginNanos + acceptNanos + probeNanosInVisit) / NANOS_PER_MILLI;
  }

  Write write() {
    return new Write(
        beginNanos / NANOS_PER_MILLI,
        acceptCalls,
        List.copyOf(flushes),
        (acceptNanos - flushNanos) / NANOS_PER_MILLI,
        end,
        (beginNanos + acceptNanos) / NANOS_PER_MILLI + end.millis(),
        walPeak,
        probeNanos / NANOS_PER_MILLI);
  }

  private record Committed(long elements, long dependencies, long evidence, long modules) {

    private static final Committed NONE = new Committed(0, 0, 0, 0);
  }
}
