package br.pucpr.tcc.strata.analyzer.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

public record RuntimeArguments(Path dataDir) {

  private static final String DATA_DIR = "--data-dir=";

  public RuntimeArguments {
    Objects.requireNonNull(dataDir, "dataDir");
  }

  public static RuntimeArguments parse(String[] argv) {
    Path dataDir = null;
    for (final String argument : argv) {
      if (argument.startsWith(DATA_DIR)) {
        dataDir = Path.of(argument.substring(DATA_DIR.length())).normalize();
      } else {
        throw new IllegalArgumentException("argumento desconhecido: " + argument);
      }
    }
    if (dataDir == null) {
      throw new IllegalArgumentException("--data-dir=<path> é obrigatório");
    }
    if (!dataDir.isAbsolute()) {
      throw new IllegalArgumentException("--data-dir deve ser absoluto, veio: " + dataDir);
    }
    return new RuntimeArguments(dataDir);
  }
}
