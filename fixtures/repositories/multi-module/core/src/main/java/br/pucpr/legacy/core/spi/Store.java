package br.pucpr.legacy.core.spi;

import br.pucpr.legacy.core.CatalogEntry;

/** Fixture: `TypeKind.INTERFACE`, e a volta do ciclo de pacote com `core`. */
public interface Store {

    void save(CatalogEntry entry);
}
