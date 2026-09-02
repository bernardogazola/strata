package br.pucpr.shipping;

/**
 * Fixture de exclusão de merge: nasce no ramo; entra na principal pelo merge.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo, sem contar merges.
 */
public final class Tracker {

    static final int REVISION = 3;

    Tracker() {
    }

    int revision() {
        return REVISION;
    }
}
