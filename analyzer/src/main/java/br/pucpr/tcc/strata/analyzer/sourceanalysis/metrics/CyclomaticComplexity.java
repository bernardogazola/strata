package br.pucpr.tcc.strata.analyzer.sourceanalysis.metrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CaseKind;
import spoon.reflect.code.CtAssert;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtCasePattern;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtDo;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtForEach;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtWhile;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;

public final class CyclomaticComplexity {

  private CyclomaticComplexity() {}

  public static int decisionPoints(CtElement root) {
    Objects.requireNonNull(root, "root");
    int total = 0;
    final Deque<CtElement> pending = new ArrayDeque<>();
    pending.push(root);
    while (!pending.isEmpty()) {
      final CtElement element = pending.pop();
      total += increment(element);
      for (final CtElement child : element.getDirectChildren()) {
        if (!isPruned(child)) {
          pending.push(child);
        }
      }
    }
    return total;
  }

  private static boolean isPruned(CtElement child) {
    return child instanceof CtType<?> || child instanceof CtAnnotation<?>;
  }

  private static int increment(CtElement element) {
    return switch (element) {
      case CtIf _, CtFor _, CtForEach _, CtWhile _, CtDo _, CtCatch _ -> 1;
      case CtConditional<?> _, CtAssert<?> _ -> 1;
      case CtBinaryOperator<?> operator -> isShortCircuit(operator.getKind()) ? 1 : 0;
      case CtCase<?> label -> label.getCaseExpressions().size() + (isGuarded(label) ? 1 : 0);
      default -> 0;
    };
  }

  private static boolean isShortCircuit(BinaryOperatorKind kind) {
    return kind == BinaryOperatorKind.AND || kind == BinaryOperatorKind.OR;
  }

  private static boolean isGuarded(CtCase<?> label) {
    if (label.getGuard() != null) {
      return true;
    }
    final List<CtStatement> statements = label.getStatements();
    return label.getCaseKind() == CaseKind.ARROW
        && statements.size() == 2
        && !statements.getFirst().isImplicit()
        && label.getCaseExpressions().stream().anyMatch(CtCasePattern.class::isInstance);
  }
}
