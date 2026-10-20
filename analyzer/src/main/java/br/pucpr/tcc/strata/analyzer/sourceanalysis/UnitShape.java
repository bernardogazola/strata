package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.util.List;
import java.util.Objects;

record UnitShape(
    String packageName,
    List<String> singleTypeImports,
    List<String> onDemandImports,
    List<String> singleStaticImports,
    List<String> staticOnDemandImports,
    boolean moduleImport,
    List<TypeShape> types) {

  UnitShape {
    Objects.requireNonNull(packageName, "packageName");
    singleTypeImports = List.copyOf(singleTypeImports);
    onDemandImports = List.copyOf(onDemandImports);
    singleStaticImports = List.copyOf(singleStaticImports);
    staticOnDemandImports = List.copyOf(staticOnDemandImports);
    types = List.copyOf(types);
  }

  record TypeShape(
      String name,
      TypeKind kind,
      byte modifiers,
      List<String> typeParameters,
      String superclass,
      List<String> superInterfaces,
      MemberTable methods,
      MemberTable fields,
      List<String> fieldTypes,
      List<TypeShape> memberTypes) {

    TypeShape {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(kind, "kind");
      typeParameters = List.copyOf(typeParameters);
      superInterfaces = List.copyOf(superInterfaces);
      Objects.requireNonNull(methods, "methods");
      Objects.requireNonNull(fields, "fields");
      fieldTypes = List.copyOf(fieldTypes);
      memberTypes = List.copyOf(memberTypes);
      if (fieldTypes.size() != fields.size()) {
        throw new IllegalArgumentException(
            "um tipo escrito por campo: " + fields.size() + " / " + fieldTypes.size());
      }
    }

    boolean isInterface() {
      return kind == TypeKind.INTERFACE || kind == TypeKind.ANNOTATION;
    }
  }
}
