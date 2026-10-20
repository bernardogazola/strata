package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.List;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtCasePattern;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtContinue;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtForEach;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtPattern;
import spoon.reflect.code.CtRecordPattern;
import spoon.reflect.code.CtResource;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtStatementList;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtTryWithResource;
import spoon.reflect.code.CtTypePattern;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtWhile;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.declaration.CtVariable;

final class LexicalScope {

  private LexicalScope() {}

  static Found local(CtElement from, String name) {
    CtElement child = from;
    while (child.isParentInitialized()) {
      final CtElement parent = child.getParent();
      if (parent == null || parent instanceof CtPackage) {
        break;
      }
      if (parent instanceof CtType<?> type && !(parent instanceof CtTypeParameter)) {
        return new Found(null, type);
      }
      final CtVariable<?> declared = declaredIn(parent, child, name);
      if (declared != null) {
        return new Found(declared, null);
      }
      child = parent;
    }
    return Found.NOTHING;
  }

  private static CtVariable<?> declaredIn(CtElement parent, CtElement child, String name) {
    return switch (parent) {
      case CtCase<?> branch -> first(earlier(branch, child, name), caseLabels(branch, name));
      case CtStatementList list -> earlier(list, child, name);
      case CtLocalVariable<?> local -> named(local, name);
      case CtExecutable<?> executable -> first(executable.getParameters(), name);
      case CtCatch caught -> child == caught.getBody() ? named(caught.getParameter(), name) : null;
      case CtForEach loop -> child == loop.getBody() ? named(loop.getVariable(), name) : null;
      case CtFor loop -> forLoop(loop, child, name);
      case CtTryWithResource attempt ->
          child == attempt.getBody() ? resource(attempt.getResources(), name) : null;
      case CtIf branch -> ifBranch(branch, child, name);
      case CtConditional<?> conditional -> conditionalBranch(conditional, child, name);
      case CtWhile loop ->
          child == loop.getBody() ? introduced(loop.getLoopingExpression(), true, name) : null;
      case CtBinaryOperator<?> operator -> rightOperand(operator, child, name);
      default -> null;
    };
  }

  private static CtVariable<?> earlier(CtStatementList list, CtElement child, String name) {
    final List<CtStatement> statements = list.getStatements();
    for (int i = indexOf(statements, child) - 1; i >= 0; i--) {
      final CtVariable<?> found =
          switch (statements.get(i)) {
            case CtLocalVariable<?> local -> named(local, name);
            case CtIf branch -> afterIf(branch, name);
            default -> null;
          };
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static CtVariable<?> afterIf(CtIf branch, String name) {
    final boolean thenEnds = endsAbruptly(branch.getThenStatement());
    final CtStatement otherwise = branch.getElseStatement();
    if (otherwise == null) {
      return thenEnds ? introduced(branch.getCondition(), false, name) : null;
    }
    final boolean elseEnds = endsAbruptly(otherwise);
    if (thenEnds == elseEnds) {
      return null;
    }
    return introduced(branch.getCondition(), elseEnds, name);
  }

  private static boolean endsAbruptly(CtStatement statement) {
    final CtStatement last =
        statement instanceof CtBlock<?> block && !block.getStatements().isEmpty()
            ? block.getStatements().getLast()
            : statement;
    return last instanceof CtReturn<?>
        || last instanceof CtThrow
        || last instanceof CtBreak
        || last instanceof CtContinue;
  }

  private static CtVariable<?> caseLabels(CtCase<?> branch, String name) {
    for (final CtExpression<?> label : branch.getCaseExpressions()) {
      if (label instanceof CtCasePattern pattern) {
        final CtVariable<?> found = inPattern(pattern.getPattern(), name);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static CtVariable<?> forLoop(CtFor loop, CtElement child, String name) {
    for (final CtStatement init : loop.getForInit()) {
      if (init == child) {
        return null;
      }
      if (init instanceof CtLocalVariable<?> local && named(local, name) != null) {
        return local;
      }
    }
    final boolean afterCondition =
        child == loop.getBody() || indexOf(loop.getForUpdate(), child) >= 0;
    return afterCondition ? introduced(loop.getExpression(), true, name) : null;
  }

  private static int indexOf(List<? extends CtElement> elements, CtElement element) {
    for (int i = 0; i < elements.size(); i++) {
      if (elements.get(i) == element) {
        return i;
      }
    }
    return -1;
  }

  private static CtVariable<?> resource(List<CtResource<?>> resources, String name) {
    for (final CtResource<?> resource : resources) {
      if (resource instanceof CtLocalVariable<?> local && named(local, name) != null) {
        return local;
      }
    }
    return null;
  }

  private static CtVariable<?> ifBranch(CtIf branch, CtElement child, String name) {
    if (child == branch.getThenStatement()) {
      return introduced(branch.getCondition(), true, name);
    }
    return child == branch.getElseStatement()
        ? introduced(branch.getCondition(), false, name)
        : null;
  }

  private static CtVariable<?> conditionalBranch(
      CtConditional<?> conditional, CtElement child, String name) {
    if (child == conditional.getThenExpression()) {
      return introduced(conditional.getCondition(), true, name);
    }
    return child == conditional.getElseExpression()
        ? introduced(conditional.getCondition(), false, name)
        : null;
  }

  private static CtVariable<?> rightOperand(
      CtBinaryOperator<?> operator, CtElement child, String name) {
    if (child != operator.getRightHandOperand()) {
      return null;
    }
    return switch (operator.getKind()) {
      case AND -> introduced(operator.getLeftHandOperand(), true, name);
      case OR -> introduced(operator.getLeftHandOperand(), false, name);
      default -> null;
    };
  }

  private static CtVariable<?> introduced(
      CtExpression<?> condition, boolean whenTrue, String name) {
    return switch (condition) {
      case CtBinaryOperator<?> operator when operator.getKind() == BinaryOperatorKind.INSTANCEOF ->
          whenTrue ? inPattern(operator.getRightHandOperand(), name) : null;
      case CtBinaryOperator<?> operator when operator.getKind() == BinaryOperatorKind.AND ->
          whenTrue ? both(operator, true, name) : null;
      case CtBinaryOperator<?> operator when operator.getKind() == BinaryOperatorKind.OR ->
          whenTrue ? null : both(operator, false, name);
      case CtUnaryOperator<?> operator when operator.getKind() == UnaryOperatorKind.NOT ->
          introduced(operator.getOperand(), !whenTrue, name);
      case null, default -> null;
    };
  }

  private static CtVariable<?> both(CtBinaryOperator<?> operator, boolean whenTrue, String name) {
    return first(
        introduced(operator.getLeftHandOperand(), whenTrue, name),
        introduced(operator.getRightHandOperand(), whenTrue, name));
  }

  private static CtVariable<?> inPattern(CtElement pattern, String name) {
    return switch (pattern) {
      case CtTypePattern type -> named(type.getVariable(), name);
      case CtRecordPattern record -> {
        for (final CtPattern component : record.getPatternList()) {
          final CtVariable<?> found = inPattern(component, name);
          if (found != null) {
            yield found;
          }
        }
        yield null;
      }
      case null, default -> null;
    };
  }

  private static CtVariable<?> first(List<? extends CtVariable<?>> variables, String name) {
    for (final CtVariable<?> variable : variables) {
      if (named(variable, name) != null) {
        return variable;
      }
    }
    return null;
  }

  private static CtVariable<?> first(CtVariable<?> found, CtVariable<?> otherwise) {
    return found != null ? found : otherwise;
  }

  private static CtVariable<?> named(CtVariable<?> variable, String name) {
    return variable != null && name.equals(variable.getSimpleName()) ? variable : null;
  }

  record Found(CtVariable<?> declaration, CtType<?> boundary) {

    static final Found NOTHING = new Found(null, null);
  }
}
