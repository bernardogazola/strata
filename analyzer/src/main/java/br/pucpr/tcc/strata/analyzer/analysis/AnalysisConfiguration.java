package br.pucpr.tcc.strata.analyzer.analysis;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootId;
import java.util.List;
import java.util.Objects;

public record AnalysisConfiguration(
    boolean includeTests,
    HistoryScope historyScope,
    Integer historyScopeCommits,
    List<String> sourceRootIds,
    boolean rebuildCache) {

  static final String LAST_N_WITHOUT_N = "LAST_N_COMMITS exige historyScopeCommits";

  static final String N_BELOW_ONE = "historyScopeCommits deve ser pelo menos 1";

  static final String N_ABOVE_CEILING = "historyScopeCommits passa do teto de commits percorridos";

  static final String N_WITHOUT_LAST_N = "historyScopeCommits só vale com LAST_N_COMMITS";

  static final String EMPTY_SELECTION = "sourceRootIds não pode ser vazio";

  static final String MALFORMED_SOURCE_ROOT_ID =
      "sourceRootIds contém um id de raiz de código malformado";

  static final String UNSORTED_SELECTION = "sourceRootIds deve ter ids distintos e em ordem";

  public AnalysisConfiguration {
    Objects.requireNonNull(historyScope, "historyScope");
    switch (historyScope) {
      case LAST_N_COMMITS -> {
        if (historyScopeCommits == null) {
          throw new IllegalArgumentException(LAST_N_WITHOUT_N);
        }
        if (historyScopeCommits < 1) {
          throw new IllegalArgumentException(N_BELOW_ONE);
        }
        if (historyScopeCommits > AnalysisConstants.MAX_COMMITS_WALKED) {
          throw new IllegalArgumentException(N_ABOVE_CEILING);
        }
      }
      case ALL -> {
        if (historyScopeCommits != null) {
          throw new IllegalArgumentException(N_WITHOUT_LAST_N);
        }
      }
    }
    if (sourceRootIds != null) {
      sourceRootIds = checkedSelection(sourceRootIds);
    }
  }

  public static AnalysisConfiguration normalized(
      Boolean includeTests,
      HistoryScope historyScope,
      Long historyScopeCommits,
      List<String> sourceRootIds,
      Boolean rebuildCache) {
    throw new UnsupportedOperationException("ainda não implementado");
  }

  private static List<String> checkedSelection(List<String> ids) {
    if (ids.isEmpty()) {
      throw new IllegalArgumentException(EMPTY_SELECTION);
    }
    String previous = null;
    for (final String id : ids) {
      if (!isSourceRootId(id)) {
        throw new IllegalArgumentException(MALFORMED_SOURCE_ROOT_ID);
      }
      if (previous != null && previous.compareTo(id) >= 0) {
        throw new IllegalArgumentException(UNSORTED_SELECTION);
      }
      previous = id;
    }
    return List.copyOf(ids);
  }

  private static boolean isSourceRootId(String id) {
    if (id == null) {
      return false;
    }
    try {
      new SourceRootId(id);
      return true;
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }
}
