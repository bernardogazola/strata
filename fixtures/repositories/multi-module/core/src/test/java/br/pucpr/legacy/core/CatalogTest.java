package br.pucpr.legacy.core;

import br.pucpr.legacy.core.spi.MemoryStore;

/** Fixture: a raiz TEST, que sai da coorte de percentil quando `includeTests` é falso. */
public final class CatalogTest {

    public CatalogTest() {
    }

    public void addsEntry() {
        Catalog catalog = new Catalog(new MemoryStore());
        catalog.add(new CatalogEntry("titulo", Kind.BOOK));
    }
}
