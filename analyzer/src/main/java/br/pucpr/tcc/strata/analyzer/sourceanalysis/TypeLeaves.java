package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.ArrayList;
import java.util.List;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtIntersectionTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;

final class TypeLeaves {

  private TypeLeaves() {}

  static List<CtTypeReference<?>> of(CtTypeReference<?> type) {
    final List<CtTypeReference<?>> leaves = new ArrayList<>();
    collect(type, leaves);
    return leaves;
  }

  private static void collect(CtTypeReference<?> type, List<CtTypeReference<?>> leaves) {
    switch (type) {
      case null -> {}
      case CtArrayTypeReference<?> array -> collect(array.getComponentType(), leaves);
      case CtWildcardReference wildcard -> {
        if (!wildcard.isDefaultBoundingType()) {
          collect(wildcard.getBoundingType(), leaves);
        }
      }
      case CtTypeParameterReference _ -> {}
      case CtIntersectionTypeReference<?> intersection -> {
        for (final CtTypeReference<?> bound : intersection.getBounds()) {
          collect(bound, leaves);
        }
      }
      default -> named(type, leaves);
    }
  }

  private static void named(CtTypeReference<?> type, List<CtTypeReference<?>> leaves) {
    if (type.isPrimitive()) {
      return;
    }
    leaves.add(type);
    arguments(type, leaves);
    for (CtTypeReference<?> qualifier = type.getDeclaringType();
        qualifier != null;
        qualifier = qualifier.getDeclaringType()) {
      arguments(qualifier, leaves);
    }
  }

  private static void arguments(CtTypeReference<?> type, List<CtTypeReference<?>> leaves) {
    for (final CtTypeReference<?> argument : type.getActualTypeArguments()) {
      collect(argument, leaves);
    }
  }
}
