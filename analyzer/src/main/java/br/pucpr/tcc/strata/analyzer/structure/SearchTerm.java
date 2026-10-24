package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.NameFold;
import java.util.Objects;
import java.util.Optional;

public record SearchTerm(String folded) {

  public SearchTerm {
    Objects.requireNonNull(folded, "folded");
    if (folded.isEmpty()) {
      throw new IllegalArgumentException("um termo de busca vazio casa com todas as linhas");
    }
  }

  public static Optional<SearchTerm> of(String query) {
    if (query == null) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.QUERY_REQUIRED);
    }
    if (query.codePointCount(0, query.length()) > AnalysisConstants.MAX_SEARCH_QUERY_CODE_POINTS) {
      throw new InvalidStructureRequest(InvalidStructureRequest.Reason.QUERY_TOO_LONG);
    }
    if (hasLoneSurrogate(query)) {
      return Optional.empty();
    }
    final String folded = NameFold.fold(query);
    return folded.isEmpty() ? Optional.empty() : Optional.of(new SearchTerm(folded));
  }

  private static boolean hasLoneSurrogate(String query) {
    for (int index = 0; index < query.length(); ) {
      final int codePoint = query.codePointAt(index);
      if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
        return true;
      }
      index += Character.charCount(codePoint);
    }
    return false;
  }
}
