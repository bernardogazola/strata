package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.ClassFactory;
import spoon.reflect.factory.Factory;

final class GuardedClassFactory extends ClassFactory {

  GuardedClassFactory(Factory factory) {
    super(factory);
  }

  @Override
  public <T> CtClass<T> create(CtPackage owner, String simpleName) {
    if (!holdsWrittenType(owner, simpleName)) {
      return super.create(owner, simpleName);
    }
    final CtClass<T> detached = factory.Core().createClass();
    detached.setSimpleName(simpleName);
    detached.setParent(owner);
    return detached;
  }

  private static boolean holdsWrittenType(CtPackage owner, String simpleName) {
    final CtType<?> existing = owner.getType(simpleName);
    return existing != null && !existing.isShadow() && existing.getPosition().isValidPosition();
  }
}
