package br.pucpr.tcc.strata.analyzer.repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class PackageDeclarations {

  private static final String BYTE_ORDER_MARK = "﻿";
  private static final String PACKAGE = "package";
  private static final String ANNOTATION_TYPE = "interface";
  private static final String TEXT_BLOCK = "\"\"\"";

  private PackageDeclarations() {}

  static Optional<String> declaredPackage(byte[] content) {
    return declaredPackage(new String(content, StandardCharsets.UTF_8));
  }

  static Optional<String> declaredPackage(String source) {
    Objects.requireNonNull(source, "source");
    int index = source.startsWith(BYTE_ORDER_MARK) ? BYTE_ORDER_MARK.length() : 0;
    while (index >= 0) {
      index = skipTrivia(source, index);
      if (startsWithWord(source, index, PACKAGE)) {
        return qualifiedNameThenSemicolon(source, index + PACKAGE.length());
      }
      if (index >= source.length() || source.charAt(index) != '@') {
        return Optional.empty();
      }
      final int afterAt = skipTrivia(source, index + 1);
      index =
          startsWithWord(source, afterAt, ANNOTATION_TYPE) ? -1 : skipAnnotation(source, afterAt);
    }
    return Optional.empty();
  }

  private static Optional<String> qualifiedNameThenSemicolon(String source, int start) {
    final List<String> names = new ArrayList<>();
    int index = skipTrivia(source, start);
    while (true) {
      final int end = identifierEnd(source, index);
      if (end == index) {
        return Optional.empty();
      }
      names.add(source.substring(index, end));
      index = skipTrivia(source, end);
      if (index >= source.length() || source.charAt(index) != '.') {
        break;
      }
      index = skipTrivia(source, index + 1);
    }
    final boolean terminated = index < source.length() && source.charAt(index) == ';';
    return terminated ? Optional.of(String.join(".", names)) : Optional.empty();
  }

  private static int skipAnnotation(String source, int start) {
    int index = start;
    while (true) {
      final int end = identifierEnd(source, index);
      if (end == index) {
        return -1;
      }
      index = skipTrivia(source, end);
      if (index >= source.length() || source.charAt(index) != '.') {
        break;
      }
      index = skipTrivia(source, index + 1);
    }
    return index < source.length() && source.charAt(index) == '('
        ? skipArguments(source, index)
        : index;
  }

  private static int skipArguments(String source, int open) {
    int depth = 0;
    int index = open;
    while (index < source.length()) {
      final char current = source.charAt(index);
      if (current == '"' || current == '\'') {
        index = skipLiteral(source, index);
      } else if (source.startsWith("//", index) || source.startsWith("/*", index)) {
        index = skipTrivia(source, index);
      } else {
        depth += current == '(' ? 1 : 0;
        depth -= current == ')' ? 1 : 0;
        index++;
        if (depth == 0) {
          return index;
        }
      }
    }
    return -1;
  }

  private static int skipLiteral(String source, int start) {
    final boolean textBlock = source.startsWith(TEXT_BLOCK, start);
    final String close = textBlock ? TEXT_BLOCK : String.valueOf(source.charAt(start));
    int index = start + close.length();
    while (index < source.length()) {
      if (source.charAt(index) == '\\') {
        index += 2;
      } else if (source.startsWith(close, index)) {
        return index + close.length();
      } else {
        index++;
      }
    }
    return source.length();
  }

  private static int skipTrivia(String source, int start) {
    int index = start;
    while (index < source.length()) {
      if (Character.isWhitespace(source.charAt(index))) {
        index++;
      } else if (source.startsWith("//", index)) {
        final int newline = source.indexOf('\n', index);
        index = newline < 0 ? source.length() : newline + 1;
      } else if (source.startsWith("/*", index)) {
        final int close = source.indexOf("*/", index + 2);
        index = close < 0 ? source.length() : close + 2;
      } else {
        break;
      }
    }
    return index;
  }

  private static int identifierEnd(String source, int start) {
    if (start >= source.length() || !Character.isJavaIdentifierStart(source.codePointAt(start))) {
      return start;
    }
    int index = start + Character.charCount(source.codePointAt(start));
    while (index < source.length() && Character.isJavaIdentifierPart(source.codePointAt(index))) {
      index += Character.charCount(source.codePointAt(index));
    }
    return index;
  }

  private static boolean startsWithWord(String source, int index, String word) {
    return source.startsWith(word, index) && identifierEnd(source, index) == index + word.length();
  }
}
