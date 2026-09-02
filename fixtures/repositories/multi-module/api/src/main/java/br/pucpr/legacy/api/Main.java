package br.pucpr.legacy.api;

import br.pucpr.legacy.core.Catalog;
import br.pucpr.legacy.core.spi.MemoryStore;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        CatalogEndpoint endpoint = new CatalogEndpoint(new Catalog(new MemoryStore()));
        endpoint.post("strata");
    }
}
