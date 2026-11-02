package br.pucpr.tcc.strata.analyzer.graph;

import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ExternalPackages;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.Dependency;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class StructuralGraphInputs {

  static final ExternalPackages PACKAGES = ExternalPackages.load();

  private StructuralGraphInputs() {}

  static CodeElementId type(String qualifiedName) {
    return CodeElementId.ofType(qualifiedName);
  }

  static CodeElementId pkg(String qualifiedName) {
    return CodeElementId.ofPackage(qualifiedName);
  }

  static CodeElementId declaredPackage(CodeElementId type) {
    final String body = type.sortKey();
    final int dollar = body.indexOf('$');
    final String topLevel = dollar < 0 ? body : body.substring(0, dollar);
    final int lastDot = topLevel.lastIndexOf('.');
    return pkg(lastDot < 0 ? "" : topLevel.substring(0, lastDot));
  }

  static TypeNode node(String qualifiedName) {
    final CodeElementId id = type(qualifiedName);
    return new TypeNode(id, declaredPackage(id));
  }

  static Dependency row(
      String source,
      String target,
      DependencyType type,
      int occurrences,
      ResolutionQuality resolution) {
    return new Dependency(type(source), type(target), type, resolution, occurrences);
  }

  static Dependency resolved(String source, String target, DependencyType type) {
    return row(source, target, type, 1, ResolutionQuality.RESOLVED);
  }

  static StructuralGraphResult compute(GraphInput input) {
    return StructuralGraphAnalysis.compute(input, PACKAGES, () -> false);
  }

  static TypeMetrics typeMetrics(StructuralGraphResult result, String qualifiedName) {
    final CodeElementId id = type(qualifiedName);
    return result.types().stream()
        .filter(metrics -> metrics.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("nenhuma métrica de TYPE para " + id));
  }

  static PackageMetrics packageMetrics(StructuralGraphResult result, String qualifiedName) {
    final CodeElementId id = pkg(qualifiedName);
    return result.packages().stream()
        .filter(metrics -> metrics.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("nenhuma métrica de PACKAGE para " + id));
  }

  static JsonNode golden(String fixture) throws IOException {
    return new ObjectMapper()
        .readTree(TestRepositories.corpus(fixture).resolve("expected.json").toFile());
  }

  static GraphInput goldenInput(JsonNode golden) {
    final List<TypeNode> types = new ArrayList<>();
    for (final JsonNode type : golden.get("types")) {
      final CodeElementId id = new CodeElementId(type.get("id").asText());
      types.add(new TypeNode(id, declaredPackage(id)));
    }
    final List<Dependency> rows = new ArrayList<>();
    for (final JsonNode row : golden.get("dependencies")) {
      rows.add(
          new Dependency(
              new CodeElementId(row.get("from").asText()),
              new CodeElementId(row.get("to").asText()),
              DependencyType.valueOf(row.get("type").asText()),
              ResolutionQuality.valueOf(row.get("resolution").asText()),
              row.get("occurrences").asInt()));
    }
    return new GraphInput(types, rows);
  }

  static List<CodeElementId> ids(JsonNode array) {
    final List<CodeElementId> ids = new ArrayList<>();
    for (final JsonNode id : array) {
      ids.add(new CodeElementId(id.asText()));
    }
    return ids;
  }
}
