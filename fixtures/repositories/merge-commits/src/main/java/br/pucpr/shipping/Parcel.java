package br.pucpr.shipping;

/**
 * Fixture de exclusão de merge: muda sempre com Route.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo, sem contar merges.
 */
public final class Parcel {

    static final int REVISION = 4;

    Parcel() {
    }

    int revision() {
        return REVISION;
    }
}
