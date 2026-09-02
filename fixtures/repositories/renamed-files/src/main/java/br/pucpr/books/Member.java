package br.pucpr.books;

/**
 * Fixture de linhagem de caminho: muda só com Report.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo, e é o mesmo número que
 * o golden declara em history.lineages[].changes.
 */
public final class Member {

    static final int REVISION = 3;

    Member() {
    }

    int revision() {
        return REVISION;
    }
}
