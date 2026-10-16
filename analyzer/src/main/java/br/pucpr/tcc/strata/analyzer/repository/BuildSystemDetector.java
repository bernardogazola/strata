package br.pucpr.tcc.strata.analyzer.repository;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.repository.BuildDescription.DeclaredSourceDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.stream.XMLStreamException;

final class BuildSystemDetector {

  static final Set<String> BUILD_FILE_NAMES =
      Set.of(
          "pom.xml",
          "settings.gradle",
          "settings.gradle.kts",
          "build.gradle",
          "build.gradle.kts",
          "build.xml",
          "BUILD.bazel",
          "WORKSPACE",
          "WORKSPACE.bazel",
          "MODULE.bazel",
          "build.sbt");

  private static final String POM = "pom.xml";
  private static final String POM_FILE_SUFFIX = ".xml";
  private static final List<String> GRADLE_SETTINGS =
      List.of("settings.gradle", "settings.gradle.kts");
  private static final List<String> GRADLE_FILES =
      List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");
  private static final String ANT_BUILD = "build.xml";
  private static final List<String> UNSUPPORTED_MARKERS =
      List.of("BUILD.bazel", "WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel", "build.sbt");
  private static final List<String> BASEDIR_PREFIXES =
      List.of("${project.basedir}/", "${basedir}/");
  private static final String PROPERTY_REFERENCE = "${";
  private static final String REPOSITORY_ROOT = "";

  private final RepositoryLimits limits;

  BuildSystemDetector(RepositoryLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  BuildDescription detect(Path root, RepositoryInventory inventory) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(inventory, "inventory");
    final EntryLog log = new EntryLog(limits.maxWarnings());
    final BuildDescription description = classify(root, inventory, log);
    log.summarize("build-file");
    return description;
  }

  private BuildDescription classify(Path root, RepositoryInventory inventory, EntryLog log) {
    final Map<String, BuildFileEntry> atRoot = new HashMap<>();
    for (final BuildFileEntry entry : inventory.buildFiles()) {
      if (entry.relativePath().indexOf('/') < 0) {
        atRoot.put(entry.relativePath(), entry);
      }
    }
    if (atRoot.containsKey(POM)) {
      return maven(root, inventory, atRoot.get(POM), log);
    }
    if (GRADLE_FILES.stream().anyMatch(atRoot::containsKey)) {
      return gradle(root, atRoot, log);
    }
    if (atRoot.containsKey(ANT_BUILD)) {
      return withoutModules(BuildSystem.ANT);
    }
    if (UNSUPPORTED_MARKERS.stream().anyMatch(atRoot::containsKey)) {
      return withoutModules(BuildSystem.UNKNOWN);
    }
    return withoutModules(BuildSystem.NONE);
  }

  static Optional<byte[]> readBounded(Path file, int maxBytes, EntryLog log) {
    final Optional<byte[]> content = readPrefix(file, Math.addExact(maxBytes, 1), log);
    if (content.isPresent() && content.get().length > maxBytes) {
      log.warn("sem ler " + file + ": passa de " + maxBytes + " bytes");
      return Optional.empty();
    }
    return content;
  }

  static Optional<byte[]> readPrefix(Path file, int prefixBytes, EntryLog log) {
    try {
      if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
          .isRegularFile()) {
        log.warn("sem ler " + file + ": deixou de ser um arquivo comum");
        return Optional.empty();
      }
      try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.of(input.readNBytes(prefixBytes));
      }
    } catch (IOException failure) {
      log.warn("não foi possível ler " + file + ": " + failure);
      return Optional.empty();
    }
  }

  private static BuildDescription withoutModules(BuildSystem buildSystem) {
    return new BuildDescription(buildSystem, List.of(), List.of(REPOSITORY_ROOT), List.of());
  }

  private BuildDescription maven(
      Path root, RepositoryInventory inventory, BuildFileEntry rootPom, EntryLog log) {
    final Optional<PomReader.Pom> parsedRoot = readPom(root, rootPom, log);
    if (parsedRoot.isEmpty()) {
      return withoutModules(BuildSystem.UNKNOWN);
    }
    final Map<String, BuildFileEntry> buildFiles = new HashMap<>();
    inventory.buildFiles().forEach(entry -> buildFiles.put(entry.relativePath(), entry));
    final Set<String> moduleDirectories = new TreeSet<>(Set.of(REPOSITORY_ROOT));
    final Set<DeclaredSourceDirectory> sourceDirectories = new LinkedHashSet<>();
    final Set<String> visited = new HashSet<>(Set.of(POM));
    final Deque<ModulePom> pending = new ArrayDeque<>();
    pending.add(new ModulePom(REPOSITORY_ROOT, POM, parsedRoot.get(), 0));
    while (!pending.isEmpty()) {
      final ModulePom current = pending.removeFirst();
      sourceDirectories.addAll(declaredSourceDirectories(current));
      for (final ModuleReference module : modulesOf(current, log)) {
        moduleDirectories.add(module.directory());
        final boolean descend =
            current.depth() < limits.maxDirectoryDepth() && visited.add(module.pomPath());
        if (descend) {
          readModulePom(root, buildFiles.get(module.pomPath()), module, current.depth() + 1, log)
              .ifPresent(pending::addLast);
        }
      }
    }
    return new BuildDescription(
        BuildSystem.MAVEN,
        declaredModules(parsedRoot.get()),
        List.copyOf(moduleDirectories),
        List.copyOf(sourceDirectories));
  }

  private List<String> declaredModules(PomReader.Pom rootPom) {
    return rootPom.allModules().stream()
        .filter(value -> resolveModule(REPOSITORY_ROOT, value).isPresent())
        .toList();
  }

  private List<ModuleReference> modulesOf(ModulePom modulePom, EntryLog log) {
    final List<ModuleReference> modules = new ArrayList<>();
    for (final String value : modulePom.pom().allModules()) {
      resolveModule(modulePom.directory(), value).ifPresent(modules::add);
    }
    final int rejected = modulePom.pom().allModules().size() - modules.size();
    if (rejected > 0) {
      log.warn(
          "ignorado(s) "
              + rejected
              + " módulo(s) Maven de "
              + modulePom.pomPath()
              + " que são absolutos, escapam da raiz do repositório ou passam do teto de caminho");
    }
    return modules;
  }

  private Optional<ModulePom> readModulePom(
      Path root, BuildFileEntry entry, ModuleReference module, int depth, EntryLog log) {
    if (entry == null) {
      log.info(
          "o módulo Maven "
              + module.directory()
              + " não tem o arquivo listado "
              + module.pomPath());
      return Optional.empty();
    }
    return readPom(root, entry, log)
        .map(pom -> new ModulePom(module.directory(), entry.relativePath(), pom, depth));
  }

  private Optional<PomReader.Pom> readPom(Path root, BuildFileEntry entry, EntryLog log) {
    if (!entry.regularFile() || entry.sizeBytes() > limits.maxBuildFileBytes()) {
      log.warn(
          "sem ler "
              + entry.relativePath()
              + ": não é um arquivo comum, ou passa de "
              + limits.maxBuildFileBytes()
              + " bytes");
      return Optional.empty();
    }
    final Optional<byte[]> content =
        readBounded(root.resolve(entry.relativePath()), limits.maxBuildFileBytes(), log);
    if (content.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(PomReader.read(content.get()));
    } catch (XMLStreamException malformed) {
      log.warn(
          "POM ilegível "
              + entry.relativePath()
              + ": "
              + String.valueOf(malformed.getMessage()).replace('\n', ' '));
      return Optional.empty();
    }
  }

  private List<DeclaredSourceDirectory> declaredSourceDirectories(ModulePom modulePom) {
    final Set<DeclaredSourceDirectory> declared = new LinkedHashSet<>();
    for (final String value : modulePom.pom().sourceDirectories()) {
      resolveSourceDirectory(modulePom.directory(), value)
          .ifPresent(path -> declared.add(new DeclaredSourceDirectory(path, SourceRootKind.MAIN)));
    }
    for (final String value : modulePom.pom().testSourceDirectories()) {
      resolveSourceDirectory(modulePom.directory(), value)
          .ifPresent(path -> declared.add(new DeclaredSourceDirectory(path, SourceRootKind.TEST)));
    }
    return List.copyOf(declared);
  }

  private Optional<String> resolveSourceDirectory(String pomDirectory, String value) {
    String unprefixed = value;
    for (final String prefix : BASEDIR_PREFIXES) {
      if (value.startsWith(prefix)) {
        unprefixed = value.substring(prefix.length());
        break;
      }
    }
    return unprefixed.contains(PROPERTY_REFERENCE)
        ? Optional.empty()
        : resolveInside(pomDirectory, unprefixed);
  }

  private Optional<ModuleReference> resolveModule(String pomDirectory, String value) {
    if (utf8Length(value) > limits.maxPathBytes()) {
      return Optional.empty();
    }
    return resolveInside(pomDirectory, value)
        .map(
            resolved ->
                resolved.endsWith(POM_FILE_SUFFIX)
                    ? new ModuleReference(parentOf(resolved), resolved)
                    : new ModuleReference(resolved, childOf(resolved, POM)));
  }

  private Optional<String> resolveInside(String directory, String value) {
    final String slashed = value.replace('\\', '/');
    if (isAbsolute(slashed)) {
      return Optional.empty();
    }
    final Deque<String> segments = new ArrayDeque<>();
    for (final String segment : (directory + "/" + slashed).split("/")) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        if (segments.isEmpty()) {
          return Optional.empty();
        }
        segments.removeLast();
        continue;
      }
      segments.addLast(segment);
    }
    final String resolved = String.join("/", segments);
    return utf8Length(resolved) <= limits.maxPathBytes() ? Optional.of(resolved) : Optional.empty();
  }

  private static boolean isAbsolute(String slashed) {
    final boolean driveLetter =
        slashed.length() >= 2
            && slashed.charAt(1) == ':'
            && ((slashed.charAt(0) >= 'A' && slashed.charAt(0) <= 'Z')
                || (slashed.charAt(0) >= 'a' && slashed.charAt(0) <= 'z'));
    return slashed.startsWith("/") || driveLetter;
  }

  private BuildDescription gradle(Path root, Map<String, BuildFileEntry> atRoot, EntryLog log) {
    final Set<String> included = new LinkedHashSet<>();
    for (final String name : GRADLE_SETTINGS) {
      final BuildFileEntry entry = atRoot.get(name);
      if (entry == null) {
        continue;
      }
      if (!entry.regularFile() || entry.sizeBytes() > limits.maxBuildFileBytes()) {
        log.warn("sem ler " + name + ": não é um arquivo comum, ou é grande demais");
        continue;
      }
      readBounded(root.resolve(name), limits.maxBuildFileBytes(), log)
          .map(content -> new String(content, StandardCharsets.UTF_8))
          .map(GradleSettingsReader::includedDirectories)
          .ifPresent(included::addAll);
    }
    included.removeIf(directory -> utf8Length(directory) > limits.maxPathBytes());
    final Set<String> moduleDirectories = new TreeSet<>(included);
    moduleDirectories.add(REPOSITORY_ROOT);
    return new BuildDescription(
        BuildSystem.GRADLE, List.copyOf(included), List.copyOf(moduleDirectories), List.of());
  }

  private static String parentOf(String relativePath) {
    final int slash = relativePath.lastIndexOf('/');
    return slash < 0 ? REPOSITORY_ROOT : relativePath.substring(0, slash);
  }

  private static String childOf(String directory, String name) {
    return directory.isEmpty() ? name : directory + "/" + name;
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  static final class EntryLog {

    private final int limit;
    private int logged;
    private int silenced;

    EntryLog(int limit) {
      this.limit = limit;
    }

    void info(String message) {
      if (admit()) {
        Log.info(message);
      }
    }

    void warn(String message) {
      if (admit()) {
        Log.warn(message);
      }
    }

    void summarize(String stage) {
      if (silenced > 0) {
        Log.warn(
            "outras " + silenced + " linhas de log por entrada de " + stage + " foram silenciadas");
      }
    }

    private boolean admit() {
      if (logged < limit) {
        logged++;
        return true;
      }
      silenced++;
      return false;
    }
  }

  private record ModulePom(String directory, String pomPath, PomReader.Pom pom, int depth) {}

  private record ModuleReference(String directory, String pomPath) {}
}
