package br.pucpr.tcc.strata.analyzer.sourceanalysis;

final class SyntaxPreParser {

  private SyntaxPreParser() {}

  static PreParse parse(String fileName, char[] content) {
    throw new UnsupportedOperationException("SyntaxPreParser.parse: ainda não implementado");
  }

  record PreParse(
      boolean syntaxError,
      boolean empty,
      boolean moduleDescriptor,
      SourceFile.Declarations declarations) {

    PreParse {
      if (syntaxError != (declarations == null)) {
        throw new IllegalArgumentException(
            "as declarações faltam se e somente se o arquivo tem erro de sintaxe");
      }
    }
  }
}
