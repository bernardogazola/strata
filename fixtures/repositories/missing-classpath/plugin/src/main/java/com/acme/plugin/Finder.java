package com.acme.plugin;

import com.acme.core.*;

/**
 * Fixture: resolução por import sob demanda. Ele mapeia `Registry` para exatamente um tipo
 * interno; como não nomeia tipo nenhum, o import em si não produz aresta IMPORTS.
 */
public final class Finder {

    private Registry registry;

    public Finder() {
    }
}
