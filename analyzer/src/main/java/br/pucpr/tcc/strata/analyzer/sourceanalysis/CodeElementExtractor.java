package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import spoon.reflect.declaration.CtCompilationUnit;

public final class CodeElementExtractor implements BatchVisitor {

  private static final String MODULE_DESCRIPTOR = "module-info.java";

  private final ElementSink sink;
  private final Map<SourceRootId, SourceRootKind> rootKinds = new HashMap<>();

  private final Map<CodeElementId, String> claimedTypes = new HashMap<>();

  private final Set<CodeElementId> emittedPackages = new HashSet<>();
  private final Map<String, List<SourceIssue>> issuesByFile = new LinkedHashMap<>();
  private final Set<DuplicatePair> reportedDuplicates = new HashSet<>();
  private final Set<String> filesWithTooLongIds = new HashSet<>();
  private SimpleNameIndex index;
  private List<String> fileOrder = List.of();

  public CodeElementExtractor(List<SourceRoot> roots, ElementSink sink) {
    for (final SourceRoot root : List.copyOf(Objects.requireNonNull(roots, "roots"))) {
      rootKinds.put(root.id(), root.kind());
    }
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  @Override
  public void begin(List<SourceFile> files) {
    fileOrder = files.stream().map(SourceFile::relativePath).toList();
    sink.begin(files);
  }

  @Override
  public void visit(ModeledBatch batch) {
    index = batch.index();
    collect(batch, batch.perCompilationUnit((path, unit) -> extract(batch, path, unit)));
  }

  @Override
  public void end(SourceAnalysis analysis) {
    final Map<CodeElementId, String> claimedFiles = new HashMap<>();
    final List<FileElement> fileElements = new ArrayList<>();
    for (final SourceFile file : analysis.files()) {
      final String path = file.relativePath();
      final CodeElementId id = fileId(path);
      if (!id.fitsCeiling()) {
        tooLong(path);
        continue;
      }
      final String first = claimedFiles.putIfAbsent(id, path);
      if (first != null) {
        duplicate(path, first);
        continue;
      }
      fileElements.add(fileElement(file, id));
    }
    sink.end(analysis.files(), fileElements);
  }

  public UnitElements extract(ModeledBatch batch, String relativePath, CtCompilationUnit unit) {
    index = batch.index();
    return new UnitExtraction(batch.root(), relativePath, index).extract(unit);
  }

  UnitExtraction.Walked walk(ModeledBatch batch, String relativePath, CtCompilationUnit unit) {
    index = batch.index();
    return new UnitExtraction(batch.root(), relativePath, index).walk(unit);
  }

  public void collect(ModeledBatch batch, List<UnitElements> units) {
    sink.accept(dedupe(units));
  }

  public BatchElements dedupe(List<UnitElements> units) {
    final List<PackageElement> packages = new ArrayList<>();
    final List<TypeElement> types = new ArrayList<>();
    final List<MethodElement> methods = new ArrayList<>();
    final Collected collected = new Collected(packages, types, methods);
    for (final UnitElements unit : units) {
      final Map<CodeElementId, PackageElement> unitPackages = new HashMap<>();
      unit.packages().forEach(declared -> unitPackages.put(declared.id(), declared));
      for (final DeclaredType declared : unit.types()) {
        collect(unit.relativePath(), declared, unitPackages, collected);
      }
    }
    return new BatchElements(packages, types, methods);
  }

  SimpleNameIndex index() {
    if (index == null) {
      throw new IllegalStateException("nenhum lote foi visitado ainda: use ModeledBatch.index()");
    }
    return index;
  }

  public List<SourceIssue> issues() {
    final List<SourceIssue> ordered = new ArrayList<>();
    for (final String file : fileOrder) {
      ordered.addAll(issuesByFile.getOrDefault(file, List.of()));
    }
    final Set<String> known = Set.copyOf(fileOrder);
    issuesByFile.forEach(
        (file, issues) -> {
          if (!known.contains(file)) {
            ordered.addAll(issues);
          }
        });
    return List.copyOf(ordered);
  }

  private void collect(
      String path,
      DeclaredType declared,
      Map<CodeElementId, PackageElement> unitPackages,
      Collected collected) {
    final TypeElement type = declared.type();
    if (!type.id().fitsCeiling()) {
      tooLong(path);
      return;
    }
    final String first = claimedTypes.putIfAbsent(type.id(), path);
    if (first != null) {
      duplicate(path, first);
      return;
    }
    if (emittedPackages.add(type.packageId())) {
      collected.packages().add(Objects.requireNonNull(unitPackages.get(type.packageId())));
    }
    collected.types().add(type);
    for (final MethodElement method : declared.methods()) {
      if (method.id().fitsCeiling()) {
        collected.methods().add(method);
      } else {
        tooLong(path);
      }
    }
    for (final DeclaredType nested : declared.nested()) {
      collect(path, nested, unitPackages, collected);
    }
  }

  private void duplicate(String path, String first) {
    if (reportedDuplicates.add(new DuplicatePair(path, first))) {
      issue(path, SourceIssue.duplicateElement(path, first));
    }
  }

  private void tooLong(String path) {
    if (filesWithTooLongIds.add(path)) {
      issue(path, SourceIssue.elementIdTooLong(path, AnalysisConstants.MAX_ELEMENT_ID_BYTES));
    }
  }

  private void issue(String path, SourceIssue issue) {
    issuesByFile.computeIfAbsent(path, file -> new ArrayList<>()).add(issue);
  }

  private static CodeElementId fileId(String path) {
    return CodeElementId.ofFile(path);
  }

  private FileElement fileElement(SourceFile file, CodeElementId id) {
    final String normalized = id.sortKey();
    return new FileElement(
        id,
        CodeElement.label(normalized.substring(normalized.lastIndexOf('/') + 1)),
        normalized,
        file.relativePath(),
        file.rootId(),
        declaredPackage(file),
        rootKinds.get(file.rootId()) == SourceRootKind.TEST);
  }

  private CodeElementId declaredPackage(SourceFile file) {
    final SourceFile.Declarations declarations = file.declarations();
    final String path = file.relativePath();
    if (declarations == null
        || path.equals(MODULE_DESCRIPTOR)
        || path.endsWith("/" + MODULE_DESCRIPTOR)) {
      return null;
    }
    final CodeElementId declared = CodeElementId.ofPackage(declarations.packageName());
    return emittedPackages.contains(declared) ? declared : null;
  }

  public record UnitElements(
      String relativePath, List<PackageElement> packages, List<DeclaredType> types) {

    public UnitElements {
      Objects.requireNonNull(relativePath, "relativePath");
      packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
      types = List.copyOf(Objects.requireNonNull(types, "types"));
    }
  }

  public record DeclaredType(
      TypeElement type, List<MethodElement> methods, List<DeclaredType> nested) {

    public DeclaredType {
      Objects.requireNonNull(type, "type");
      methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
      nested = List.copyOf(Objects.requireNonNull(nested, "nested"));
    }
  }

  private record Collected(
      List<PackageElement> packages, List<TypeElement> types, List<MethodElement> methods) {}

  private record DuplicatePair(String filePath, String firstFilePath) {}
}
