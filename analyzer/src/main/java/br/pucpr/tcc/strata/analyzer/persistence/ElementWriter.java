package br.pucpr.tcc.strata.analyzer.persistence;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.ElementRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceFileDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceFileRow;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceRootDao;
import br.pucpr.tcc.strata.analyzer.persistence.dao.SourceRootRow;
import br.pucpr.tcc.strata.analyzer.repository.JavaSource;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchElements;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ElementSink;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Modifier;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.io.Serial;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ElementWriter implements ElementSink {

  private final SqliteConnections connections;
  private final long analysisPk;
  private final List<SourceRoot> roots;
  private final List<JavaSource> sources;

  private final Map<SourceRootId, Long> rootPks = new HashMap<>();

  private final Map<String, SourceFileRow> fileRows = new HashMap<>();

  private final Map<CodeElementId, Long> index = new HashMap<>();

  private final List<BatchElements> buffered = new ArrayList<>();
  private int bufferedRows;
  private Stage stage = Stage.CREATED;

  public ElementWriter(
      SqliteConnections connections,
      long analysisPk,
      List<SourceRoot> roots,
      List<JavaSource> sources) {
    this.connections = Objects.requireNonNull(connections, "connections");
    this.analysisPk = analysisPk;
    this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
    this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
  }

  @Override
  public void begin(List<SourceFile> files) {
    requireStage(Stage.CREATED);
    final Map<String, JavaSource> scanned =
        sources.stream().collect(Collectors.toMap(JavaSource::relativePath, Function.identity()));
    final Set<SourceRootId> selected =
        roots.stream().map(SourceRoot::id).collect(Collectors.toSet());
    for (final SourceFile file : files) {
      if (!scanned.containsKey(file.relativePath()) || !selected.contains(file.rootId())) {
        throw new IllegalStateException(
            "não é um arquivo varrido de uma raiz selecionada: " + file.relativePath());
      }
    }
    final Begun begun = write(connection -> writeRootsAndFiles(connection, files, scanned));
    begun.roots().forEach(row -> rootPks.put(row.sourceRootId(), row.pk()));
    begun.files().forEach(row -> fileRows.put(row.relativePath(), row));
    stage = Stage.BEGUN;
  }

  @Override
  public void accept(BatchElements elements) {
    requireStage(Stage.BEGUN);
    buffered.add(Objects.requireNonNull(elements, "elements"));
    bufferedRows += elements.size();
    if (bufferedRows >= AnalysisConstants.ELEMENT_FLUSH_ROWS) {
      final Map<CodeElementId, Long> committed = write(this::insertBuffered);
      learn(committed);
    }
  }

  @Override
  public void end(List<SourceFile> files, List<FileElement> fileElements) {
    requireStage(Stage.BEGUN);
    final List<SourceFileRow> changed = changedStatuses(files);
    final Map<CodeElementId, Long> committed =
        write(
            connection -> {
              final Map<CodeElementId, Long> numbered = insertBuffered(connection);
              for (final SourceFileRow file : changed) {
                SourceFileDao.updateStatus(connection, file.pk(), file.status());
              }
              insertFileElements(connection, fileElements, numbered);
              ElementDao.markTestPackages(connection, analysisPk);
              return numbered;
            });
    learn(committed);
    changed.forEach(row -> fileRows.put(row.relativePath(), row));
    stage = Stage.ENDED;
  }

  public OptionalLong pkOf(CodeElementId id) {
    final Long pk = index.get(id);
    return pk == null ? OptionalLong.empty() : OptionalLong.of(pk);
  }

  private Begun writeRootsAndFiles(
      Connection connection, List<SourceFile> files, Map<String, JavaSource> scanned)
      throws SQLException {
    final List<SourceRootRow> rootRows = new ArrayList<>(roots.size());
    long next = SourceRootDao.nextId(connection);
    for (final SourceRoot root : roots) {
      rootRows.add(
          new SourceRootRow(
              next++, root.id(), root.relativePath(), root.kind(), root.javaFileCount()));
    }
    SourceRootDao.insert(connection, analysisPk, rootRows);
    final List<SourceFileRow> rows = new ArrayList<>(files.size());
    next = SourceFileDao.nextId(connection);
    for (final SourceFile file : files) {
      final JavaSource source = scanned.get(file.relativePath());
      rows.add(
          new SourceFileRow(
              next++,
              file.relativePath(),
              source.sizeBytes(),
              source.lastModifiedMillis(),
              file.status()));
    }
    SourceFileDao.insert(connection, analysisPk, rows);
    return new Begun(rootRows, rows);
  }

  private Map<CodeElementId, Long> insertBuffered(Connection connection) throws SQLException {
    final Map<CodeElementId, Long> numbered = new HashMap<>();
    final List<ElementRow> rows = new ArrayList<>(bufferedRows);
    long next = ElementDao.nextId(connection);
    for (final BatchElements batch : buffered) {
      for (final PackageElement element : batch.packages()) {
        numbered.put(element.id(), next);
        rows.add(packageRow(next++, element));
      }
      for (final TypeElement element : batch.types()) {
        numbered.put(element.id(), next);
        rows.add(typeRow(next++, element, parentPk(element.packageId(), numbered)));
      }
      for (final MethodElement element : batch.methods()) {
        rows.add(methodRow(next++, element, parentPk(element.declaringTypeId(), numbered)));
      }
    }
    ElementDao.insert(connection, analysisPk, rows);
    return numbered;
  }

  private void insertFileElements(
      Connection connection, List<FileElement> fileElements, Map<CodeElementId, Long> numbered)
      throws SQLException {
    final List<ElementRow> rows = new ArrayList<>(fileElements.size());
    long next = ElementDao.nextId(connection);
    for (final FileElement element : fileElements) {
      final Long parent =
          element.packageId() == null ? null : parentPk(element.packageId(), numbered);
      rows.add(
          new ElementRow(
              next++,
              element.id(),
              ElementKind.FILE,
              parent,
              element.simpleName(),
              element.qualifiedName(),
              element.id().sortKey(),
              null,
              "",
              false,
              element.isTest(),
              rootPk(element.rootId()),
              filePk(element.relativePath()),
              null,
              null));
    }
    ElementDao.insert(connection, analysisPk, rows);
  }

  private List<SourceFileRow> changedStatuses(List<SourceFile> files) {
    final List<SourceFileRow> changed = new ArrayList<>();
    for (final SourceFile file : files) {
      final SourceFileRow written = fileRows.get(file.relativePath());
      if (written == null) {
        throw new IllegalStateException("não foi gravado pelo begin: " + file.relativePath());
      }
      if (written.status() != file.status()) {
        changed.add(
            new SourceFileRow(
                written.pk(),
                written.relativePath(),
                written.sizeBytes(),
                written.mtimeMillis(),
                file.status()));
      }
    }
    return changed;
  }

  private static ElementRow packageRow(long pk, PackageElement element) {
    return new ElementRow(
        pk,
        element.id(),
        ElementKind.PACKAGE,
        null,
        element.simpleName(),
        element.qualifiedName(),
        element.id().sortKey(),
        null,
        "",
        false,
        false,
        null,
        null,
        null,
        null);
  }

  private ElementRow typeRow(long pk, TypeElement element, long parentPk) {
    return new ElementRow(
        pk,
        element.id(),
        ElementKind.TYPE,
        parentPk,
        element.simpleName(),
        element.qualifiedName(),
        element.id().sortKey(),
        element.typeKind(),
        modifiers(element.modifiers()),
        false,
        element.isTest(),
        rootPk(element.rootId()),
        filePk(element.filePath()),
        startLine(element.lines()),
        endLine(element.lines()));
  }

  private ElementRow methodRow(long pk, MethodElement element, long parentPk) {
    return new ElementRow(
        pk,
        element.id(),
        ElementKind.METHOD,
        parentPk,
        element.simpleName(),
        element.qualifiedName(),
        element.id().sortKey(),
        null,
        modifiers(element.modifiers()),
        false,
        element.isTest(),
        rootPk(element.rootId()),
        filePk(element.filePath()),
        startLine(element.lines()),
        endLine(element.lines()));
  }

  private long parentPk(CodeElementId parent, Map<CodeElementId, Long> numbered) {
    final Long pk = numbered.getOrDefault(parent, index.get(parent));
    if (pk == null) {
      throw new IllegalStateException("um pai que nunca foi gravado: " + parent);
    }
    return pk;
  }

  private long rootPk(SourceRootId rootId) {
    final Long pk = rootPks.get(rootId);
    if (pk == null) {
      throw new IllegalStateException("não é uma raiz selecionada: " + rootId.value());
    }
    return pk;
  }

  private long filePk(String relativePath) {
    final SourceFileRow row = fileRows.get(relativePath);
    if (row == null) {
      throw new IllegalStateException("não foi gravado pelo begin: " + relativePath);
    }
    return row.pk();
  }

  private static String modifiers(Set<Modifier> modifiers) {
    return modifiers.stream().sorted().map(Modifier::keyword).collect(Collectors.joining(" "));
  }

  private static Integer startLine(SourceLines lines) {
    return lines.available() ? lines.start() : null;
  }

  private static Integer endLine(SourceLines lines) {
    return lines.available() ? lines.end() : null;
  }

  private void learn(Map<CodeElementId, Long> committed) {
    index.putAll(committed);
    buffered.clear();
    bufferedRows = 0;
  }

  private void requireStage(Stage expected) {
    if (stage != expected) {
      throw new IllegalStateException(
          "o ElementWriter está em " + stage + ", e não em " + expected);
    }
  }

  private <T> T write(SqlWork<T> work) {
    try {
      return connections.write(work);
    } catch (SQLException failure) {
      throw new WriteFailedException(failure);
    }
  }

  private enum Stage {
    CREATED,
    BEGUN,
    ENDED
  }

  private record Begun(List<SourceRootRow> roots, List<SourceFileRow> files) {}

  public static final class WriteFailedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    WriteFailedException(SQLException cause) {
      super("a escrita dos elementos foi recusada e desfeita", cause);
    }

    @Override
    public synchronized SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }
}
