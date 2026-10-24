package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.util.Objects;

public final class InvalidStructureRequest extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Reason reason;

  public InvalidStructureRequest(Reason reason) {
    super(Objects.requireNonNull(reason, "reason").detail(), null, false, false);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public String detail() {
    return reason.detail();
  }

  public enum Reason {
    LEVEL_REQUIRED("level é obrigatório"),
    LEVEL_NOT_SERVED("o nível TYPE_NEIGHBORHOOD não é atendido"),
    PARENT_NOT_ALLOWED("parentId deve estar ausente ou ser null no nível PACKAGE"),
    PARENT_NOT_A_PACKAGE("parentId deve ser um id de pacote no nível TYPE"),
    OUTLINE_PARENT_KIND("parentId deve ser null, um id de pacote ou um id de tipo"),
    LIMIT_NOT_POSITIVE("limit deve ser pelo menos 1"),
    CURSOR_MALFORMED("o cursor está malformado"),
    CURSOR_UNKNOWN("o cursor não aponta para uma linha desta listagem"),
    QUERY_REQUIRED("query é obrigatório"),
    QUERY_TOO_LONG(
        "query passa de " + AnalysisConstants.MAX_SEARCH_QUERY_CODE_POINTS + " code points"),
    KIND_REQUIRED("kinds não pode conter null"),
    ELEMENT_KIND("elementId deve ser um id de pacote ou de tipo"),
    SOURCE_KIND("source deve ser um id de tipo"),
    TARGET_KIND("target deve ser um id de tipo"),
    SAME_ENDS("source e target devem ser diferentes"),
    PAIR_WITHOUT_DEPENDENCY("o par não tem dependência");

    private final String detail;

    Reason(String detail) {
      this.detail = detail;
    }

    public String detail() {
      return detail;
    }
  }
}
