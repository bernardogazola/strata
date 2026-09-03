package com.acme.plugin;

import com.acme.core.Widget;

/**
 * Fixture: resolução por import de tipo único, que mapeia o nome simples para um tipo interno
 * declarado em outra raiz de fonte. O modelo do Spoon é por raiz, então a declaração não
 * está nele e a referência é INFERRED, não RESOLVED.
 */
public final class Loader {

    private Widget widget;

    public Loader() {
    }
}
