package br.pucpr.tcc.strata.analyzer.persistence;

import java.util.Objects;

public sealed interface CacheOpening {

  RepositoryCache cache();

  record Reused(RepositoryCache cache) implements CacheOpening {
    public Reused {
      Objects.requireNonNull(cache, "cache");
    }
  }

  record Created(RepositoryCache cache) implements CacheOpening {
    public Created {
      Objects.requireNonNull(cache, "cache");
    }
  }

  record Rebuilt(RepositoryCache cache, CacheInvalidation invalidation) implements CacheOpening {
    public Rebuilt {
      Objects.requireNonNull(cache, "cache");
      Objects.requireNonNull(invalidation, "invalidation");
      if (invalidation instanceof CacheInvalidation.Outdated) {
        throw new IllegalArgumentException(
            "um cache desatualizado não é reconstruído: " + invalidation);
      }
    }
  }
}
