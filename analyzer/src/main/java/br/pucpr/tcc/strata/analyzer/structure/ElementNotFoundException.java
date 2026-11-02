package br.pucpr.tcc.strata.analyzer.structure;

public final class ElementNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private static final String MESSAGE = "o elemento não é um elemento interno desta análise";

  public ElementNotFoundException() {
    super(MESSAGE, null, false, false);
  }
}
