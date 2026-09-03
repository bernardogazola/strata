package br.pucpr.payments;

/**
 * Fixture: interface que estende duas interfaces. São duas arestas EXTENDS, e não
 * IMPLEMENTS, porque a palavra-chave usada é `extends`.
 */
public interface Auditable extends Named, Traceable {

    void audit();
}
