package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.util.Arrays;
import java.util.Objects;

final class NestingScreen {

  private NestingScreen() {}

  static Nesting measure(char[] content) {
    Objects.requireNonNull(content, "content");
    return new Scan(unicodeEscapesTranslated(content)).run();
  }

  private static char[] unicodeEscapesTranslated(char[] content) {
    if (!containsEscapeStart(content)) {
      return content;
    }
    final char[] translated = new char[content.length];
    int written = 0;
    int backslashes = 0;
    int at = 0;
    while (at < content.length) {
      final int end = backslashes % 2 == 0 ? unicodeEscapeEnd(content, at) : -1;
      if (end > 0) {
        translated[written++] = (char) Integer.parseInt(new String(content, end - 4, 4), 16);
        backslashes = 0;
        at = end;
      } else {
        backslashes = content[at] == '\\' ? backslashes + 1 : 0;
        translated[written++] = content[at++];
      }
    }
    return Arrays.copyOf(translated, written);
  }

  private static boolean containsEscapeStart(char[] content) {
    for (int at = 0; at + 1 < content.length; at++) {
      if (content[at] == '\\' && content[at + 1] == 'u') {
        return true;
      }
    }
    return false;
  }

  private static int unicodeEscapeEnd(char[] content, int at) {
    if (content[at] != '\\' || at + 1 >= content.length || content[at + 1] != 'u') {
      return -1;
    }
    int hex = at + 1;
    while (hex < content.length && content[hex] == 'u') {
      hex++;
    }
    if (hex + 4 > content.length) {
      return -1;
    }
    for (int digit = hex; digit < hex + 4; digit++) {
      if (Character.digit(content[digit], 16) < 0) {
        return -1;
      }
    }
    return hex + 4;
  }

  record Nesting(int bracketDepth, int arrowChain) {

    boolean tooDeep() {
      return bracketDepth > AnalysisConstants.MAX_SOURCE_NESTING_DEPTH
          || arrowChain > AnalysisConstants.MAX_SOURCE_NESTING_DEPTH;
    }
  }

  private static final class Scan {

    private final char[] text;
    private int at;
    private int depth;
    private int maxDepth;
    private int chain;
    private int maxChain;

    private int[] outerChains = new int[64];

    private Scan(char[] text) {
      this.text = text;
    }

    private Nesting run() {
      while (at < text.length) {
        switch (text[at]) {
          case '/' -> slash();
          case '"' -> quote();
          case '\'' -> skipLiteral('\'');
          case '{', '(', '[' -> open();
          case '}', ')', ']' -> close();
          case ',', ';' -> {
            chain = 0;
            at++;
          }
          case '-' -> minus();
          default -> at++;
        }
      }
      return new Nesting(maxDepth, maxChain);
    }

    private void open() {
      if (depth == outerChains.length) {
        outerChains = Arrays.copyOf(outerChains, depth * 2);
      }
      outerChains[depth++] = chain;
      maxDepth = Math.max(maxDepth, depth);
      chain = 0;
      at++;
    }

    private void close() {
      if (depth > 0) {
        depth--;
        chain = text[at] == '}' ? 0 : outerChains[depth];
      }
      at++;
    }

    private void minus() {
      final char next = at + 1 < text.length ? text[at + 1] : 0;
      if (next == '-') {
        at += 2;
      } else if (next == '>') {
        chain++;
        maxChain = Math.max(maxChain, chain);
        at += 2;
      } else {
        at++;
      }
    }

    private void slash() {
      final char next = at + 1 < text.length ? text[at + 1] : 0;
      if (next == '/') {
        while (at < text.length && text[at] != '\n' && text[at] != '\r') {
          at++;
        }
      } else if (next == '*') {
        at += 2;
        while (at < text.length
            && !(text[at] == '*' && at + 1 < text.length && text[at + 1] == '/')) {
          at++;
        }
        at += 2;
      } else {
        at++;
      }
    }

    private void quote() {
      if (startsTextBlockDelimiter(at)) {
        skipTextBlock();
      } else {
        skipLiteral('"');
      }
    }

    private void skipLiteral(char quote) {
      at++;
      while (at < text.length) {
        final char current = text[at];
        if (current == '\\') {
          at += 2;
        } else if (current == quote) {
          at++;
          return;
        } else if (current == '\n' || current == '\r') {
          return;
        } else {
          at++;
        }
      }
    }

    private void skipTextBlock() {
      at += 3;
      while (at < text.length) {
        if (text[at] == '\\') {
          at += 2;
        } else if (startsTextBlockDelimiter(at)) {
          at += 3;
          return;
        } else {
          at++;
        }
      }
    }

    private boolean startsTextBlockDelimiter(int index) {
      return index + 2 < text.length
          && text[index] == '"'
          && text[index + 1] == '"'
          && text[index + 2] == '"';
    }
  }
}
