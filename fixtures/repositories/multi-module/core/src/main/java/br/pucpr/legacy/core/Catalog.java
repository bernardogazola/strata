package br.pucpr.legacy.core;

import br.pucpr.legacy.core.spi.Store;

@Audited
public final class Catalog {

    private final Store store;

    public Catalog(Store store) {
        this.store = store;
    }

    public void add(CatalogEntry entry) {
        store.save(entry);
    }
}
