package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.FieldLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberAnswer;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.TypeLookup;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeSet;

final class InheritedMembers {

  private final SimpleNameIndex index;

  private Set<IndexedType> visitedWithPackage;

  private Set<IndexedType> visitedWithoutPackage;

  InheritedMembers(SimpleNameIndex index) {
    this.index = index;
  }

  TypeLookup memberType(String[] supertypes, String name, boolean staticOnly, String heirPackage) {
    final Set<String> found = new TreeSet<>();
    boolean unknown = false;
    for (final String supertype : supertypes) {
      final IndexedType parent = parent(supertype);
      if (parent == null) {
        unknown |= opens(supertype);
        continue;
      }
      if (!enter(parent, heirPackage)) {
        continue;
      }
      final TypeLookup member = memberOfParent(parent, name, staticOnly, heirPackage);
      switch (member.state()) {
        case FOUND -> found.add(member.name());
        case AMBIGUOUS -> {
          return TypeLookup.AMBIGUOUS;
        }
        case UNKNOWN -> unknown = true;
        case NONE -> {}
      }
    }
    if (found.size() == 1) {
      return TypeLookup.found(found.iterator().next());
    }
    if (found.size() > 1) {
      return TypeLookup.AMBIGUOUS;
    }
    return unknown ? TypeLookup.UNKNOWN : TypeLookup.NONE;
  }

  MemberAnswer method(IndexedType heir, String name, String heirPackage) {
    boolean unknown = false;
    for (final String supertype : index.supertypesOf(heir)) {
      final IndexedType parent = parent(supertype);
      if (parent == null) {
        unknown |= opens(supertype);
        continue;
      }
      if (!enter(parent, heirPackage)) {
        continue;
      }
      final int at = parent.methods().find(name);
      if (at >= 0) {
        final byte flags = parent.methods().flags(at);
        final boolean interfaceStatic = parent.isInterface() && (flags & MemberTable.INSTANCE) == 0;
        if (!interfaceStatic
            && inheritable(MemberTable.visibility(flags), parent.packageName(), heirPackage)) {
          return MemberAnswer.YES;
        }
      }
      final MemberAnswer above = method(parent, name, through(parent, heirPackage));
      if (above == MemberAnswer.YES) {
        return above;
      }
      unknown |= above == MemberAnswer.UNKNOWN;
    }
    return unknown ? MemberAnswer.UNKNOWN : MemberAnswer.NO;
  }

  FieldLookup field(IndexedType heir, String name, String heirPackage) {
    boolean unknown = false;
    for (final String supertype : index.supertypesOf(heir)) {
      final IndexedType parent = parent(supertype);
      if (parent == null) {
        unknown |= opens(supertype);
        continue;
      }
      if (!enter(parent, heirPackage)) {
        continue;
      }
      final int at = parent.fields().find(name);
      if (at >= 0) {
        if (inheritable(
            MemberTable.visibility(parent.fields().flags(at)), parent.packageName(), heirPackage)) {
          return new FieldLookup(MemberAnswer.YES, parent.id(), parent.fieldTypes()[at]);
        }
        continue;
      }
      final FieldLookup above = field(parent, name, through(parent, heirPackage));
      if (above.answer() == MemberAnswer.YES) {
        return above;
      }
      unknown |= above.answer() == MemberAnswer.UNKNOWN;
    }
    return unknown ? FieldLookup.UNKNOWN : FieldLookup.NO;
  }

  private TypeLookup memberOfParent(
      IndexedType parent, String name, boolean staticOnly, String pathPackage) {
    final IndexedType declared = index.type(parent.id() + "$" + name);
    if (declared != null) {
      if (!inheritable(declared.visibility(), declared.packageName(), pathPackage)) {
        return TypeLookup.NONE;
      }
      return staticOnly && !declared.isStatic() ? TypeLookup.NONE : TypeLookup.found(declared.id());
    }
    return memberType(index.supertypesOf(parent), name, staticOnly, through(parent, pathPackage));
  }

  private IndexedType parent(String supertype) {
    final IndexedType parent = supertype == null ? null : index.type(supertype);
    return parent == null || !parent.structured() ? null : parent;
  }

  private static boolean opens(String supertype) {
    return supertype == null || !SimpleNameIndex.isRoot(supertype);
  }

  private boolean enter(IndexedType type, String pathPackage) {
    if (pathPackage == null) {
      if (visitedWithoutPackage == null) {
        visitedWithoutPackage = Collections.newSetFromMap(new IdentityHashMap<>());
      }
      return visitedWithoutPackage.add(type);
    }
    if (visitedWithPackage == null) {
      visitedWithPackage = Collections.newSetFromMap(new IdentityHashMap<>());
    }
    return visitedWithPackage.add(type);
  }

  private static boolean inheritable(int visibility, String memberPackage, String pathPackage) {
    if (visibility == MemberTable.PRIVATE) {
      return false;
    }
    return visibility != MemberTable.PACKAGE || memberPackage.equals(pathPackage);
  }

  private static String through(IndexedType parent, String pathPackage) {
    return parent.packageName().equals(pathPackage) ? pathPackage : null;
  }
}
