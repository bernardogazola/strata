package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.AbstractVariableDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ImportReference;
import org.eclipse.jdt.internal.compiler.ast.RecordComponent;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeParameter;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;

final class SyntaxPreParser {

  private static final String JAVA_LEVEL = String.valueOf(SpoonModelBuilder.COMPLIANCE_LEVEL);

  private static final Map<String, String> SETTINGS =
      Map.of(
          CompilerOptions.OPTION_Compliance, JAVA_LEVEL,
          CompilerOptions.OPTION_Source, JAVA_LEVEL,
          CompilerOptions.OPTION_DocCommentSupport, CompilerOptions.ENABLED);

  private SyntaxPreParser() {}

  private static final List<String> ENUM_METHODS = List.of("values", "valueOf");

  private static final String DIMENSION = "[]";

  static PreParse parse(String fileName, char[] content) {
    return parse(fileName, content, UnaryOperator.identity());
  }

  static PreParse parse(String fileName, char[] content, UnaryOperator<String> interner) {
    Objects.requireNonNull(fileName, "fileName");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(interner, "interner");
    final CompilerOptions options = options();
    final Parser parser =
        new Parser(
            new ProblemReporter(
                DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                options,
                new DefaultProblemFactory(Locale.ROOT)),
            false);
    final CompilationUnit unit = new CompilationUnit(content, fileName, null);
    final CompilationUnitDeclaration declaration =
        parser.parse(unit, new CompilationResult(unit, 0, 1, options.maxProblemsPerUnit));
    final boolean moduleDescriptor = declaration.isModuleInfo();
    if (declaration.hasErrors()) {
      return new PreParse(true, false, moduleDescriptor, null, null);
    }
    final boolean empty = declaration.isEmpty() && declaration.moduleDeclaration == null;
    return new PreParse(
        false, empty, moduleDescriptor, declarations(declaration), shape(declaration, interner));
  }

  private static CompilerOptions options() {
    final CompilerOptions options = new CompilerOptions(SETTINGS);
    options.parseLiteralExpressionsAsConstants = false;
    return options;
  }

  private static SourceFile.Declarations declarations(CompilationUnitDeclaration declaration) {
    final String packageName =
        declaration.currentPackage == null
            ? ""
            : CharOperation.toString(declaration.currentPackage.tokens);
    final List<String> topLevelTypes =
        declaration.types == null
            ? List.of()
            : Arrays.stream(declaration.types)
                .filter(type -> !type.isPackageInfo())
                .map(type -> new String(type.name))
                .toList();
    return new SourceFile.Declarations(packageName, topLevelTypes);
  }

  private static UnitShape shape(
      CompilationUnitDeclaration declaration, UnaryOperator<String> interner) {
    final String packageName =
        declaration.currentPackage == null
            ? ""
            : interner.apply(CharOperation.toString(declaration.currentPackage.tokens));
    final List<String> singleType = new ArrayList<>();
    final List<String> onDemand = new ArrayList<>();
    final List<String> singleStatic = new ArrayList<>();
    final List<String> staticOnDemand = new ArrayList<>();
    boolean moduleImport = false;
    for (final ImportReference imported :
        declaration.imports == null ? new ImportReference[0] : declaration.imports) {
      final String text = interner.apply(CharOperation.toString(imported.tokens));
      final boolean star = (imported.bits & ASTNode.OnDemand) != 0;
      if ((imported.modifiers & ClassFileConstants.AccModule) != 0) {
        moduleImport = true;
      } else if (imported.isStatic()) {
        (star ? staticOnDemand : singleStatic).add(text);
      } else {
        (star ? onDemand : singleType).add(text);
      }
    }
    final List<UnitShape.TypeShape> types = new ArrayList<>();
    for (final TypeDeclaration type :
        declaration.types == null ? new TypeDeclaration[0] : declaration.types) {
      if (!type.isPackageInfo()) {
        types.add(typeShape(type, false, interner));
      }
    }
    return new UnitShape(
        packageName, singleType, onDemand, singleStatic, staticOnDemand, moduleImport, types);
  }

  private static UnitShape.TypeShape typeShape(
      TypeDeclaration type, boolean interfaceMember, UnaryOperator<String> interner) {
    final TypeKind kind = kind(type);
    final boolean isInterface = kind == TypeKind.INTERFACE || kind == TypeKind.ANNOTATION;
    final boolean implicitlyStatic = interfaceMember || isInterface || kind != TypeKind.CLASS;
    int modifiers = visibility(type.modifiers, interfaceMember);
    if (implicitlyStatic || (type.modifiers & ClassFileConstants.AccStatic) != 0) {
      modifiers |= MemberTable.STATIC;
    }
    final String name = interner.apply(new String(type.name));
    final TreeMap<String, Byte> methods = new TreeMap<>();
    final TreeMap<String, Byte> fields = new TreeMap<>();
    final Map<String, String> fieldTypes = new TreeMap<>();
    methods(type, isInterface, methods, interner);
    fields(type, name, isInterface, fields, fieldTypes, interner);
    for (final RecordComponent component : components(type)) {
      MemberTable.put(
          methods,
          interner.apply(new String(component.name)),
          (byte) (MemberTable.PUBLIC | MemberTable.INSTANCE));
    }
    if (kind == TypeKind.ENUM) {
      for (final String implicit : ENUM_METHODS) {
        MemberTable.put(methods, implicit, (byte) (MemberTable.PUBLIC | MemberTable.STATIC));
      }
    }
    final List<UnitShape.TypeShape> members = new ArrayList<>();
    for (final TypeDeclaration member :
        type.memberTypes == null ? new TypeDeclaration[0] : type.memberTypes) {
      members.add(typeShape(member, isInterface, interner));
    }
    return new UnitShape.TypeShape(
        name,
        kind,
        (byte) modifiers,
        typeParameters(type.typeParameters, interner),
        written(type.superclass, interner),
        written(type.superInterfaces, interner),
        MemberTable.of(methods),
        MemberTable.of(fields),
        List.copyOf(fieldTypes.values()),
        members);
  }

  private static void methods(
      TypeDeclaration type,
      boolean isInterface,
      TreeMap<String, Byte> methods,
      UnaryOperator<String> interner) {
    for (final AbstractMethodDeclaration method :
        type.methods == null ? new AbstractMethodDeclaration[0] : type.methods) {
      if (method.isConstructor() || method.isClinit() || method.selector == null) {
        continue;
      }
      final int presence =
          (method.modifiers & ClassFileConstants.AccStatic) != 0
              ? MemberTable.STATIC
              : MemberTable.INSTANCE;
      MemberTable.put(
          methods,
          interner.apply(new String(method.selector)),
          (byte) (visibility(method.modifiers, isInterface) | presence));
    }
  }

  private static void fields(
      TypeDeclaration type,
      String typeName,
      boolean isInterface,
      TreeMap<String, Byte> fields,
      Map<String, String> fieldTypes,
      UnaryOperator<String> interner) {
    for (final FieldDeclaration field :
        type.fields == null ? new FieldDeclaration[0] : type.fields) {
      if (field.getKind() == AbstractVariableDeclaration.INITIALIZER || field.name == null) {
        continue;
      }
      final boolean constant = field.getKind() == AbstractVariableDeclaration.ENUM_CONSTANT;
      final boolean isStatic =
          constant || isInterface || (field.modifiers & ClassFileConstants.AccStatic) != 0;
      final int visibility =
          constant ? MemberTable.PUBLIC : visibility(field.modifiers, isInterface);
      final String name = interner.apply(new String(field.name));
      MemberTable.put(
          fields,
          name,
          (byte) (visibility | (isStatic ? MemberTable.STATIC : MemberTable.INSTANCE)));
      fieldTypes.putIfAbsent(name, constant ? typeName : written(field.type, interner));
    }
    for (final RecordComponent component : components(type)) {
      final String name = interner.apply(new String(component.name));
      MemberTable.put(fields, name, (byte) (MemberTable.PRIVATE | MemberTable.INSTANCE));
      fieldTypes.putIfAbsent(name, written(component.type, interner));
    }
  }

  private static RecordComponent[] components(TypeDeclaration type) {
    return type.recordComponents == null ? new RecordComponent[0] : type.recordComponents;
  }

  private static TypeKind kind(TypeDeclaration type) {
    return switch (TypeDeclaration.kind(type.modifiers)) {
      case TypeDeclaration.INTERFACE_DECL -> TypeKind.INTERFACE;
      case TypeDeclaration.ENUM_DECL -> TypeKind.ENUM;
      case TypeDeclaration.ANNOTATION_TYPE_DECL -> TypeKind.ANNOTATION;
      case TypeDeclaration.RECORD_DECL -> TypeKind.RECORD;
      default -> TypeKind.CLASS;
    };
  }

  private static int visibility(int modifiers, boolean interfaceMember) {
    if ((modifiers & ClassFileConstants.AccPrivate) != 0) {
      return MemberTable.PRIVATE;
    }
    if (interfaceMember || (modifiers & ClassFileConstants.AccPublic) != 0) {
      return MemberTable.PUBLIC;
    }
    return (modifiers & ClassFileConstants.AccProtected) != 0
        ? MemberTable.PROTECTED
        : MemberTable.PACKAGE;
  }

  private static List<String> typeParameters(
      TypeParameter[] parameters, UnaryOperator<String> interner) {
    if (parameters == null) {
      return List.of();
    }
    final List<String> names = new ArrayList<>(parameters.length);
    for (final TypeParameter parameter : parameters) {
      names.add(interner.apply(new String(parameter.name)));
    }
    return names;
  }

  private static String written(TypeReference reference, UnaryOperator<String> interner) {
    if (reference == null) {
      return null;
    }
    final String name = CharOperation.toString(reference.getTypeName());
    final int dimensions = reference.dimensions();
    return interner.apply(dimensions == 0 ? name : name + DIMENSION.repeat(dimensions));
  }

  private static List<String> written(TypeReference[] references, UnaryOperator<String> interner) {
    if (references == null) {
      return List.of();
    }
    final List<String> names = new ArrayList<>(references.length);
    for (final TypeReference reference : references) {
      names.add(written(reference, interner));
    }
    return names;
  }

  record PreParse(
      boolean syntaxError,
      boolean empty,
      boolean moduleDescriptor,
      SourceFile.Declarations declarations,
      UnitShape shape) {

    PreParse {
      if (syntaxError != (declarations == null) || syntaxError != (shape == null)) {
        throw new IllegalArgumentException(
            "as declarações e a forma faltam se e somente se o arquivo tem erro de sintaxe");
      }
    }
  }
}
