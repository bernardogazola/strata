package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.repository.JavaSource;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ParseFailureIsolator.Attempt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import spoon.Launcher;
import spoon.reflect.factory.Factory;

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

  private List<SourceFile> preParsed = List.of();
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
    assigned.forEach(this::preParseRoot);
    preParsed = List.copyOf(outcomes.values());
    preParseNanos = System.nanoTime() - started;
    for (final SourceRoot root : assigned.keySet()) {
      modelRoot(root);
    }
    return result();
  }

  private void preParseRoot(SourceRoot root, List<JavaSource> sources) {
    typeFiles.put(root.id(), new ArrayList<>());
    descriptors.put(root.id(), new ArrayList<>());
    for (final JavaSource source : sources) {
      checkCancelled();
      preParseFile(root, source);
    }
  }

  private void preParseFile(SourceRoot root, JavaSource source) {
    final String file = source.relativePath();
    if (source.sizeBytes() > MAX_SOURCE_FILE_BYTES) {
      record(file, root, SourceFileStatus.SKIPPED_TOO_LARGE, null, null);
      return;
    }
    final Optional<byte[]> content;
    try {
      content = readBounded(repositoryRoot.resolve(file));
    } catch (IOException unreadable) {
      Log.warn("não foi possível ler " + repositoryRoot.resolve(file) + ": " + unreadable);
      record(file, root, SourceFileStatus.PARSE_FAILED, ParseFailureCause.UNREADABLE, null);
      return;
    }
    if (content.isEmpty()) {
      Log.warn(file + " cresceu além de " + MAX_SOURCE_FILE_BYTES + " bytes depois da varredura");
      record(file, root, SourceFileStatus.SKIPPED_TOO_LARGE, null, null);
      return;
    }
    final Charset charset = SourceCharsets.detect(content.get());
    final SyntaxPreParser.PreParse parsed =
        SyntaxPreParser.parse(file, new String(content.get(), charset).toCharArray());
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
      sizes.put(file, content.get().length);
    }
  }

  private static Optional<byte[]> readBounded(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("deixou de ser um arquivo comum");
    }
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      final byte[] content = input.readNBytes(MAX_SOURCE_FILE_BYTES + 1);
      return content.length > MAX_SOURCE_FILE_BYTES ? Optional.empty() : Optional.of(content);
    }
  }

  private void modelRoot(SourceRoot root) {
    final long started = System.nanoTime();
    final int buildsBefore = builds;
    final int batchesBefore = batches;
    final List<List<String>> chunks = chunks(typeFiles.get(root.id()));
    splitBatches += Math.max(0, chunks.size() - 1);
    for (final List<String> chunk : chunks) {
      isolateAndVisit(root, chunk);
    }
    // O module-info.java vai em um lote só dele: junto dos tipos, o Spoon lança
    // "Ambiguous package name".
    for (final String descriptor : descriptors.get(root.id())) {
      isolateAndVisit(root, List.of(descriptor));
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
    final List<List<String>> chunks = new ArrayList<>();
    List<String> current = new ArrayList<>();
    long bytes = 0;
    for (final String file : files) {
      final int size = sizes.get(file);
      if (!current.isEmpty() && bytes + size > MAX_MODEL_SOURCE_BYTES) {
        chunks.add(List.copyOf(current));
        current = new ArrayList<>();
        bytes = 0;
      }
      current.add(file);
      bytes += size;
    }
    if (!current.isEmpty()) {
      chunks.add(List.copyOf(current));
    }
    return chunks;
  }

  private void isolateAndVisit(SourceRoot root, List<String> files) {
    final ParseFailureIsolator<String, Factory> isolator =
        new ParseFailureIsolator<>(batch -> build(root, batch));
    final ParseFailureIsolator.Isolation<String> isolation =
        isolator.isolate(files, (batch, factory) -> visit(root, batch, factory));
    for (final String failed : isolation.failed()) {
      fail(failed, ParseFailureCause.MODEL_BUILD_CRASH);
    }
    builds += isolation.builds();
    batches += isolation.batches().size();
    quarantinedBatches += isolation.quarantines();
  }

  private Attempt<Factory> build(SourceRoot root, List<String> files) {
    checkCancelled();
    final long started = System.nanoTime();
    try {
      final Launcher launcher = SpoonLaunchers.configured(emptyClasspath, cancelled);
      for (final String file : files) {
        launcher.addInputResource(repositoryRoot.resolve(file).toString());
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

  private void visit(SourceRoot root, List<String> files, Factory factory) {
    final ModeledBatch batch =
        new ModeledBatch(root, files, factory, repositoryRoot, preParsed, cancelled);
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
    outcomes.put(file, new SourceFile(file, root.id(), status, cause, declarations));
  }

  private void fail(String file, ParseFailureCause cause) {
    final SourceFile current = outcomes.get(file);
    outcomes.put(
        file,
        new SourceFile(
            file, current.rootId(), SourceFileStatus.PARSE_FAILED, cause, current.declarations()));
  }

  private SourceAnalysis result() {
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
