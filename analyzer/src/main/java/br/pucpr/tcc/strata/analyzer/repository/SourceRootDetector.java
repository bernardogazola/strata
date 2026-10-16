package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class SourceRootDetector {

  private static final String REPOSITORY_ROOT = "";
  private static final String SEPARATOR = "/";

  List<SourceRoot> detect(Path root, RepositoryInventory inventory, BuildDescription build) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(inventory, "inventory");
    Objects.requireNonNull(build, "build");
    final List<Candidate> scanned = conventionScan(inventory, build);
    final List<Candidate> candidates =
        build.buildSystem() == BuildSystem.MAVEN
            ? refineByReactor(scanned, inventory, build)
            : scanned;
    return assign(
        inventory, candidates.isEmpty() ? inferFromPackages(root, inventory) : candidates);
  }

  private record Candidate(String relativePath, SourceRootKind kind, String moduleDirectory) {}

  private static List<Candidate> conventionScan(
      RepositoryInventory inventory, BuildDescription build) {
    final Set<String> gradleModules =
        build.buildSystem() == BuildSystem.GRADLE
            ? Set.copyOf(build.moduleDirectories())
            : Set.of();
    final Map<String, Candidate> found = new LinkedHashMap<>();
    for (final JavaSource source : inventory.javaSources()) {
      conventionRootOf(source.relativePath().split(SEPARATOR, -1), gradleModules)
          .ifPresent(candidate -> found.putIfAbsent(candidate.relativePath(), candidate));
    }
    return List.copyOf(found.values());
  }

  private static Optional<Candidate> conventionRootOf(
      String[] segments, Set<String> gradleModules) {
    final int fileIndex = segments.length - 1;
    for (int index = 0; index < fileIndex; index++) {
      final Optional<Candidate> match = matchAt(segments, index, fileIndex, gradleModules);
      if (match.isPresent()) {
        return match;
      }
    }
    return Optional.empty();
  }

  private static Optional<Candidate> matchAt(
      String[] segments, int index, int fileIndex, Set<String> gradleModules) {
    final boolean sourceSet =
        index + 2 < fileIndex
            && "src".equals(segments[index])
            && "java".equals(segments[index + 2]);
    if (sourceSet) {
      final String set = segments[index + 1];
      final boolean conventional = "main".equals(set) || "test".equals(set);
      final boolean gradleSet =
          !conventional
              && !gradleModules.isEmpty()
              && gradleModules.contains(join(segments, 0, index));
      if (conventional || gradleSet) {
        final boolean test = "test".equals(set) || (gradleSet && containsIgnoringCase(set, "test"));
        return Optional.of(
            candidate(segments, index, 3, test ? SourceRootKind.TEST : SourceRootKind.MAIN));
      }
    }
    final boolean mavenGenerated =
        index + 2 < fileIndex
            && "target".equals(segments[index])
            && ("generated-sources".equals(segments[index + 1])
                || "generated-test-sources".equals(segments[index + 1]));
    if (mavenGenerated) {
      return Optional.of(candidate(segments, index, 3, SourceRootKind.GENERATED));
    }
    final boolean gradleGenerated =
        index + 5 < fileIndex
            && "build".equals(segments[index])
            && "generated".equals(segments[index + 1])
            && "sources".equals(segments[index + 2])
            && "java".equals(segments[index + 4]);
    if (gradleGenerated) {
      return Optional.of(candidate(segments, index, 6, SourceRootKind.GENERATED));
    }
    return Optional.empty();
  }

  private static Candidate candidate(
      String[] segments, int index, int length, SourceRootKind kind) {
    return new Candidate(join(segments, 0, index + length), kind, join(segments, 0, index));
  }

  private static List<Candidate> refineByReactor(
      List<Candidate> scanned, RepositoryInventory inventory, BuildDescription build) {
    final Set<String> modules = Set.copyOf(build.moduleDirectories());
    final Map<String, Candidate> refined = new LinkedHashMap<>();
    for (final Candidate candidate : scanned) {
      if (modules.contains(candidate.moduleDirectory())) {
        refined.putIfAbsent(candidate.relativePath(), candidate);
      }
    }
    if (!build.declaredSourceDirectories().isEmpty()) {
      final Set<String> javaDirectories = directoriesHoldingJava(inventory);
      for (final BuildDescription.DeclaredSourceDirectory declared :
          build.declaredSourceDirectories()) {
        if (javaDirectories.contains(declared.relativePath())) {
          refined.putIfAbsent(
              declared.relativePath(),
              new Candidate(declared.relativePath(), declared.kind(), declared.relativePath()));
        }
      }
    }
    if (refined.isEmpty() && !scanned.isEmpty()) {
      Log.info(
          "o reator Maven não casou com nenhuma das "
              + scanned.size()
              + " raiz(es) de código convencionais; elas ficam como estão");
      return scanned;
    }
    return List.copyOf(refined.values());
  }

  private static Set<String> directoriesHoldingJava(RepositoryInventory inventory) {
    final Set<String> directories = new HashSet<>();
    for (final JavaSource source : inventory.javaSources()) {
      String directory = parentOf(source.relativePath());
      while (directories.add(directory) && !directory.isEmpty()) {
        directory = parentOf(directory);
      }
    }
    return directories;
  }

  private static List<Candidate> inferFromPackages(Path root, RepositoryInventory inventory) {
    final Map<String, Candidate> inferred = new LinkedHashMap<>();
    for (final JavaSource source : inventory.javaSources()) {
      final String directory = parentOf(source.relativePath());
      final String rootPath =
          declaredPackageOf(root, source)
              .map(declared -> withoutPackage(directory, declared))
              .orElse(directory);
      inferred.putIfAbsent(rootPath, new Candidate(rootPath, kindByName(rootPath), rootPath));
    }
    if (!inferred.isEmpty()) {
      Log.info(
          "nenhuma raiz de código convencional; inferida(s) "
              + inferred.size()
              + " raiz(es) a partir das declarações de pacote");
    }
    return List.copyOf(inferred.values());
  }

  private static Optional<String> declaredPackageOf(Path root, JavaSource source) {
    if (source.sizeBytes() > AnalysisConstants.MAX_SOURCE_FILE_BYTES) {
      return Optional.empty();
    }
    return BuildSystemDetector.readBounded(
            root.resolve(source.relativePath()), AnalysisConstants.MAX_SOURCE_FILE_BYTES)
        .flatMap(PackageDeclarations::declaredPackage);
  }

  private static String withoutPackage(String directory, String declaredPackage) {
    final List<String> directorySegments =
        directory.isEmpty() ? List.of() : Arrays.asList(directory.split(SEPARATOR, -1));
    final String[] packageSegments = declaredPackage.split("\\.", -1);
    final int offset = directorySegments.size() - packageSegments.length;
    if (offset < 0) {
      return directory;
    }
    for (int index = 0; index < packageSegments.length; index++) {
      if (!nfc(directorySegments.get(offset + index)).equals(nfc(packageSegments[index]))) {
        return directory;
      }
    }
    return String.join(SEPARATOR, directorySegments.subList(0, offset));
  }

  private static SourceRootKind kindByName(String rootPath) {
    final List<String> segments =
        rootPath.isEmpty() ? List.of() : Arrays.asList(rootPath.split(SEPARATOR, -1));
    final boolean test =
        segments.stream()
            .anyMatch(
                segment -> "test".equalsIgnoreCase(segment) || "tests".equalsIgnoreCase(segment));
    if (test) {
      return SourceRootKind.TEST;
    }
    if (segments.stream().anyMatch(segment -> containsIgnoringCase(segment, "generated"))) {
      return SourceRootKind.GENERATED;
    }
    return SourceRootKind.MAIN;
  }

  private static List<SourceRoot> assign(
      RepositoryInventory inventory, List<Candidate> candidates) {
    final Map<String, Candidate> byPath = new LinkedHashMap<>();
    candidates.forEach(candidate -> byPath.putIfAbsent(candidate.relativePath(), candidate));
    final Map<String, Integer> counts = new HashMap<>();
    int outside = 0;
    for (final JavaSource source : inventory.javaSources()) {
      final Optional<String> owner = deepestRoot(source.relativePath(), byPath.keySet());
      if (owner.isPresent()) {
        counts.merge(owner.get(), 1, Integer::sum);
      } else {
        outside++;
      }
    }
    if (outside > 0) {
      Log.info(outside + " arquivo(s) .java ficam fora de toda raiz de código detectada");
    }
    final Map<SourceRootId, String> minted = new HashMap<>();
    final List<SourceRoot> roots = new ArrayList<>();
    for (final Candidate candidate : byPath.values()) {
      final int count = counts.getOrDefault(candidate.relativePath(), 0);
      if (count == 0) {
        continue;
      }
      final SourceRootId id = SourceRootId.derive(candidate.relativePath());
      final String previous = minted.putIfAbsent(id, candidate.relativePath());
      if (previous != null) {
        throw new IllegalStateException(
            "as raízes de código '"
                + previous
                + "' e '"
                + candidate.relativePath()
                + "' derivam o mesmo id "
                + id.value());
      }
      roots.add(new SourceRoot(id, candidate.relativePath(), candidate.kind(), count));
    }
    roots.sort(Comparator.comparing(SourceRoot::relativePath));
    return List.copyOf(roots);
  }

  private static Optional<String> deepestRoot(String filePath, Set<String> roots) {
    String directory = parentOf(filePath);
    while (!roots.contains(directory)) {
      if (directory.isEmpty()) {
        return Optional.empty();
      }
      directory = parentOf(directory);
    }
    return Optional.of(directory);
  }

  private static String parentOf(String relativePath) {
    final int slash = relativePath.lastIndexOf('/');
    return slash < 0 ? REPOSITORY_ROOT : relativePath.substring(0, slash);
  }

  private static String join(String[] segments, int from, int to) {
    return String.join(SEPARATOR, Arrays.asList(segments).subList(from, to));
  }

  private static boolean containsIgnoringCase(String value, String fragment) {
    return value.toLowerCase(Locale.ROOT).contains(fragment);
  }

  private static String nfc(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC);
  }
}
