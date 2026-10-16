package br.pucpr.tcc.strata.analyzer.repository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class GradleSettingsReader {

  private static final String INCLUDE = "include";
  private static final String PROJECT_PATH_SEPARATOR = ":";

  private GradleSettingsReader() {}

  static List<String> includedDirectories(String settings) {
    Objects.requireNonNull(settings, "settings");
    final List<Token> tokens = tokenize(settings);
    final Set<String> directories = new LinkedHashSet<>();
    for (int index = 0; index < tokens.size(); index++) {
      if (tokens.get(index).isIdentifier(INCLUDE)) {
        for (final String projectPath : includeArguments(tokens, index + 1)) {
          directoryOf(projectPath).ifPresent(directories::add);
        }
      }
    }
    return List.copyOf(directories);
  }

  static Optional<String> directoryOf(String projectPath) {
    final String relative =
        projectPath.startsWith(PROJECT_PATH_SEPARATOR) ? projectPath.substring(1) : projectPath;
    final String[] segments = relative.split(PROJECT_PATH_SEPARATOR, -1);
    for (final String segment : segments) {
      final boolean unusable =
          segment.isEmpty()
              || ".".equals(segment)
              || "..".equals(segment)
              || segment.indexOf('/') >= 0
              || segment.indexOf('\\') >= 0;
      if (unusable) {
        return Optional.empty();
      }
    }
    return Optional.of(String.join("/", segments));
  }

  private static List<String> includeArguments(List<Token> tokens, int start) {
    if (start < tokens.size() && tokens.get(start).isPunctuation('(')) {
      return parenthesizedArguments(tokens, start + 1);
    }
    final List<String> arguments = new ArrayList<>();
    int index = start;
    while (index < tokens.size() && tokens.get(index).kind() == Kind.LITERAL) {
      arguments.add(tokens.get(index).text());
      final boolean continues =
          index + 1 < tokens.size() && tokens.get(index + 1).isPunctuation(',');
      if (!continues) {
        break;
      }
      index += 2;
    }
    return arguments;
  }

  private static List<String> parenthesizedArguments(List<Token> tokens, int start) {
    final List<String> arguments = new ArrayList<>();
    final List<Token> argument = new ArrayList<>();
    int depth = 0;
    for (int index = start; index < tokens.size(); index++) {
      final Token token = tokens.get(index);
      final boolean closesCall = depth == 0 && token.isPunctuation(')');
      if (closesCall || (depth == 0 && token.isPunctuation(','))) {
        literalArgument(argument).ifPresent(arguments::add);
        argument.clear();
        if (closesCall) {
          break;
        }
        continue;
      }
      if (token.isPunctuation('(') || token.isPunctuation('[') || token.isPunctuation('{')) {
        depth++;
      } else if (token.isPunctuation(')') || token.isPunctuation(']') || token.isPunctuation('}')) {
        depth--;
      }
      argument.add(token);
    }
    return arguments;
  }

  private static Optional<String> literalArgument(List<Token> argument) {
    final boolean single = argument.size() == 1 && argument.getFirst().kind() == Kind.LITERAL;
    return single ? Optional.of(argument.getFirst().text()) : Optional.empty();
  }

  private static List<Token> tokenize(String text) {
    final List<Token> tokens = new ArrayList<>();
    int index = 0;
    while (index < text.length()) {
      final char current = text.charAt(index);
      if (Character.isWhitespace(current)) {
        index++;
      } else if (text.startsWith("//", index)) {
        final int newline = text.indexOf('\n', index);
        index = newline < 0 ? text.length() : newline + 1;
      } else if (text.startsWith("/*", index)) {
        final int close = text.indexOf("*/", index + 2);
        index = close < 0 ? text.length() : close + 2;
      } else if (current == '\'' || current == '"') {
        index = readString(text, index, tokens);
      } else if (Character.isJavaIdentifierStart(current)) {
        final int end = identifierEnd(text, index);
        tokens.add(new Token(Kind.IDENTIFIER, text.substring(index, end)));
        index = end;
      } else {
        tokens.add(new Token(Kind.PUNCTUATION, String.valueOf(current)));
        index++;
      }
    }
    return tokens;
  }

  private static int identifierEnd(String text, int start) {
    int end = start + 1;
    while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
      end++;
    }
    return end;
  }

  private static int readString(String text, int start, List<Token> tokens) {
    final char quote = text.charAt(start);
    final String triple = String.valueOf(quote).repeat(3);
    if (text.startsWith(triple, start)) {
      final int close = text.indexOf(triple, start + triple.length());
      if (close < 0) {
        return text.length();
      }
      tokens.add(literal(quote, text.substring(start + triple.length(), close), true));
      return close + triple.length();
    }
    final StringBuilder value = new StringBuilder();
    int index = start + 1;
    while (index < text.length()) {
      final char current = text.charAt(index);
      if (current == quote) {
        tokens.add(literal(quote, value.toString(), true));
        return index + 1;
      }
      if (current == '\n') {
        break;
      }
      final int width = current == '\\' && index + 1 < text.length() ? 2 : 1;
      value.append(text, index, index + width);
      index += width;
    }
    tokens.add(literal(quote, value.toString(), false));
    return index;
  }

  private static Token literal(char quote, String value, boolean terminated) {
    final boolean template = quote == '"' && value.indexOf('$') >= 0;
    return new Token(terminated && !template ? Kind.LITERAL : Kind.TEMPLATE, value);
  }

  private enum Kind {
    IDENTIFIER,
    LITERAL,
    TEMPLATE,
    PUNCTUATION
  }

  private record Token(Kind kind, String text) {

    boolean isIdentifier(String name) {
      return kind == Kind.IDENTIFIER && text.equals(name);
    }

    boolean isPunctuation(char symbol) {
      return kind == Kind.PUNCTUATION && text.length() == 1 && text.charAt(0) == symbol;
    }
  }
}
