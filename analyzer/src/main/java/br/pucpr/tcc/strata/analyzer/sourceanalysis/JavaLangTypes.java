package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.util.HashMap;
import java.util.Map;

final class JavaLangTypes {

  private static final String JAVA_LANG = "java.lang.";

  private final Map<String, Boolean> known = new HashMap<>();

  String qualify(String simpleName) {
    if (!TargetNames.isQualifiedIdentifier(simpleName) || simpleName.indexOf('.') >= 0) {
      return null;
    }
    return known.computeIfAbsent(simpleName, JavaLangTypes::exists) ? JAVA_LANG + simpleName : null;
  }

  private static boolean exists(String simpleName) {
    try {
      Class.forName(JAVA_LANG + simpleName, false, null);
      return true;
    } catch (ClassNotFoundException | LinkageError absent) {
      return false;
    }
  }
}
