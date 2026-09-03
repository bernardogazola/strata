package br.pucpr.pedidos;

/**
 * Fixture: a âncora ASCII, para que o resto da fixture tenha contra o que ser estranho.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo.
 */
public final class Pedido {

    static final int REVISION = 4;

    Pedido() {
    }

    int revision() {
        return REVISION;
    }
}
