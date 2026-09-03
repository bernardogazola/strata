package com.x.billing;

/**
 * Fixture: ciclo de três tipos dentro de um só pacote. No nível de pacote ele não produz
 * aresta nenhuma, porque `P -> P` é excluído; o acoplamento aparece como
 * `internalCohesion`.
 */
public final class Invoice {

    private Ledger ledger;

    public Invoice() {
    }

    public void post(Ledger value) {
        this.ledger = value;
    }
}
