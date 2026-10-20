package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.FieldLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberAnswer;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitHomes.Home;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;

final class UnqualifiedCalls {

  private final SimpleNameIndex index;
  private final Home unit;

  UnqualifiedCalls(SimpleNameIndex index, Home unit) {
    this.index = index;
    this.unit = unit;
  }

  Qualifying qualifying(CtElement site, String name) {
    for (CtType<?> type = site.getParent(CtType.class);
        type != null;
        type = type.getParent(CtType.class)) {
      switch (methodOf(type, name)) {
        case YES -> {
          return Qualifying.decided(ElementIds.idName(ElementIds.nearestElementType(type)));
        }
        case UNKNOWN -> {
          return new Qualifying(null, opaque(type) ? outerElementTypes(type) : Set.of());
        }
        case NO -> {}
      }
    }
    final Set<String> single = new TreeSet<>();
    for (final String owner : unit.imports().singleStatic(name)) {
      single.add(index.importedTypeName(owner));
    }
    if (single.size() == 1) {
      return Qualifying.decided(single.iterator().next());
    }
    final List<String> onDemand = new ArrayList<>();
    for (final String owner : unit.imports().staticOnDemand()) {
      final String imported = index.importedTypeName(owner);
      if (index.hasMethod(imported, name) != MemberAnswer.NO && !onDemand.contains(imported)) {
        onDemand.add(imported);
      }
    }
    return onDemand.size() == 1 ? Qualifying.decided(onDemand.getFirst()) : Qualifying.NONE;
  }

  private boolean opaque(CtType<?> type) {
    if (ElementIds.isElementType(type)) {
      return index.opaque(ElementIds.idName(type));
    }
    for (final String supertype : supertypes(type)) {
      if (index.opaque(supertype)) {
        return true;
      }
    }
    return false;
  }

  private static Set<CodeElementId> outerElementTypes(CtType<?> type) {
    final Set<CodeElementId> outer = new HashSet<>();
    for (CtType<?> current = type.getParent(CtType.class);
        current != null;
        current = current.getParent(CtType.class)) {
      if (ElementIds.isElementType(current)) {
        outer.add(CodeElementId.ofType(ElementIds.idName(current)));
      }
    }
    return outer;
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

  Variable variable(CtElement site, String name) {
    CtElement from = site;
    boolean uncertain = false;
    while (true) {
      final LexicalScope.Found local = LexicalScope.local(from, name);
      if (local.declaration() != null) {
        return new Variable(local.declaration(), null, uncertain);
      }
      final CtType<?> type = local.boundary();
      if (type == null) {
        return Variable.NONE;
      }
      final CtField<?> declared = ElementIds.isElementType(type) ? null : type.getField(name);
      if (declared != null) {
        return new Variable(declared, null, uncertain);
      }
      final FieldLookup found =
          ElementIds.isElementType(type)
              ? index.field(ElementIds.idName(type), name)
              : fieldOfSupertypes(type, name);
      if (found.answer() == MemberAnswer.YES) {
        return new Variable(null, found, uncertain);
      }
      uncertain |= found.answer() == MemberAnswer.UNKNOWN && opaque(type);
      from = type;
    }
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
    if (supertype != null && index.type(supertype) != null) {
      return index.hasMethod(supertype, name);
    }
    return supertype != null && SimpleNameIndex.isRoot(supertype)
        ? MemberAnswer.NO
        : MemberAnswer.UNKNOWN;
  }

  private FieldLookup fieldOfSupertypes(CtType<?> type, String name) {
    boolean unknown = false;
    for (final String supertype : supertypes(type)) {
      final FieldLookup found;
      if (supertype != null && index.type(supertype) != null) {
        found = index.field(supertype, name);
      } else {
        found =
            supertype != null && SimpleNameIndex.isRoot(supertype)
                ? FieldLookup.NO
                : FieldLookup.UNKNOWN;
      }
      if (found.answer() == MemberAnswer.YES) {
        return found;
      }
      unknown |= found.answer() == MemberAnswer.UNKNOWN;
    }
    return unknown ? FieldLookup.UNKNOWN : FieldLookup.NO;
  }

  private List<String> supertypes(CtType<?> type) {
    if (unit.scope() instanceof UnitScope scope) {
      return scope.localSupertypes(type);
    }
    throw new IllegalStateException("as chamadas de uma unidade pedem o escopo dela");
  }

  record Variable(CtVariable<?> declaration, FieldLookup field, boolean uncertain) {

    static final Variable NONE = new Variable(null, null, false);
  }

  record Qualifying(String name, Set<CodeElementId> outerOfUnknown) {

    static final Qualifying NONE = new Qualifying(null, Set.of());

    Qualifying {
      outerOfUnknown = Set.copyOf(outerOfUnknown);
    }

    private static Qualifying decided(String name) {
      return new Qualifying(name, Set.of());
    }
  }
}
