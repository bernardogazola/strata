package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.RequestRejected;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class ClaimedRoot {

  private ClaimedRoot() {}

  static Path of(String method, String wire) {
    if (wire == null) {
      throw invalid(method, "repositoryPath é obrigatório");
    }
    final Path claimed;
    try {
      claimed = Path.of(wire);
    } catch (InvalidPathException malformed) {
      throw invalid(method, "repositoryPath não é um caminho válido");
    }
    if (!claimed.isAbsolute() || !claimed.equals(claimed.normalize())) {
      throw invalid(method, "repositoryPath deve ser absoluto e normalizado");
    }
    return claimed;
  }

  private static RequestRejected invalid(String method, String detail) {
    return new RequestRejected(ErrorCodes.invalidPayload(method, detail));
  }
}
