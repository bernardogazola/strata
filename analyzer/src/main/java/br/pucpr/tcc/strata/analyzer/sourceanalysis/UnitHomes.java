package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.IdentityHashMap;
import java.util.Map;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;

final class UnitHomes {

  private final SimpleNameIndex index;
  private final Map<CtCompilationUnit, Home> homes = new IdentityHashMap<>();

  UnitHomes(SimpleNameIndex index) {
    this.index = index;
  }

  Home of(CtCompilationUnit unit, String ownPackage) {
    return homes.computeIfAbsent(
        unit,
        key ->
            new Home(
                ownPackage,
                index.scopeOf(key, ownPackage),
                ImportScope.of(key, ownPackage, index.retainedStatics(key))));
  }

  Home of(CtElement declaration) {
    final CtType<?> declaring =
        declaration instanceof CtType<?> type ? type : declaration.getParent(CtType.class);
    if (declaring == null) {
      return null;
    }
    final CtType<?> top = declaring.getTopLevelType();
    final SourcePosition position = top.getPosition();
    final CtCompilationUnit unit =
        position.isValidPosition() ? position.getCompilationUnit() : null;
    return unit == null ? null : of(unit, ElementIds.packageName(top));
  }

  record Home(String ownPackage, SimpleNameIndex.Scope scope, ImportScope imports) {}
}
