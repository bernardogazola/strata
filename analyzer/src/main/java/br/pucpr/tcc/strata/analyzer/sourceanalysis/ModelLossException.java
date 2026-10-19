package br.pucpr.tcc.strata.analyzer.sourceanalysis;

final class ModelLossException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  ModelLossException(String qualifiedName) {
    super("o Spoon devolveu um marcador vazio e sem posição para " + qualifiedName);
  }
}
