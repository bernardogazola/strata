package br.pucpr.legacy.api;

import br.pucpr.legacy.core.Catalog;
import br.pucpr.legacy.core.CatalogEntry;
import br.pucpr.legacy.core.Kind;

public final class CatalogEndpoint {

    private final Catalog catalog;

    public CatalogEndpoint(Catalog catalog) {
        this.catalog = catalog;
    }

    public void post(String title) {
        catalog.add(new CatalogEntry(title, Kind.MOVIE));
    }
}
