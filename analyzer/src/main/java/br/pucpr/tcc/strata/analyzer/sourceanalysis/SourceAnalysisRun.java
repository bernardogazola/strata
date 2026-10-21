package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.repository.JavaSource;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureIsolator.Attempt;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.reflect.factory.Factory;
import spoon.support.compiler.FileSystemFile;

final class SourceAnalysisRun {

  private static final int MAX_SOURCE_FILE_BYTES = AnalysisConstants.MAX_SOURCE_FILE_BYTES;
  private static final long MAX_MODEL_SOURCE_BYTES = AnalysisConstants.MAX_MODEL_SOURCE_BYTES;

  private final Path repositoryRoot;
  private final Path emptyClasspath;
  private final BatchVisitor visitor;
  private final BooleanSupplier cancelled;

  private final Map<String, SourceFile> outcomes = new LinkedHashMap<>();

  private final Map<SourceRootId, List<String>> typeFiles = new LinkedHashMap<>();

  private final Map<SourceRootId, List<String>> descriptors = new LinkedHashMap<>();

  private final Map<String, Integer> sizes = new LinkedHashMap<>();

  private final Map<String, UnitShape> shapes = new HashMap<>();

  private final Map<SourceRootId, List<List<String>>> plannedChunks = new LinkedHashMap<>();

  private int assignedFiles;

  private List<SourceFile> preParsed = List.of();
  private SimpleNameIndex index;
  private int batches;
  private int splitBatches;
  private int quarantinedBatches;
  private int builds;
  private long preParseNanos;
  private long buildNanos;
  private long longestBuildNanos;
  private String longestBuildRoot = "";
  private long visitNanos;

  SourceAnalysisRun(
      Path repositoryRoot, Path emptyClasspath, BatchVisitor visitor, BooleanSupplier cancelled) {
    this.repositoryRoot = repositoryRoot;
    this.emptyClasspath = emptyClasspath;
    this.visitor = visitor;
    this.cancelled = cancelled;
  }

  SourceAnalysis run(Map<SourceRoot, List<JavaSource>> assigned) {
    final long started = System.nanoTime();
    assignedFiles = assigned.values().stream().mapToInt(List::size).sum();
    final Map<String, String> interned = new HashMap<>();
    final UnaryOperator<String> interner =
        text -> {
          final String known = interned.putIfAbsent(text, text);
          return known == null ? text : known;
        };
    assigned.forEach((root, sources) -> preParseRoot(root, sources, interner));
    interned.clear();
    preParsed = List.copyOf(outcomes.values());
    index = buildIndex(assigned.keySet());
    preParseNanos = System.nanoTime() - started;
    final long beginStarted = System.nanoTime();
    visitor.begin(preParsed);
    visitNanos += System.nanoTime() - beginStarted;
    for (final SourceRoot root : assigned.keySet()) {
      modelRoot(root);
    }
    final SourceAnalysis analysis = result();
    visitor.end(analysis);
    return analysis;
  }

  private void preParseRoot(
      SourceRoot root, List<JavaSource> sources, UnaryOperator<String> interner) {
    typeFiles.put(root.id(), new ArrayList<>());
    descriptors.put(root.id(), new ArrayList<>());
    for (final JavaSource source : sources) {
      checkCancelled();
      preParseFile(root, source, interner);
    }
  }

  private void preParseFile(SourceRoot root, JavaSource source, UnaryOperator<String> interner) {
    final String file = source.relativePath();
    if (source.sizeBytes() > MAX_SOURCE_FILE_BYTES) {
      record(file, root, SourceFileStatus.SKIPPED_TOO_LARGE, null, null);
      return;
    }
    switch (readBounded(file)) {
      case Read.Content content -> preParseContent(root, file, content.bytes(), interner);
      case Read.TooLarge _ -> record(file, root, SourceFileStatus.SKIPPED_TOO_LARGE, null, null);
      case Read.Unreadable _ ->
          record(file, root, SourceFileStatus.PARSE_FAILED, ParseFailureCause.UNREADABLE, null);
    }
  }

  private void preParseContent(
      SourceRoot root, String file, byte[] content, UnaryOperator<String> interner) {
    final char[] text = SourceCharsets.decode(content);
    if (tooDeep(file, text)) {
      record(file, root, SourceFileStatus.PARSE_FAILED, ParseFailureCause.NESTING_TOO_DEEP, null);
      return;
    }
    final Optional<SyntaxPreParser.PreParse> preParse = preParse(file, text, interner);
    if (preParse.isEmpty()) {
      record(file, root, SourceFileStatus.PARSE_FAILED, ParseFailureCause.PRE_PARSE_CRASH, null);
      return;
    }
    final SyntaxPreParser.PreParse parsed = preParse.get();
    if (parsed.syntaxError()) {
      Log.info(file + " tem um erro de sintaxe e não vai para o Spoon");
      record(file, root, SourceFileStatus.PARSE_FAILED, ParseFailureCause.SYNTAX_ERROR, null);
      return;
    }
    record(file, root, SourceFileStatus.ANALYZED, null, parsed.declarations());
    if (parsed.moduleDescriptor()) {
      descriptors.get(root.id()).add(file);
    } else if (!parsed.empty()) {
      typeFiles.get(root.id()).add(file);
      sizes.put(file, content.length);
      shapes.put(file, parsed.shape());
    }
  }

  private static boolean tooDeep(String file, char[] text) {
    final NestingScreen.Nesting nesting = NestingScreen.measure(text);
    if (!nesting.tooDeep()) {
      return false;
    }
    Log.warn(
        file
            + " aninha "
            + nesting.bracketDepth()
            + " colchete(s) e encadeia "
            + nesting.arrowChain()
            + " seta(s) de lambda, acima de "
            + AnalysisConstants.MAX_SOURCE_NESTING_DEPTH
            + "; ele não vai para o parser");
    return true;
  }

  private static Optional<SyntaxPreParser.PreParse> preParse(
      String file, char[] text, UnaryOperator<String> interner) {
    try {
      return Optional.of(SyntaxPreParser.parse(file, text, interner));
    } catch (CancellationException cancellation) {
      throw cancellation;
    } catch (RuntimeException | StackOverflowError crash) {
      Log.warn(
          "o pré-parse de "
              + file
              + " lançou "
              + crash.getClass().getName()
              + "; o arquivo é reportado como falha de parse");
      return Optional.empty();
    }
  }

  private Read readBounded(String file) {
    final Path path = repositoryRoot.resolve(file);
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("deixou de ser um arquivo comum");
      }
      try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        final byte[] content = input.readNBytes(MAX_SOURCE_FILE_BYTES + 1);
        if (content.length > MAX_SOURCE_FILE_BYTES) {
          Log.warn(
              file + " cresceu além de " + MAX_SOURCE_FILE_BYTES + " bytes depois da varredura");
          return new Read.TooLarge();
        }
        return new Read.Content(content);
      }
    } catch (IOException unreadable) {
      Log.warn("não foi possível ler " + path + ": " + unreadable);
      return new Read.Unreadable();
    }
  }

  private sealed interface Read permits Read.Content, Read.TooLarge, Read.Unreadable {

    record Content(byte[] bytes) implements Read {}

    record TooLarge() implements Read {}

    record Unreadable() implements Read {}
  }

  private SimpleNameIndex buildIndex(Set<SourceRoot> roots) {
    final long started = System.nanoTime();
    final IndexBuilder builder = SimpleNameIndex.builder();
    int units = 0;
    for (final SourceRoot root : roots) {
      final List<List<String>> chunks = chunks(typeFiles.get(root.id()));
      plannedChunks.put(root.id(), chunks);
      for (final List<String> chunk : chunks) {
        for (final String file : chunk) {
          builder.add(shapes.get(file), repositoryRoot.resolve(file));
          units++;
        }
      }
    }
    shapes.clear();
    final SimpleNameIndex built = builder.addDeclarations(preParsed).build();
    Log.info(
        "índice de nomes: "
            + units
            + " unidade(s), "
            + built.size()
            + " tipo(s) nomeado(s), "
            + built.retainedStaticImports()
            + " import(s) estático(s) retido(s), montado em "
            + (System.nanoTime() - started) / 1_000_000
            + " ms");
    return built;
  }

  private void modelRoot(SourceRoot root) {
    final long started = System.nanoTime();
    final int buildsBefore = builds;
    final int batchesBefore = batches;
    final List<List<String>> chunks = plannedChunks.get(root.id());
    splitBatches += Math.max(0, chunks.size() - 1);
    for (final List<String> chunk : chunks) {
      readAndModel(root, chunk);
    }
    // O module-info.java vai em um lote só dele: junto dos tipos, o Spoon lança
    // "Ambiguous package name".
    for (final String descriptor : descriptors.get(root.id())) {
      readAndModel(root, List.of(descriptor));
    }
    Log.info(
        "raiz de código '"
            + root.relativePath()
            + "': "
            + (batches - batchesBefore)
            + " modelo(s), "
            + (builds - buildsBefore)
            + " build(s), "
            + (System.nanoTime() - started) / 1_000_000
            + " ms");
  }

  private List<List<String>> chunks(List<String> files) {
    final Map<String, List<String>> byDirectory = new TreeMap<>();
    for (final String file : files) {
      byDirectory.computeIfAbsent(parentOf(file), directory -> new ArrayList<>()).add(file);
    }
    final Chunks chunks = new Chunks();
    for (final List<String> directory : byDirectory.values()) {
      final long bytes = directory.stream().mapToLong(file -> sizes.get(file)).sum();
      if (bytes <= MAX_MODEL_SOURCE_BYTES) {
        chunks.add(directory, bytes);
      } else {
        for (final String file : directory) {
          chunks.add(List.of(file), sizes.get(file));
        }
      }
    }
    return chunks.done();
  }

  private static String parentOf(String file) {
    final int slash = file.lastIndexOf('/');
    return slash < 0 ? "" : file.substring(0, slash);
  }

  private static final class Chunks {

    private final List<List<String>> done = new ArrayList<>();
    private final List<String> current = new ArrayList<>();
    private long bytes;

    private void add(List<String> files, long size) {
      if (!current.isEmpty() && bytes + size > MAX_MODEL_SOURCE_BYTES) {
        flush();
      }
      current.addAll(files);
      bytes += size;
    }

    private List<List<String>> done() {
      if (!current.isEmpty()) {
        flush();
      }
      return List.copyOf(done);
    }

    private void flush() {
      done.add(current.stream().sorted().toList());
      current.clear();
      bytes = 0;
    }
  }

  private void readAndModel(SourceRoot root, List<String> files) {
    final Map<String, byte[]> contents = new LinkedHashMap<>();
    for (final String file : files) {
      readForBatch(file).ifPresent(content -> contents.put(file, content));
    }
    if (!contents.isEmpty()) {
      isolateAndVisit(root, List.copyOf(contents.keySet()), contents);
    }
  }

  private Optional<byte[]> readForBatch(String file) {
    return switch (readBounded(file)) {
      case Read.Content content when tooDeep(file, SourceCharsets.decode(content.bytes())) -> {
        fail(file, ParseFailureCause.NESTING_TOO_DEEP);
        yield Optional.empty();
      }
      case Read.Content content -> Optional.of(content.bytes());
      case Read.TooLarge _ -> {
        reclassify(file, SourceFileStatus.SKIPPED_TOO_LARGE, null);
        yield Optional.empty();
      }
      case Read.Unreadable _ -> {
        fail(file, ParseFailureCause.UNREADABLE);
        yield Optional.empty();
      }
    };
  }

  private void isolateAndVisit(SourceRoot root, List<String> files, Map<String, byte[]> contents) {
    final ParseFailureIsolator<String, Factory> isolator =
        new ParseFailureIsolator<>(batch -> build(root, batch, contents));
    final ParseFailureIsolator.Isolation<String> isolation =
        isolator.isolate(files, (batch, factory) -> visit(root, batch, factory, contents));
    for (final String failed : isolation.failed()) {
      fail(failed, ParseFailureCause.MODEL_BUILD_CRASH);
    }
    builds += isolation.builds();
    batches += isolation.batches().size();
    quarantinedBatches += isolation.quarantines();
  }

  private Attempt<Factory> build(
      SourceRoot root, List<String> files, Map<String, byte[]> contents) {
    checkCancelled();
    final long started = System.nanoTime();
    try {
      final Launcher launcher = SpoonLaunchers.configured(emptyClasspath, cancelled);
      for (final String file : files) {
        launcher.addInputResource(
            new ReadSource(repositoryRoot.resolve(file).toFile(), contents.get(file)));
      }
      launcher.buildModel();
      return new Attempt.Built<>(launcher.getFactory());
    } catch (CancellationException cancellation) {
      throw cancellation;
    } catch (RuntimeException | StackOverflowError failure) {
      checkCancelled();
      Log.warn(
          "o build do modelo de "
              + files.size()
              + " arquivo(s) de '"
              + root.relativePath()
              + "' falhou: "
              + failure.getClass().getName()
              + ": "
              + firstLine(failure.getMessage()));
      return new Attempt.Failed<>(failure);
    } finally {
      final long elapsed = System.nanoTime() - started;
      buildNanos += elapsed;
      if (elapsed > longestBuildNanos) {
        longestBuildNanos = elapsed;
        longestBuildRoot = root.relativePath();
      }
    }
  }

  private static final class ReadSource extends FileSystemFile {

    private final byte[] content;

    private ReadSource(File file, byte[] content) {
      super(file);
      this.content = content;
    }

    @Override
    public InputStream getContent() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public char[] getContentChars(Environment environment) {
      return SourceCharsets.decode(content);
    }
  }

  private void visit(
      SourceRoot root, List<String> files, Factory factory, Map<String, byte[]> contents) {
    final ModeledBatch batch =
        new ModeledBatch(
            root, files, factory, repositoryRoot, preParsed, index, cancelled, contents);
    final Set<String> modeled = batch.modeledFiles();
    for (final String file : files) {
      if (!modeled.contains(file)) {
        Log.warn(
            file
                + " era válido e não vazio, e o Spoon não montou nenhuma unidade de compilação para ele");
        fail(file, ParseFailureCause.DROPPED_BY_SPOON);
      }
    }
    final long started = System.nanoTime();
    try {
      visitor.visit(batch);
    } finally {
      batch.close();
      visitNanos += System.nanoTime() - started;
    }
    for (final String file : batch.extractionFailures()) {
      fail(file, ParseFailureCause.EXTRACTION_CRASH);
    }
  }

  private void record(
      String file,
      SourceRoot root,
      SourceFileStatus status,
      ParseFailureCause cause,
      SourceFile.Declarations declarations) {
    final SourceFile outcome = new SourceFile(file, root.id(), status, cause, declarations);
    if (outcomes.putIfAbsent(file, outcome) != null) {
      throw new IllegalStateException(file + " recebeu um segundo desfecho");
    }
  }

  private void fail(String file, ParseFailureCause cause) {
    reclassify(file, SourceFileStatus.PARSE_FAILED, cause);
  }

  private void reclassify(String file, SourceFileStatus status, ParseFailureCause cause) {
    final SourceFile current = outcomes.get(file);
    outcomes.put(
        file, new SourceFile(file, current.rootId(), status, cause, current.declarations()));
  }

  private SourceAnalysis result() {
    if (outcomes.size() != assignedFiles) {
      throw new IllegalStateException(
          assignedFiles + " arquivo(s) foram atribuídos e " + outcomes.size() + " têm desfecho");
    }
    final List<SourceIssue> issues = new ArrayList<>();
    for (final SourceFile file : outcomes.values()) {
      switch (file.status()) {
        case SKIPPED_TOO_LARGE ->
            issues.add(SourceIssue.fileTooLarge(file.relativePath(), MAX_SOURCE_FILE_BYTES));
        case PARSE_FAILED -> issues.add(SourceIssue.parseFailed(file.relativePath()));
        case ANALYZED -> {}
      }
    }
    return new SourceAnalysis(
        List.copyOf(outcomes.values()),
        issues,
        new SourceAnalysis.Diagnostics(
            batches,
            splitBatches,
            quarantinedBatches,
            builds,
            preParseNanos / 1_000_000,
            buildNanos / 1_000_000,
            longestBuildNanos / 1_000_000,
            longestBuildRoot,
            visitNanos / 1_000_000));
  }

  private void checkCancelled() {
    if (cancelled.getAsBoolean()) {
      throw new CancellationException("análise de código cancelada");
    }
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    final int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
