package br.pucpr.books;

/**
 * Fixture de linhagem de caminho: muda com Catalog e com Ledger.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo, e é o mesmo número que
 * o golden declara em history.lineages[].changes.
 */
public final class Loan {

    static final int REVISION = 5;

    Loan() {
    }

    int revision() {
        return REVISION;
    }
}
