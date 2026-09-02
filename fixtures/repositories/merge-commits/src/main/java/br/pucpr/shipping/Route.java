package br.pucpr.shipping;

/**
 * Fixture de exclusão de merge: muda sempre com Parcel.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo, sem contar merges.
 */
public final class Route {

    static final int REVISION = 3;

    Route() {
    }

    int revision() {
        return REVISION;
    }
}
