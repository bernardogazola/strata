package br.pucpr.ledger;

/**
 * Fixture: história longa. Um dos oito arquivos do rodízio determinístico, em que
 * o commit i toca Entry(i mod 8) e Entry((i+3) mod 8).
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo.
 */
public final class Entry5 {

    static final int REVISION = 31;

    Entry5() {
    }

    int revision() {
        return REVISION;
    }
}
