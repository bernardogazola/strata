package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.util.Objects;

final class IndexedType {

  private static final String[] NONE = new String[0];

  private final String id;
  private final String simpleName;
  private final String packageName;
  private final TypeKind kind;
  private final byte modifiers;
  private final boolean topLevel;
  private final boolean structured;
  private final MemberTable methods;
  private final MemberTable fields;
  private String[] supertypes;
  private String[] fieldTypes;
  private boolean complete;

  IndexedType(
      String id,
      String simpleName,
      String packageName,
      boolean topLevel,
      UnitShape.TypeShape shape) {
    this.id = Objects.requireNonNull(id, "id");
    this.simpleName = Objects.requireNonNull(simpleName, "simpleName");
    this.packageName = Objects.requireNonNull(packageName, "packageName");
    this.topLevel = topLevel;
    this.structured = shape != null;
    this.kind = shape == null ? TypeKind.CLASS : shape.kind();
    this.modifiers = shape == null ? (byte) MemberTable.PUBLIC : shape.modifiers();
    this.methods = shape == null ? MemberTable.EMPTY : shape.methods();
    this.fields = shape == null ? MemberTable.EMPTY : shape.fields();
    this.supertypes = shape == null ? NONE : null;
    this.fieldTypes = shape == null ? NONE : null;
  }

  String id() {
    return id;
  }

  String simpleName() {
    return simpleName;
  }

  String packageName() {
    return packageName;
  }

  TypeKind kind() {
    return kind;
  }

  boolean isInterface() {
    return kind == TypeKind.INTERFACE || kind == TypeKind.ANNOTATION;
  }

  int visibility() {
    return MemberTable.visibility(modifiers);
  }

  boolean isStatic() {
    return MemberTable.isStatic(modifiers);
  }

  boolean isTopLevel() {
    return topLevel;
  }

  boolean structured() {
    return structured;
  }

  MemberTable methods() {
    return methods;
  }

  MemberTable fields() {
    return fields;
  }

  String[] supertypes() {
    return supertypes;
  }

  void supertypes(String[] resolved) {
    if (supertypes != null) {
      throw new IllegalStateException("os supertipos de " + id + " já foram resolvidos");
    }
    supertypes = resolved;
  }

  String[] fieldTypes() {
    return fieldTypes;
  }

  void fieldTypes(String[] resolved) {
    if (fieldTypes != null) {
      throw new IllegalStateException("os tipos de campo de " + id + " já foram resolvidos");
    }
    fieldTypes = resolved;
  }

  boolean complete() {
    return complete;
  }

  void complete(boolean value) {
    complete = value;
  }
}
