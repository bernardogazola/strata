package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.FieldLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberAnswer;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitHomes.Home;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

final class UnqualifiedCalls {

  private final SimpleNameIndex index;
  private final Home unit;

  UnqualifiedCalls(SimpleNameIndex index, Home unit) {
    this.index = index;
    this.unit = unit;
  }

  String qualifying(CtElement site, String name) {
    for (CtType<?> type = site.getParent(CtType.class);
        type != null;
        type = type.getParent(CtType.class)) {
      if (methodOf(type, name) == MemberAnswer.YES) {
        return ElementIds.idName(ElementIds.nearestElementType(type));
      }
    }
    final Set<String> single = new TreeSet<>();
    for (final String owner : unit.imports().singleStatic(name)) {
      single.add(index.importedTypeName(owner));
    }
    if (single.size() == 1) {
      return single.iterator().next();
    }
    final List<String> onDemand = new ArrayList<>();
    for (final String owner : unit.imports().staticOnDemand()) {
      final String imported = index.importedTypeName(owner);
      if (index.hasMethod(imported, name) != MemberAnswer.NO && !onDemand.contains(imported)) {
        onDemand.add(imported);
      }
    }
    return onDemand.size() == 1 ? onDemand.getFirst() : null;
  }

  FieldLookup field(CtElement site, String name) {
    for (CtType<?> type = site.getParent(CtType.class);
        type != null;
        type = type.getParent(CtType.class)) {
      final FieldLookup found =
          ElementIds.isElementType(type)
              ? index.field(ElementIds.idName(type), name)
              : fieldOfSupertypes(type, name);
      if (found.answer() == MemberAnswer.YES) {
        return found;
      }
    }
    return FieldLookup.UNKNOWN;
  }

  private MemberAnswer methodOf(CtType<?> type, String name) {
    if (ElementIds.isElementType(type)) {
      return index.hasMethod(ElementIds.idName(type), name);
    }
    if (!type.getMethodsByName(name).isEmpty()) {
      return MemberAnswer.YES;
    }
    boolean unknown = false;
    for (final String supertype : supertypes(type)) {
      final MemberAnswer answer = answer(supertype, name);
      if (answer == MemberAnswer.YES) {
        return MemberAnswer.YES;
      }
      unknown |= answer == MemberAnswer.UNKNOWN;
    }
    return unknown ? MemberAnswer.UNKNOWN : MemberAnswer.NO;
  }

  private MemberAnswer answer(String supertype, String name) {
    if (index.type(supertype) != null) {
      return index.hasMethod(supertype, name);
    }
    return SimpleNameIndex.isRoot(supertype) ? MemberAnswer.NO : MemberAnswer.UNKNOWN;
  }

  private FieldLookup fieldOfSupertypes(CtType<?> type, String name) {
    for (final String supertype : supertypes(type)) {
      final FieldLookup found =
          index.type(supertype) == null ? FieldLookup.UNKNOWN : index.field(supertype, name);
      if (found.answer() == MemberAnswer.YES) {
        return found;
      }
    }
    return FieldLookup.UNKNOWN;
  }

  private List<String> supertypes(CtType<?> type) {
    final List<String> names = new ArrayList<>();
    final CtTypeReference<?> superclass = type.getSuperclass();
    if (superclass != null) {
      names.add(ElementIds.resolveLeaf(superclass, unit.scope()).name());
    }
    for (final CtTypeReference<?> superinterface : type.getSuperInterfaces()) {
      names.add(ElementIds.resolveLeaf(superinterface, unit.scope()).name());
    }
    return names;
  }
}
