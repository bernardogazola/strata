package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

public record CodeElementId(String value) implements Comparable<CodeElementId> {

  public CodeElementId {
    Objects.requireNonNull(value, "value");
    final ElementKind kind = kindOf(value);
    if (kind == null) {
      throw new IllegalArgumentException("não é um CodeElementId: falta o prefixo de tipo");
    }
    final String body = value.substring(kind.prefix().length());
    if (kind != ElementKind.PACKAGE && body.isEmpty()) {
      throw new IllegalArgumentException("um id " + kind + " vazio");
    }
    if (kind == ElementKind.METHOD && !isMethodBody(body)) {
      throw new IllegalArgumentException("um id de método pede Owner#name(...)");
    }
    if (kind == ElementKind.FILE && (body.startsWith("/") || body.indexOf('\\') >= 0)) {
      throw new IllegalArgumentException("um id de arquivo é relativo à raiz e POSIX");
    }
  }

  public static CodeElementId ofPackage(String qualifiedName) {
    return new CodeElementId(ElementKind.PACKAGE.prefix() + qualifiedName);
  }

  public static CodeElementId ofType(String qualifiedName) {
    return new CodeElementId(ElementKind.TYPE.prefix() + qualifiedName);
  }

  public static CodeElementId ofMethod(
      String ownerQualifiedName, String name, List<String> erasedParameters) {
    return new CodeElementId(
        ElementKind.METHOD.prefix()
            + ownerQualifiedName
            + "#"
            + name
            + "("
            + String.join(",", erasedParameters)
            + ")");
  }

  public static CodeElementId ofFile(String relativePath) {
    return new CodeElementId(
        ElementKind.FILE.prefix() + Normalizer.normalize(relativePath, Normalizer.Form.NFC));
  }

  public ElementKind kind() {
    return kindOf(value);
  }

  public String sortKey() {
    return value.substring(kind().prefix().length());
  }

  public int utf8Length() {
    int bytes = 0;
    for (int i = 0; i < value.length(); ) {
      final int codePoint = value.codePointAt(i);
      bytes += utf8Width(codePoint);
      i += Character.charCount(codePoint);
    }
    return bytes;
  }

  public boolean fitsCeiling() {
    return utf8Length() <= AnalysisConstants.MAX_ELEMENT_ID_BYTES;
  }

  public static int compareCodePoints(String left, String right) {
    int i = 0;
    int j = 0;
    while (i < left.length() && j < right.length()) {
      final int a = left.codePointAt(i);
      final int b = right.codePointAt(j);
      if (a != b) {
        return Integer.compare(a, b);
      }
      i += Character.charCount(a);
      j += Character.charCount(b);
    }
    return Integer.compare(left.length() - i, right.length() - j);
  }

  @Override
  public int compareTo(CodeElementId other) {
    return compareCodePoints(value, other.value);
  }

  @Override
  public String toString() {
    return value;
  }

  private static ElementKind kindOf(String value) {
    for (final ElementKind kind : ElementKind.values()) {
      if (value.startsWith(kind.prefix())) {
        return kind;
      }
    }
    return null;
  }

  private static boolean isMethodBody(String body) {
    final int hash = body.indexOf('#');
    final int open = body.indexOf('(', Math.max(hash, 0));
    return hash > 0 && open > hash + 1 && body.endsWith(")");
  }

  private static int utf8Width(int codePoint) {
    if (codePoint < 0x80) {
      return 1;
    }
    if (codePoint < 0x800) {
      return 2;
    }
    return codePoint < 0x10000 ? 3 : 4;
  }
}
