package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import java.util.Set;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

final class DependencySites {

  private final OccurrenceRecorder recorder;
  private final CallTargets calls;
  private final Set<Long> fieldDeclarations;

  DependencySites(OccurrenceRecorder recorder, CallTargets calls, Set<Long> fieldDeclarations) {
    this.recorder = recorder;
    this.calls = calls;
    this.fieldDeclarations = fieldDeclarations;
  }

  void scan(CtType<?> top) {
    for (final CtElement element : top.getElements(new TypeFilter<>(CtElement.class))) {
      visit(element);
    }
  }

  private void visit(CtElement element) {
    switch (element) {
      case CtInvocation<?> invocation when !invocation.isImplicit() -> invocation(invocation);
      case CtConstructorCall<?> call when CallTargets.isSite(call) -> constructorCall(call);
      case CtEnumValue<?> _ -> {}
      case CtField<?> field when !field.isImplicit() -> field(field);
      case CtRecordComponent component ->
          recorder.declaration(component, component.getType(), DependencyType.FIELD_TYPE);
      case CtMethod<?> method when !method.isImplicit() -> method(method);
      case CtConstructor<?> constructor when !constructor.isImplicit() -> parameters(constructor);
      case CtAnnotation<?> annotation when countsAnnotation(annotation) ->
          recorder.reference(
              annotation, annotation.getAnnotationType(), DependencyType.ANNOTATION_REFERENCE);
      case CtTypeParameter _ -> {}
      case CtType<?> type when ElementIds.isElementType(type) -> supertypes(type);
      default -> {}
    }
  }

  private void invocation(CtInvocation<?> invocation) {
    final CodeElementId source = recorder.source(invocation);
    if (source != null) {
      recorder.call(invocation, source, calls.of(invocation, source));
    }
  }

  private void constructorCall(CtConstructorCall<?> call) {
    final CodeElementId source = recorder.source(call);
    if (source != null) {
      recorder.call(call, source, calls.of(call));
    }
  }

  private void field(CtField<?> field) {
    final CtTypeReference<?> type = field.getType();
    if (type == null) {
      return;
    }
    final SourcePosition position = type.getPosition();
    if (position.isValidPosition()
        && !fieldDeclarations.add(
            ((long) position.getSourceStart() << Integer.SIZE) | position.getSourceEnd())) {
      return;
    }
    recorder.declaration(field, type, DependencyType.FIELD_TYPE);
  }

  private void method(CtMethod<?> method) {
    final CtTypeReference<?> returned = method.getType();
    if (returned != null) {
      recorder.declaration(returned, returned, DependencyType.RETURN_TYPE);
    }
    parameters(method);
  }

  private void parameters(CtExecutable<?> executable) {
    if (executable instanceof CtConstructor<?> constructor && constructor.isCompactConstructor()) {
      return;
    }
    for (final CtParameter<?> parameter : executable.getParameters()) {
      if (!parameter.isImplicit()) {
        recorder.declaration(parameter, parameter.getType(), DependencyType.PARAMETER_TYPE);
      }
    }
  }

  private void supertypes(CtType<?> type) {
    if (type instanceof CtClass<?> && !(type instanceof CtEnum<?>) && !(type instanceof CtRecord)) {
      final CtTypeReference<?> superclass = type.getSuperclass();
      if (superclass != null && superclass.getPosition().isValidPosition()) {
        recorder.reference(superclass, superclass, DependencyType.EXTENDS);
      }
    }
    final DependencyType kind =
        type instanceof CtInterface<?> ? DependencyType.EXTENDS : DependencyType.IMPLEMENTS;
    for (final CtTypeReference<?> superInterface : type.getSuperInterfaces()) {
      if (!superInterface.isImplicit()) {
        recorder.reference(superInterface, superInterface, kind);
      }
    }
  }

  private static boolean countsAnnotation(CtAnnotation<?> annotation) {
    if (annotation.isImplicit() || !annotation.isParentInitialized()) {
      return false;
    }
    return switch (annotation.getParent()) {
      case CtTypeParameter _ -> false;
      case CtType<?> _, CtRecordComponent _ -> true;
      case CtField<?> field -> !field.isImplicit();
      case CtMethod<?> method -> !method.isImplicit();
      case CtConstructor<?> constructor -> !constructor.isImplicit();
      case CtParameter<?> parameter -> isCountedParameter(parameter);
      default -> false;
    };
  }

  private static boolean isCountedParameter(CtParameter<?> parameter) {
    if (parameter.isImplicit() || !parameter.isParentInitialized()) {
      return false;
    }
    return switch (parameter.getParent()) {
      case CtLambda<?> _ -> false;
      case CtConstructor<?> constructor -> !constructor.isCompactConstructor();
      case CtMethod<?> _ -> true;
      default -> false;
    };
  }
}
