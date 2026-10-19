package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class ExternalPackages {

  private static final String JDK_RESOURCE = "/jdk-packages.txt";
  private static final String DENYLIST_RESOURCE = "/external-denylist.txt";
  private static final char COMMENT = '#';

  private final Set<String> jdkPackages;
  private final Set<String> denylisted;

  private ExternalPackages(Set<String> jdkPackages, Set<String> denylisted) {
    this.jdkPackages = Set.copyOf(jdkPackages);
    this.denylisted = Set.copyOf(denylisted);
  }

  public static ExternalPackages load() {
    final Set<String> jdk = read(JDK_RESOURCE);
    final Set<String> denylist = read(DENYLIST_RESOURCE);
    for (final String excluded : denylist) {
      if (!jdk.contains(excluded)) {
        throw new IllegalStateException(
            DENYLIST_RESOURCE + " nomeia um pacote fora de " + JDK_RESOURCE + ": " + excluded);
      }
    }
    return new ExternalPackages(jdk, denylist);
  }

  public boolean isJdkPackage(String packageName) {
    return jdkPackages.contains(packageName);
  }

  public boolean isJdk(String idName) {
    final String packageName = packageOf(idName);
    return !packageName.isEmpty() && jdkPackages.contains(packageName);
  }

  public boolean isDenylisted(CodeElementId target) {
    Objects.requireNonNull(target, "target");
    return target.kind() == ElementKind.TYPE && denylisted.contains(packageOf(target.sortKey()));
  }

  public Set<String> jdkPackages() {
    return new TreeSet<>(jdkPackages);
  }

  public Set<String> denylistedPackages() {
    return new TreeSet<>(denylisted);
  }

  static String packageOf(String idName) {
    final int dollar = idName.indexOf('$');
    final String top = dollar < 0 ? idName : idName.substring(0, dollar);
    final int lastDot = top.lastIndexOf('.');
    return lastDot < 0 ? "" : top.substring(0, lastDot);
  }

  private static Set<String> read(String resource) {
    try (InputStream in = ExternalPackages.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("recurso do analisador ausente: " + resource);
      }
      final Set<String> packages = new TreeSet<>();
      final BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        final String name = line.strip();
        if (name.isEmpty() || name.charAt(0) == COMMENT) {
          continue;
        }
        if (!TargetNames.isQualifiedIdentifier(name)) {
          throw new IllegalStateException(resource + " tem uma linha que não é um pacote: " + name);
        }
        packages.add(name);
      }
      return packages;
    } catch (IOException failure) {
      throw new UncheckedIOException(
          "não foi possível ler o recurso do analisador " + resource, failure);
    }
  }
}
