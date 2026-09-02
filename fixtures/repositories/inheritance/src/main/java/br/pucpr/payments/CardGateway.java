package br.pucpr.payments;

/**
 * Fixture: uma classe com N supertipos produz N arestas: uma EXTENDS para a superclasse
 * e uma IMPLEMENTS por interface. Nada de `@Override` nesta fixture: a anotação criaria
 * uma aresta ANNOTATION_REFERENCE para `java.lang.Override` e o golden lista as
 * `dependencies` de forma exaustiva.
 */
public final class CardGateway extends BaseGateway implements Auditable, Instrument {

    public CardGateway() {
    }

    public void audit() {
    }

    public void charge() {
    }

    public void describe() {
    }

    public void trace() {
    }
}
