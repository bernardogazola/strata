package br.pucpr.shipping;

/**
 * Fixture de exclusão de merge: nasce no ramo; entra na principal pelo merge.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo, sem contar merges.
 */
public final class Waybill {

    static final int REVISION = 2;

    Waybill() {
    }

    int revision() {
        return REVISION;
    }
}
