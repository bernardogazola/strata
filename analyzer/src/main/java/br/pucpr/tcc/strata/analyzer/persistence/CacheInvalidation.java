package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;

public sealed interface CacheInvalidation {

  CacheInvalidationReason reason();

  record Corrupt() implements CacheInvalidation {
    @Override
    public CacheInvalidationReason reason() {
      return CacheInvalidationReason.CORRUPT;
    }
  }

  record SchemaVersion(int fromVersion, int toVersion) implements CacheInvalidation {

    public SchemaVersion {
      if (fromVersion < 0 || toVersion < 0 || fromVersion == toVersion) {
        throw new IllegalArgumentException(
            "não é uma mudança de versão do schema: " + fromVersion + " -> " + toVersion);
      }
    }

    @Override
    public CacheInvalidationReason reason() {
      return CacheInvalidationReason.SCHEMA_VERSION;
    }
  }

  record Outdated(CacheInvalidationReason reason) implements CacheInvalidation {

    public Outdated {
      Objects.requireNonNull(reason, "reason");
      if (reason == CacheInvalidationReason.CORRUPT
          || reason == CacheInvalidationReason.SCHEMA_VERSION) {
        throw new IllegalArgumentException(
            "um motivo de reconstrução não é desatualização: " + reason);
      }
    }
  }
}
