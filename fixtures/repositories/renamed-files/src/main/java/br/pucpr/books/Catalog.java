package br.pucpr.books;

/**
 * Fixture de linhagem de caminho: o eixo do agrupamento.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo, e é o mesmo número que
 * o golden declara em history.lineages[].changes.
 */
public final class Catalog {

    static final int REVISION = 8;

    Catalog() {
    }

    int revision() {
        return REVISION;
    }
}
