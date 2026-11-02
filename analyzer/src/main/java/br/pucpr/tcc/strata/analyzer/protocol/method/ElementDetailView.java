package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.graph.Cycle;
import br.pucpr.tcc.strata.analyzer.graph.ResolutionMix;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.SourceLines;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import br.pucpr.tcc.strata.analyzer.structure.ElementDetail;
import br.pucpr.tcc.strata.analyzer.structure.ElementMetrics;
import br.pucpr.tcc.strata.analyzer.structure.GraphFacts;
import br.pucpr.tcc.strata.analyzer.structure.MethodSummary;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

public record ElementDetailView(
    String elementId,
    String filePath,
    Graph graph,
    @JsonProperty("isTest") boolean isTest,
    ElementKind kind,
    String label,
    Lines lines,
    int methodTotal,
    List<Method> methods,
    Metrics metrics,
    List<String> modifiers,
    String packageId,
    String qualifiedName,
    TypeKind typeKind) {

  public ElementDetailView {
    Objects.requireNonNull(elementId, "elementId");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(qualifiedName, "qualifiedName");
    methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    modifiers = List.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
  }

  public static ElementDetailView of(ElementDetail detail) {
    return new ElementDetailView(
        detail.elementId().value(),
        detail.filePath(),
        graph(detail.graph()),
        detail.isTest(),
        detail.kind(),
        detail.label(),
        lines(detail.lines()),
        detail.methodTotal(),
        detail.methods().stream().map(ElementDetailView::method).toList(),
        metrics(detail.metrics()),
        detail.modifiers(),
        detail.packageId() == null ? null : detail.packageId().value(),
        detail.qualifiedName(),
        detail.typeKind());
  }

  private static Graph graph(GraphFacts facts) {
    final Cycle cycle = facts.cycle();
    final ResolutionMix mix = facts.resolutionMix();
    return new Graph(
        cycle == null ? null : new CycleView(cycle.id(), cycle.size()),
        facts.fanIn(),
        facts.fanOut(),
        facts.pagerank(),
        new GraphSliceView.Mix(mix.inferred(), mix.resolved(), mix.unresolvedExternal()));
  }

  private static Metrics metrics(ElementMetrics metrics) {
    return new Metrics(
        metrics.ccMax(),
        metrics.ccMean(),
        metrics.ccP90(),
        metrics.ccSum(),
        metrics.loc(),
        metrics.unitCount());
  }

  private static Method method(MethodSummary method) {
    return new Method(
        method.cc(), method.elementId().value(), lines(method.lines()), method.signature());
  }

  private static Lines lines(SourceLines lines) {
    return lines == null ? null : new Lines(lines.end(), lines.start());
  }

  public record Graph(
      CycleView cycle, int fanIn, int fanOut, double pagerank, GraphSliceView.Mix resolutionMix) {}

  public record CycleView(int id, int size) {}

  public record Lines(int end, int start) {}

  public record Method(Integer cc, String elementId, Lines lines, String signature) {}

  public record Metrics(
      int ccMax, double ccMean, Integer ccP90, long ccSum, long loc, long unitCount) {}
}
