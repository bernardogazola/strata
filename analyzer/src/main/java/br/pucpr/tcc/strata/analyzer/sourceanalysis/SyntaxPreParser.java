package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
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

  static PreParse parse(String fileName, char[] content) {
    Objects.requireNonNull(fileName, "fileName");
    Objects.requireNonNull(content, "content");
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
      return new PreParse(true, false, moduleDescriptor, null);
    }
    final boolean empty = declaration.isEmpty() && declaration.moduleDeclaration == null;
    return new PreParse(false, empty, moduleDescriptor, declarations(declaration));
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

  record PreParse(
      boolean syntaxError,
      boolean empty,
      boolean moduleDescriptor,
      SourceFile.Declarations declarations) {

    PreParse {
      if (syntaxError != (declarations == null)) {
        throw new IllegalArgumentException(
            "as declarações faltam se e somente se o arquivo tem erro de sintaxe");
      }
    }
  }
}
