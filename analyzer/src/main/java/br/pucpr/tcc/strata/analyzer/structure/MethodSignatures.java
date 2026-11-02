package br.pucpr.tcc.strata.analyzer.structure;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.Objects;

public final class MethodSignatures {

  private static final String CONSTRUCTOR = "<init>";

  private static final String ID_SEPARATOR = ",";

  private static final String DISPLAY_SEPARATOR = ", ";

  private MethodSignatures() {}

  public static String display(CodeElementId method, String ownerSimpleName) {
    Objects.requireNonNull(ownerSimpleName, "ownerSimpleName");
    if (Objects.requireNonNull(method, "method").kind() != ElementKind.METHOD) {
      throw new IllegalArgumentException("só um id de método tem assinatura: " + method);
    }
    final String body = method.sortKey();
    final int hash = body.indexOf('#');
    final int open = body.indexOf('(', hash);
    final String name = body.substring(hash + 1, open);
    final String parameters = body.substring(open + 1, body.length() - 1);
    final StringBuilder display = new StringBuilder();
    display.append(CONSTRUCTOR.equals(name) ? ownerSimpleName : name).append('(');
    if (!parameters.isEmpty()) {
      final String[] erased = parameters.split(ID_SEPARATOR, -1);
      for (int index = 0; index < erased.length; index++) {
        if (index > 0) {
          display.append(DISPLAY_SEPARATOR);
        }
        display.append(simpleName(erased[index]));
      }
    }
    return NodeLabels.clamp(display.append(')').toString());
  }

  private static String simpleName(String erasedParameter) {
    return erasedParameter.substring(erasedParameter.lastIndexOf('.') + 1);
  }
}
