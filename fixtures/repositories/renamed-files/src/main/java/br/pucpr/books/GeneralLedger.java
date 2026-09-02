package br.pucpr.books;

/**
 * Fixture de linhagem de caminho: renomeada duas vezes; uma linhagem só.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo, e é o mesmo número que
 * o golden declara em history.lineages[].changes.
 */
public final class GeneralLedger {

    static final int REVISION = 5;

    GeneralLedger() {
    }

    int revision() {
        return REVISION;
    }
}
