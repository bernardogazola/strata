package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.CodeElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.FileElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.MethodElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.PackageElement;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

final class RecordingElementSink implements ElementSink {

  private final List<SourceFile> begun = new ArrayList<>();
  private final List<BatchElements> accepted = new ArrayList<>();
  private final List<SourceFile> ended = new ArrayList<>();
  private final List<FileElement> fileElements = new ArrayList<>();
  private int ends;

  @Override
  public void begin(List<SourceFile> files) {
    begun.addAll(files);
  }

  @Override
  public void accept(BatchElements elements) {
    accepted.add(elements);
  }

  @Override
  public void end(List<SourceFile> files, List<FileElement> elements) {
    ends++;
    ended.addAll(files);
    fileElements.addAll(elements);
  }

  List<SourceFile> begun() {
    return List.copyOf(begun);
  }

  List<BatchElements> accepted() {
    return List.copyOf(accepted);
  }

  List<SourceFile> ended() {
    return List.copyOf(ended);
  }

  int ends() {
    return ends;
  }

  List<PackageElement> packages() {
    return sorted(BatchElements::packages);
  }

  List<TypeElement> types() {
    return sorted(BatchElements::types);
  }

  List<MethodElement> methods() {
    return sorted(BatchElements::methods);
  }

  List<FileElement> fileElements() {
    return List.copyOf(fileElements);
  }

  TypeElement type(String id) {
    return types().stream().filter(type -> type.id().value().equals(id)).findFirst().orElseThrow();
  }

  MethodElement method(String id) {
    return methods().stream()
        .filter(method -> method.id().value().equals(id))
        .findFirst()
        .orElseThrow();
  }

  FileElement file(String id) {
    return fileElements.stream()
        .filter(file -> file.id().value().equals(id))
        .findFirst()
        .orElseThrow();
  }

  static List<String> ids(List<? extends CodeElement> elements) {
    return elements.stream().map(element -> element.id().value()).toList();
  }

  private <T extends CodeElement> List<T> sorted(Function<BatchElements, List<T>> section) {
    return accepted.stream()
        .flatMap(batch -> section.apply(batch).stream())
        .sorted(Comparator.comparing(CodeElement::id))
        .toList();
  }
}
