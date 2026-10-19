package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.EvidenceCollector.Occurrence;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SiteAttribution.Anchor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.TargetNames.Target;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.LinkedHashMap;
import java.util.Map;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.CtTypeReference;

final class OccurrenceRecorder {

  private final EvidenceCollector collector;
  private final SiteAttribution attribution;
  private final TargetNames names;

  OccurrenceRecorder(EvidenceCollector collector, SiteAttribution attribution, TargetNames names) {
    this.collector = collector;
    this.attribution = attribution;
    this.names = names;
  }

  TargetNames names() {
    return names;
  }

  CodeElementId source(CtElement site) {
    return attribution.source(site);
  }

  void declaration(CtElement site, CtTypeReference<?> type, DependencyType kind) {
    final CodeElementId source = attribution.source(site);
    if (source == null) {
      return;
    }
    final Map<CodeElementId, Anchored> targets = new LinkedHashMap<>();
    for (final CtTypeReference<?> leaf : TypeLeaves.of(type)) {
      final Target target = names.reference(leaf);
      if (target != null) {
        targets.putIfAbsent(target.id(), new Anchored(target, SiteAttribution.nearest(leaf)));
      }
    }
    if (targets.isEmpty()) {
      return;
    }
    final CodeElementId fromMethod = attribution.fromMethod(site);
    for (final Anchored anchored : targets.values()) {
      add(source, anchored.target(), kind, anchored.anchor(), fromMethod);
    }
  }

  void reference(CtElement site, CtTypeReference<?> reference, DependencyType kind) {
    final CodeElementId source = attribution.source(site);
    if (source == null || reference == null) {
      return;
    }
    final Target target = names.reference(reference);
    if (target != null) {
      add(source, target, kind, SiteAttribution.nearest(reference), attribution.fromMethod(site));
    }
  }

  void call(CtElement site, CodeElementId source, Target target) {
    if (target != null) {
      add(
          source,
          target,
          DependencyType.CALLS,
          SiteAttribution.at(site),
          attribution.fromMethod(site));
    }
  }

  void imported(CtElement statement, CodeElementId owner, Target target) {
    if (target != null) {
      add(owner, target, DependencyType.IMPORTS, SiteAttribution.at(statement), null);
    }
  }

  private void add(
      CodeElementId source,
      Target target,
      DependencyType kind,
      Anchor anchor,
      CodeElementId fromMethod) {
    collector.add(
        new Occurrence(
            source,
            target.id(),
            kind,
            target.quality(),
            target.declared(),
            anchor.line(),
            anchor.offset(),
            fromMethod,
            null));
  }

  private record Anchored(Target target, Anchor anchor) {}
}
