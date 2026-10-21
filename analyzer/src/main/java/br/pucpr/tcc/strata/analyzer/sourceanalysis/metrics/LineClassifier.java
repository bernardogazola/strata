package br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics;

import java.util.Arrays;
import java.util.Objects;

final class LineClassifier {

  private static final int CHARS_PER_LINE_ESTIMATE = 32;

  private static final int MIN_CAPACITY = 16;

  private LineClassifier() {}

  static int[] codeLinePrefix(char[] text) {
    Objects.requireNonNull(text, "text");
    return new Scan(text).run();
  }

  private static final class Scan {

    private final char[] text;
    private int at;

    private int lineStart;

    private boolean lineHasCode;
    private int lines;
    private int codeLines;
    private int[] prefix;

    private Scan(char[] text) {
      this.text = text;
      this.prefix = new int[Math.max(MIN_CAPACITY, text.length / CHARS_PER_LINE_ESTIMATE + 2)];
    }

    private int[] run() {
      while (at < text.length) {
        switch (text[at]) {
          case '\n', '\r' -> endLine();
          case ' ', '\t', '\f' -> at++;
          case '/' -> slash();
          case '"' -> quote();
          case '\'' -> string('\'');
          default -> {
            lineHasCode = true;
            at++;
          }
        }
      }
      if (lineStart < text.length) {
        closeLine();
      }
      return prefix.length == lines + 1 ? prefix : Arrays.copyOf(prefix, lines + 1);
    }

    private void slash() {
      final char next = at + 1 < text.length ? text[at + 1] : 0;
      if (next == '/') {
        lineComment();
      } else if (next == '*') {
        blockComment();
      } else {
        lineHasCode = true;
        at++;
      }
    }

    private void quote() {
      if (at + 2 < text.length && text[at + 1] == '"' && text[at + 2] == '"') {
        textBlock();
      } else {
        string('"');
      }
    }

    private void lineComment() {
      at += 2;
      while (at < text.length && !isTerminator(text[at])) {
        at++;
      }
    }

    private void blockComment() {
      at += 2;
      while (at < text.length) {
        final char c = text[at];
        if (c == '*' && at + 1 < text.length && text[at + 1] == '/') {
          at += 2;
          return;
        }
        if (isTerminator(c)) {
          endLine();
        } else {
          at++;
        }
      }
    }

    private void string(char closing) {
      lineHasCode = true;
      at++;
      while (at < text.length) {
        final char c = text[at];
        if (c == closing) {
          at++;
          return;
        }
        if (isTerminator(c)) {
          return;
        }
        at = c == '\\' ? escaped() : at + 1;
      }
    }

    private void textBlock() {
      lineHasCode = true;
      at += 3;
      while (at < text.length) {
        final char c = text[at];
        if (c == '"' && at + 2 < text.length && text[at + 1] == '"' && text[at + 2] == '"') {
          lineHasCode = true;
          at += 3;
          return;
        }
        if (isTerminator(c)) {
          endLine();
        } else {
          lineHasCode |= !isWhitespace(c);
          at = c == '\\' ? escaped() : at + 1;
        }
      }
    }

    private int escaped() {
      final int next = at + 1;
      return next < text.length && !isTerminator(text[next]) ? next + 1 : next;
    }

    private void endLine() {
      final boolean crlf = text[at] == '\r' && at + 1 < text.length && text[at + 1] == '\n';
      at += crlf ? 2 : 1;
      closeLine();
      lineStart = at;
    }

    private void closeLine() {
      if (lineHasCode) {
        codeLines++;
        lineHasCode = false;
      }
      lines++;
      if (lines == prefix.length) {
        prefix = Arrays.copyOf(prefix, prefix.length * 2);
      }
      prefix[lines] = codeLines;
    }

    private static boolean isTerminator(char c) {
      return c == '\n' || c == '\r';
    }

    private static boolean isWhitespace(char c) {
      return c == ' ' || c == '\t' || c == '\f';
    }
  }
}
