package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.factory.Factory;

public final class ModeledBatch {

  private final SourceRoot root;
  private final List<String> files;
  private final List<SourceFile> analysisFiles;
  private final Map<String, CtCompilationUnit> unitsByFile = new TreeMap<>();
  private final Set<String> extractionFailures = new TreeSet<>();
  private CtModel model;
  private boolean open = true;

  ModeledBatch(
      SourceRoot root,
      List<String> files,
      Factory factory,
      Path repositoryRoot,
      List<SourceFile> analysisFiles) {
    this.root = Objects.requireNonNull(root, "root");
    this.files = List.copyOf(Objects.requireNonNull(files, "files"));
    this.analysisFiles = List.copyOf(Objects.requireNonNull(analysisFiles, "analysisFiles"));
    this.model = Objects.requireNonNull(factory, "factory").getModel();
    final Map<Path, String> byAbsolutePath = new HashMap<>();
    for (final String file : this.files) {
      byAbsolutePath.put(repositoryRoot.resolve(file), file);
    }
    for (final CtCompilationUnit unit : factory.CompilationUnit().getMap().values()) {
      final File source = unit.getFile();
      final String file = source == null ? null : byAbsolutePath.get(source.toPath());
      if (file != null) {
        unitsByFile.put(file, unit);
      }
    }
  }

  public SourceRoot root() {
    return root;
  }

  public List<String> files() {
    return files;
  }

  public CtModel model() {
    ensureOpen();
    return model;
  }

  public List<SourceFile> analysisFiles() {
    return analysisFiles;
  }

  public <T> List<T> perCompilationUnit(CompilationUnitFunction<T> function) {
    Objects.requireNonNull(function, "function");
    ensureOpen();
    final List<T> results = new ArrayList<>();
    for (final Map.Entry<String, CtCompilationUnit> entry : unitsByFile.entrySet()) {
      final String file = entry.getKey();
      if (extractionFailures.contains(file)) {
        continue;
      }
      try {
        final T result = function.apply(file, entry.getValue());
        if (result != null) {
          results.add(result);
        }
      } catch (CancellationException cancelled) {
        throw cancelled;
      } catch (RuntimeException | StackOverflowError failure) {
        Log.warn(
            "a extração falhou em "
                + file
                + ": "
                + failure.getClass().getName()
                + "; o arquivo é reportado como falha de parse");
        extractionFailures.add(file);
      }
    }
    return List.copyOf(results);
  }

  Set<String> modeledFiles() {
    return Set.copyOf(unitsByFile.keySet());
  }

  Set<String> extractionFailures() {
    return Set.copyOf(extractionFailures);
  }

  void close() {
    open = false;
    model = null;
    unitsByFile.clear();
  }

  private void ensureOpen() {
    if (!open) {
      throw new IllegalStateException(
          "o lote de "
              + root.relativePath()
              + " foi usado depois do fim da sua visita; um visitor não pode guardar o modelo");
    }
  }

  @FunctionalInterface
  public interface CompilationUnitFunction<T> {

    T apply(String relativePath, CtCompilationUnit unit);
  }
}
