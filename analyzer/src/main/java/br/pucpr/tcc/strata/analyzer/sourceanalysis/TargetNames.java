package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.FieldLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberAnswer;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.MemberTypeLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.NameResolution;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.StructureExtractor.DropReason;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.UnitHomes.Home;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtIntersectionTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;

final class TargetNames {

  private static final String DIAMOND = "<>";

  private static final String DOUBLE_DOT = "..";

  private final Rules rules;
  private final Home unit;
  private final Map<DropReason, Integer> drops;

  TargetNames(Rules rules, Home unit, Map<DropReason, Integer> drops) {
    this.rules = rules;
    this.unit = unit;
    this.drops = drops;
  }

  Target reference(CtTypeReference<?> reference) {
    if (ElementIds.hasLocalSegment(reference.getQualifiedName())) {
      return drop(DropReason.LOCAL_TARGET);
    }
    final Named named = name(reference, unit, true);
    return named.typeVariable() ? null : named(named);
  }

  Target qualifier(CtTypeReference<?> reference, boolean written) {
    final String qualified = reference.getQualifiedName();
    if (ElementIds.PRIMITIVES.contains(qualified)) {
      return callWithoutTarget();
    }
    final int local = ElementIds.localSegmentStart(qualified);
    if (local >= 0) {
      return exact(qualified.substring(0, local));
    }
    return member(name(reference, unit, written));
  }

  Target copied(CtTypeReference<?> declared, Home home) {
    return member(name(declared, home, home == unit));
  }

  Target exact(String name) {
    return named(new Named(name, true, false));
  }

  Target imported(String text) {
    return exact(importedName(text));
  }

  Target typeOnDemand(String text) {
    final String canonical = importedName(text);
    return rules.index().declaredTopLevel(canonical).isPresent() ? exact(canonical) : null;
  }

  String importedName(String text) {
    return rules.index().importedTypeName(text);
  }

  String canonical(CtTypeReference<?> reference) {
    return ElementIds.erase(reference, unit.scope());
  }

  boolean isOwnPackageGuess(CtTypeReference<?> type) {
    final String ownPackage = unit.ownPackage();
    final String name = type.getQualifiedName();
    return !ownPackage.isEmpty()
        && !ElementIds.isWrittenSimple(type)
        && name.lastIndexOf('.') == ownPackage.length()
        && name.startsWith(ownPackage)
        && name.indexOf('$') < 0
        && rules.index().declaredTopLevel(name).isEmpty();
  }

  FieldLookup field(CtTypeReference<?> owner, String member) {
    return rules.index().field(canonical(owner), member);
  }

  Target memberOrField(String owner, String member) {
    if (rules.index().type(owner) == null) {
      return callWithoutTarget();
    }
    final MemberTypeLookup type = rules.index().memberType(owner, member);
    if (type.answer() == MemberAnswer.YES) {
      return named(new Named(type.typeName(), false, false));
    }
    final FieldLookup field = rules.index().field(owner, member);
    return field.answer() == MemberAnswer.YES ? recovered(field.typeName()) : callWithoutTarget();
  }

  Target recovered(String typeName) {
    if (typeName == null || ElementIds.PRIMITIVES.contains(typeName)) {
      return callWithoutTarget();
    }
    if (typeName.indexOf('.') < 0 && rules.index().type(typeName) == null) {
      final String javaLang = rules.javaLang().qualify(typeName);
      return named(new Named(javaLang == null ? typeName : javaLang, false, false));
    }
    return named(new Named(typeName, false, false));
  }

  Target callWithoutTarget() {
    return drop(DropReason.CALL_WITHOUT_TARGET);
  }

  private Named name(CtTypeReference<?> reference, Home home, boolean written) {
    if (!isPlain(reference)) {
      final String name = ElementIds.erase(reference, home.scope());
      return new Named(name, name.equals(print(reference)), false);
    }
    final String print = reference.getQualifiedName();
    if (print.endsWith(DIAMOND)) {
      final String stripped = print.substring(0, print.length() - DIAMOND.length());
      return decide(
          reference,
          home.scope().resolve(stripped, true, home.scope().context(reference)),
          true,
          home,
          written);
    }
    return decide(
        reference,
        ElementIds.resolveLeaf(reference, home.scope()),
        ElementIds.isWrittenSimple(reference),
        home,
        written);
  }

  private Named decide(
      CtTypeReference<?> reference,
      NameResolution resolution,
      boolean simple,
      Home home,
      boolean written) {
    final String print = reference.getQualifiedName();
    final String name = resolution.name();
    return switch (resolution.outcome()) {
      case TYPE_VARIABLE ->
          new Named(ElementIds.erase(reference, home.scope()), false, false, true);
      case PLACED -> new Named(name, name.equals(print), false);
      case AMBIGUOUS ->
          simple
              ? new Named(name.substring(name.lastIndexOf('.') + 1), false, true)
              : new Named(name, name.equals(print), false);
      case UNCHANGED_COMPLETE -> {
        final String spelled = simple ? bare(name, home) : name;
        yield written && simple && spelled.indexOf('.') < 0
            ? ambiguity(spelled, home.ownPackage())
            : new Named(spelled, spelled.equals(print), false);
      }
      case UNCHANGED_INCOMPLETE -> {
        final String spelled = simple ? bare(name, home) : name;
        yield new Named(spelled, spelled.equals(print), false);
      }
    };
  }

  private Named ambiguity(String simpleName, String fromPackage) {
    final List<String> candidates = rules.index().topLevelCandidates(simpleName, fromPackage);
    return new Named(simpleName, false, candidates.size() >= 2);
  }

  private String bare(String name, Home home) {
    final String ownPackage = home.ownPackage();
    final int lastDot = name.lastIndexOf('.');
    if (ownPackage.isEmpty()
        || lastDot != ownPackage.length()
        || !name.startsWith(ownPackage)
        || rules.index().declaredTopLevel(name).isPresent()) {
      return name;
    }
    final String simpleName = name.substring(lastDot + 1);
    return home.imports().singleType(simpleName) != null ? name : simpleName;
  }

  private Target member(Named named) {
    final String name = named.name();
    final int dollar = name.lastIndexOf('$');
    if (named.ambiguous()
        || dollar <= name.lastIndexOf('.') + 1
        || rules.index().type(name) != null
        || rules.index().declaredTopLevel(name).isEmpty()) {
      return named(named);
    }
    final String owner = name.substring(0, dollar);
    final String member = name.substring(dollar + 1);
    if (rules.index().type(owner) == null) {
      return named(named);
    }
    final MemberTypeLookup type = rules.index().memberType(owner, member);
    final FieldLookup field = rules.index().field(owner, member);
    final boolean undecidable =
        type.answer() == MemberAnswer.UNKNOWN && field.answer() != MemberAnswer.YES;
    return undecidable ? named(named) : memberOrField(owner, member);
  }

  private Target named(Named named) {
    String name = named.name();
    if (!isQualifiedIdentifier(name)) {
      name = collapsedDoubleDot(name);
      if (name == null) {
        return drop(DropReason.INVALID_NAME);
      }
    }
    final CodeElementId id = CodeElementId.ofType(name);
    if (!id.fitsCeiling()) {
      return drop(DropReason.ID_TOO_LONG);
    }
    final boolean declared = rules.index().declaredTopLevel(name).isPresent();
    if (declared && (named.ambiguous() || !unit.ownPackage().isEmpty() && name.indexOf('.') < 0)) {
      return drop(DropReason.DEFAULT_PACKAGE_COLLISION);
    }
    return new Target(id, declared, quality(name, declared, named.exact()), named.ambiguous());
  }

  private ResolutionQuality quality(String name, boolean declared, boolean exact) {
    if (declared) {
      return exact && rules.declarations().inBatch(name)
          ? ResolutionQuality.RESOLVED
          : ResolutionQuality.INFERRED;
    }
    return rules.platform().isJdk(name)
        ? ResolutionQuality.RESOLVED
        : ResolutionQuality.UNRESOLVED_EXTERNAL;
  }

  private String collapsedDoubleDot(String name) {
    final int at = name.indexOf(DOUBLE_DOT);
    if (at <= 0 || name.indexOf(DOUBLE_DOT, at + 1) >= 0) {
      return null;
    }
    final String collapsed = name.substring(0, at) + name.substring(at + 1);
    return isQualifiedIdentifier(collapsed) && rules.index().declaredTopLevel(collapsed).isEmpty()
        ? collapsed
        : null;
  }

  private Target drop(DropReason reason) {
    drops.merge(reason, 1, Integer::sum);
    return null;
  }

  private static String print(CtTypeReference<?> reference) {
    return ElementIds.erase(reference, UnaryOperator.identity());
  }

  private static boolean isPlain(CtTypeReference<?> reference) {
    return !(reference instanceof CtArrayTypeReference<?>)
        && !(reference instanceof CtTypeParameterReference)
        && !(reference instanceof CtIntersectionTypeReference<?>);
  }

  static boolean isQualifiedIdentifier(String name) {
    int start = 0;
    while (true) {
      final int dot = name.indexOf('.', start);
      final int end = dot < 0 ? name.length() : dot;
      if (!isIdentifier(name, start, end)) {
        return false;
      }
      if (dot < 0) {
        return true;
      }
      start = dot + 1;
    }
  }

  private static boolean isIdentifier(String name, int start, int end) {
    if (start >= end || !Character.isJavaIdentifierStart(name.codePointAt(start))) {
      return false;
    }
    for (int i = start + Character.charCount(name.codePointAt(start)); i < end; ) {
      final int codePoint = name.codePointAt(i);
      if (!Character.isJavaIdentifierPart(codePoint)) {
        return false;
      }
      i += Character.charCount(codePoint);
    }
    return true;
  }

  private record Named(String name, boolean exact, boolean ambiguous, boolean typeVariable) {

    private Named(String name, boolean exact, boolean ambiguous) {
      this(name, exact, ambiguous, false);
    }
  }

  record Rules(
      SimpleNameIndex index,
      BatchDeclarations declarations,
      ExternalPackages platform,
      JavaLangTypes javaLang) {

    static Rules of(SimpleNameIndex index, Set<String> batchTopLevelTypes, JavaLangTypes javaLang) {
      return new Rules(
          index,
          new BatchDeclarations(index, Set.copyOf(batchTopLevelTypes)),
          Platform.PACKAGES,
          javaLang);
    }
  }

  private static final class Platform {

    private static final ExternalPackages PACKAGES = ExternalPackages.load();

    private Platform() {}
  }

  record Target(
      CodeElementId id,
      boolean declared,
      ResolutionQuality quality,
      boolean ambiguous,
      boolean uncertainCallee) {

    Target(CodeElementId id, boolean declared, ResolutionQuality quality, boolean ambiguous) {
      this(id, declared, quality, ambiguous, false);
    }

    Target uncertain() {
      final ResolutionQuality weaker =
          declared && quality == ResolutionQuality.RESOLVED ? ResolutionQuality.INFERRED : quality;
      return new Target(id, declared, weaker, ambiguous, true);
    }
  }
}
