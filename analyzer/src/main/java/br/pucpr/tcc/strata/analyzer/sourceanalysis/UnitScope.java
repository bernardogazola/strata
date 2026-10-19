package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.LookupState;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.NameContext;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.NameResolution;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.Outcome;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.TypeLookup;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SimpleNameIndex.WrittenName;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;

final class UnitScope implements SimpleNameIndex.Scope {

  private static final Set<String> PRIMITIVES =
      Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

  private static final String JAVA_LANG = "java.lang.";
  private static final String DIMENSION = "[]";

  private final SimpleNameIndex index;
  private final ImportScope imports;
  private final Map<NameContext, Map<String, NameResolution>> simpleNames = new HashMap<>();
  private final Map<NameContext, Map<String, NameResolution>> qualifiedNames = new HashMap<>();
  private final Map<NameContext, List<Frame>> chains = new HashMap<>();
  private final Map<CtType<?>, NameContext> contexts = new IdentityHashMap<>();
  private final Map<String, Map<String, TypeLookup>> memberTypes = new HashMap<>();
  private final Map<String, Placement> scopeSteps = new HashMap<>();
  private final Map<String, Placement> scopeStepsWithJavaLang = new HashMap<>();
  private final Map<String, String> importedNames = new HashMap<>();
  private long dottedConflicts;

  UnitScope(SimpleNameIndex index, ImportScope imports) {
    this.index = index;
    this.imports = imports;
  }

  @Override
  public String canonical(String name, boolean writtenSimple) {
    return resolve(name, writtenSimple, NameContext.NONE).name();
  }

  @Override
  public NameResolution resolve(String name, boolean writtenSimple, NameContext context) {
    final Map<String, NameResolution> known =
        (writtenSimple ? simpleNames : qualifiedNames)
            .computeIfAbsent(context, _ -> new HashMap<>());
    final NameResolution memorized = known.get(name);
    if (memorized != null) {
      return memorized;
    }
    final NameResolution resolved = compute(name, writtenSimple, context);
    known.put(name, resolved);
    return resolved;
  }

  @Override
  public NameContext context(CtElement element) {
    final CtType<?> owner = ElementIds.bodyOwner(element);
    return owner == null
        ? NameContext.NONE
        : contexts.computeIfAbsent(owner, ElementIds::contextOf);
  }

  long dottedConflicts() {
    return dottedConflicts;
  }

  String resolveSource(String text, List<String> chain) {
    final List<Frame> frames = new ArrayList<>(chain.size());
    for (final String id : chain) {
      frames.add(new Frame(id, null));
    }
    final Reading reading = read(text, frames, null);
    return reading.validity() == Validity.NONE || reading.validity() == Validity.AMBIGUOUS
        ? null
        : reading.name();
  }

  String fieldType(String written, List<String> body, Set<String> typeVariables) {
    int end = written.length();
    while (end >= DIMENSION.length() && written.startsWith(DIMENSION, end - DIMENSION.length())) {
      end -= DIMENSION.length();
    }
    final String base = written.substring(0, end);
    if (typeVariables.contains(base)) {
      return null;
    }
    if (PRIMITIVES.contains(base)) {
      return written;
    }
    final String placed = resolveSource(base, body);
    final String name = placed == null ? base : placed;
    return end == written.length() ? name : name + written.substring(end);
  }

  private NameResolution compute(String name, boolean writtenSimple, NameContext context) {
    if (PRIMITIVES.contains(name)) {
      return new NameResolution(name, Outcome.UNCHANGED_INCOMPLETE);
    }
    final List<Frame> frames = chains.computeIfAbsent(context, this::frames);
    if (writtenSimple) {
      final Placement placement = simple(name.substring(name.lastIndexOf('.') + 1), frames, name);
      return switch (placement.state()) {
        case FOUND -> new NameResolution(placement.name(), Outcome.PLACED);
        case AMBIGUOUS -> new NameResolution(name, Outcome.AMBIGUOUS);
        default -> unchanged(name, placement.incomplete() || !complete(frames));
      };
    }
    return qualified(name, frames);
  }

  private NameResolution qualified(String name, List<Frame> frames) {
    final Reading printed = read(name, frames, name);
    final String ownPackage = imports.ownPackage();
    final Reading stripped =
        !ownPackage.isEmpty() && name.startsWith(ownPackage + ".")
            ? read(name.substring(ownPackage.length() + 1), frames, null)
            : null;
    final Reading chosen = choose(printed, stripped);
    return switch (chosen.validity()) {
      case FULL, PARTIAL, INVALID -> new NameResolution(chosen.name(), Outcome.PLACED);
      case AMBIGUOUS -> new NameResolution(name, Outcome.AMBIGUOUS);
      case NONE -> unchanged(name, chosen.incomplete() || !complete(frames));
    };
  }

  private Reading choose(Reading printed, Reading stripped) {
    if (stripped == null) {
      return printed;
    }
    if (printed.valid() && stripped.valid()) {
      if (!printed.name().equals(stripped.name())) {
        dottedConflicts++;
      }
      return printed;
    }
    if (stripped.valid()) {
      return stripped;
    }
    if (printed.valid() || printed.validity() == Validity.INVALID) {
      return printed;
    }
    if (stripped.validity() == Validity.INVALID || stripped.validity() == Validity.AMBIGUOUS) {
      return stripped;
    }
    return printed;
  }

  private Reading read(String text, List<Frame> frames, String spoonName) {
    final int separator = nextSeparator(text, 1);
    final String first = text.substring(0, separator);
    final Placement head = simple(first, frames, separator == text.length() ? spoonName : null);
    if (head.state() == LookupState.AMBIGUOUS) {
      return new Reading(text, Validity.AMBIGUOUS, false);
    }
    if (head.state() == LookupState.FOUND) {
      return members(head.name(), text.substring(separator));
    }
    final int top = index.topLevelPrefixEnd(text);
    if (top > 0) {
      return members(index.canonicalInstance(text.substring(0, top)), text.substring(top));
    }
    return new Reading(text, Validity.NONE, head.incomplete());
  }

  private Reading members(String head, String rest) {
    String current = head;
    Validity validity = index.type(head) == null ? Validity.PARTIAL : Validity.FULL;
    int at = 0;
    while (at < rest.length()) {
      final int next = nextSeparator(rest, at + 1);
      final TypeLookup member = index.memberTypeOf(current, rest.substring(at + 1, next), false);
      if (member.state() == LookupState.FOUND) {
        current = member.name();
        at = next;
        continue;
      }
      if (member.state() == LookupState.AMBIGUOUS) {
        return new Reading(head + rest, Validity.AMBIGUOUS, false);
      }
      validity = member.state() == LookupState.NONE ? Validity.INVALID : Validity.PARTIAL;
      break;
    }
    final String name = current + rest.substring(at).replace('.', '$');
    return new Reading(index.canonicalInstance(name), validity, false);
  }

  private Placement simple(String n, List<Frame> frames, String spoonName) {
    boolean incomplete = false;
    for (final Frame frame : frames) {
      final TypeLookup member = memberOf(frame, n);
      switch (member.state()) {
        case FOUND -> {
          return Placement.found(member.name());
        }
        case AMBIGUOUS -> {
          return Placement.AMBIGUOUS;
        }
        case UNKNOWN -> incomplete = true;
        case NONE -> {}
      }
    }
    final boolean javaLang =
        spoonName != null
            && spoonName.length() == JAVA_LANG.length() + n.length()
            && spoonName.startsWith(JAVA_LANG)
            && spoonName.endsWith(n);
    final Placement steps =
        (javaLang ? scopeStepsWithJavaLang : scopeSteps)
            .computeIfAbsent(n, name -> scopeSteps(name, javaLang));
    return incomplete && steps.state() == LookupState.NONE ? Placement.none(true) : steps;
  }

  private Placement scopeSteps(String n, boolean javaLang) {
    final String single = imports.singleType(n);
    if (single != null) {
      return Placement.found(imported(single));
    }
    final Placement imported = singleStatic(n);
    if (imported != null) {
      return imported;
    }
    final String own = SimpleNameIndex.qualify(imports.ownPackage(), n);
    if (index.isTopLevel(own)) {
      return Placement.found(index.canonicalInstance(own));
    }
    return onDemand(n, javaLang, !imports.singleStatic(n).isEmpty());
  }

  private Placement singleStatic(String n) {
    final Set<String> found = new TreeSet<>();
    for (final String owner : imports.singleStatic(n)) {
      final TypeLookup member = index.memberTypeOf(imported(owner), n, true);
      if (member.state() == LookupState.AMBIGUOUS) {
        return Placement.AMBIGUOUS;
      }
      if (member.state() == LookupState.FOUND) {
        found.add(member.name());
      }
    }
    if (found.isEmpty()) {
      return null;
    }
    return found.size() == 1 ? Placement.found(found.iterator().next()) : Placement.AMBIGUOUS;
  }

  private Placement onDemand(String n, boolean javaLang, boolean incompleteSoFar) {
    boolean incomplete = incompleteSoFar || imports.moduleImport();
    final Set<String> candidates = new TreeSet<>();
    for (final String imported : imports.onDemand()) {
      final String top = SimpleNameIndex.qualify(imported, n);
      if (index.isTopLevel(top)) {
        candidates.add(index.canonicalInstance(top));
        continue;
      }
      final IndexedType owner = index.type(imported(imported));
      if (owner != null) {
        final String member = index.declaredMemberType(owner, n, imports.ownPackage());
        if (member != null) {
          candidates.add(member);
        }
      } else if (!index.declaresPackage(imported)) {
        incomplete = true;
      }
    }
    for (final String imported : imports.staticOnDemand()) {
      final TypeLookup member = index.memberTypeOf(imported(imported), n, true);
      switch (member.state()) {
        case FOUND -> candidates.add(member.name());
        case AMBIGUOUS -> {
          return Placement.AMBIGUOUS;
        }
        case UNKNOWN -> incomplete = true;
        case NONE -> {}
      }
    }
    if (javaLang) {
      candidates.add(JAVA_LANG + n);
    }
    if (candidates.size() == 1) {
      return Placement.found(candidates.iterator().next());
    }
    return candidates.isEmpty() ? Placement.none(incomplete) : Placement.AMBIGUOUS;
  }

  private TypeLookup memberOf(Frame frame, String n) {
    if (frame.localSupertypes() == null) {
      return memberTypes
          .computeIfAbsent(frame.id(), _ -> new HashMap<>())
          .computeIfAbsent(n, name -> index.memberTypeOf(frame.id(), name, false));
    }
    return index.inheritedMemberType(frame.localSupertypes(), n, false, imports.ownPackage());
  }

  private boolean complete(List<Frame> frames) {
    for (final Frame frame : frames) {
      if (frame.localSupertypes() == null) {
        final IndexedType type = index.type(frame.id());
        if (type == null || !type.complete()) {
          return false;
        }
        continue;
      }
      for (final String supertype : frame.localSupertypes()) {
        final IndexedType type = supertype == null ? null : index.type(supertype);
        final boolean known =
            type == null ? supertype != null && SimpleNameIndex.isRoot(supertype) : type.complete();
        if (!known) {
          return false;
        }
      }
    }
    return true;
  }

  private static NameResolution unchanged(String name, boolean incomplete) {
    return new NameResolution(
        name, incomplete ? Outcome.UNCHANGED_INCOMPLETE : Outcome.UNCHANGED_COMPLETE);
  }

  private List<Frame> frames(NameContext context) {
    final List<String> entries = context.enclosingTypes();
    final Deque<Frame> frames = new ArrayDeque<>(entries.size());
    for (int at = entries.size() - 1; at >= 0; at--) {
      final String entry = entries.get(at);
      final List<WrittenName> written = context.localSupertypes().get(entry);
      if (written == null) {
        frames.addFirst(new Frame(entry, null));
        continue;
      }
      final List<Frame> outer = List.copyOf(frames);
      final String[] supertypes = new String[written.size()];
      for (int i = 0; i < supertypes.length; i++) {
        supertypes[i] = localSupertype(written.get(i), outer);
      }
      frames.addFirst(new Frame(entry, supertypes));
    }
    return List.copyOf(frames);
  }

  private String localSupertype(WrittenName written, List<Frame> outer) {
    if (ElementIds.hasLocalSegment(written.name())) {
      return null;
    }
    final NameResolution resolved =
        written.writtenSimple()
            ? simpleResolution(written.name(), outer)
            : qualified(written.name(), outer);
    return resolved.outcome() == Outcome.AMBIGUOUS ? null : resolved.name();
  }

  private NameResolution simpleResolution(String name, List<Frame> outer) {
    final Placement placement = simple(name.substring(name.lastIndexOf('.') + 1), outer, name);
    return placement.state() == LookupState.FOUND
        ? new NameResolution(placement.name(), Outcome.PLACED)
        : new NameResolution(
            name,
            placement.state() == LookupState.AMBIGUOUS
                ? Outcome.AMBIGUOUS
                : Outcome.UNCHANGED_INCOMPLETE);
  }

  private String imported(String text) {
    return importedNames.computeIfAbsent(text, index::importedTypeName);
  }

  private static int nextSeparator(String text, int from) {
    for (int at = from; at < text.length(); at++) {
      final char c = text.charAt(at);
      if (c == '.' || c == '$') {
        return at;
      }
    }
    return text.length();
  }

  private record Frame(String id, String[] localSupertypes) {}

  private record Placement(LookupState state, String name, boolean incomplete) {

    private static final Placement AMBIGUOUS = new Placement(LookupState.AMBIGUOUS, null, false);

    private static Placement found(String name) {
      return new Placement(LookupState.FOUND, name, false);
    }

    private static Placement none(boolean incomplete) {
      return new Placement(LookupState.NONE, null, incomplete);
    }
  }

  private enum Validity {
    FULL,
    PARTIAL,
    INVALID,
    AMBIGUOUS,
    NONE
  }

  private record Reading(String name, Validity validity, boolean incomplete) {

    private boolean valid() {
      return validity == Validity.FULL || validity == Validity.PARTIAL;
    }
  }
}
