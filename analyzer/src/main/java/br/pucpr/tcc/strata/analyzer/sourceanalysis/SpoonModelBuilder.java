package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.repository.JavaSource;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoots;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public final class SpoonModelBuilder {

  private static final String EMPTY_CLASSPATH_DIRECTORY = "strata-spoon-empty-classpath";

  public static final int COMPLIANCE_LEVEL = 25;

  public SpoonModelBuilder() {}

  public SourceAnalysis analyze(
      ValidatedRepository repository,
      List<SourceRoot> roots,
      BatchVisitor visitor,
      BooleanSupplier cancelled) {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(roots, "roots");
    Objects.requireNonNull(visitor, "visitor");
    Objects.requireNonNull(cancelled, "cancelled");
    final Map<SourceRoot, List<JavaSource>> assigned = assign(repository, roots);
    final Path emptyClasspath = emptyClasspath();
    Log.info(
        "análise de código: "
            + assigned.size()
            + " raiz(es) de código, "
            + assigned.values().stream().mapToInt(List::size).sum()
            + " arquivo(s) .java");
    return new SourceAnalysisRun(repository.snapshot().root(), emptyClasspath, visitor, cancelled)
        .run(assigned);
  }

  private static Map<SourceRoot, List<JavaSource>> assign(
      ValidatedRepository repository, List<SourceRoot> roots) {
    final List<SourceRoot> detected = repository.layout().sourceRoots();
    final Map<String, SourceRoot> byPath = new LinkedHashMap<>();
    detected.forEach(root -> byPath.put(root.relativePath(), root));
    final Map<SourceRoot, List<JavaSource>> assigned = new LinkedHashMap<>();
    for (final SourceRoot root : roots) {
      if (!detected.contains(root)) {
        throw new IllegalArgumentException(
            "a raiz de código '" + root.relativePath() + "' não é uma das do repositório");
      }
      if (assigned.putIfAbsent(root, new ArrayList<>()) != null) {
        throw new IllegalArgumentException(
            "a raiz de código '" + root.relativePath() + "' foi selecionada duas vezes");
      }
    }
    for (final JavaSource source : repository.snapshot().inventory().javaSources()) {
      SourceRoots.deepest(source.relativePath(), byPath.keySet())
          .map(byPath::get)
          .map(assigned::get)
          .ifPresent(files -> files.add(source));
    }
    for (final Map.Entry<SourceRoot, List<JavaSource>> entry : assigned.entrySet()) {
      final SourceRoot root = entry.getKey();
      if (entry.getValue().size() != root.javaFileCount()) {
        throw new IllegalArgumentException(
            "a raiz de código '"
                + root.relativePath()
                + "' foi detectada com "
                + root.javaFileCount()
                + " arquivo(s) .java e recebeu "
                + entry.getValue().size());
      }
    }
    return assigned;
  }

  private static Path emptyClasspath() {
    final Path directory =
        Path.of(System.getProperty("java.io.tmpdir")).resolve(EMPTY_CLASSPATH_DIRECTORY);
    try {
      Files.createDirectories(directory);
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException(directory + " não é um diretório de verdade");
      }
      try (Stream<Path> entries = Files.list(directory)) {
        if (entries.findAny().isPresent()) {
          throw new IllegalStateException(
              directory + " deve ficar vazio: é o classpath contra o qual o Spoon resolve");
        }
      }
      return directory;
    } catch (IOException failure) {
      throw new UncheckedIOException(
          "não foi possível preparar o diretório vazio do classpath do Spoon", failure);
    }
  }
}
