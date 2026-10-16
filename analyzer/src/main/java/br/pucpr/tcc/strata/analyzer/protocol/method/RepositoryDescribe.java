package br.pucpr.tcc.strata.analyzer.protocol.method;

import br.pucpr.tcc.strata.analyzer.bootstrap.Log;
import br.pucpr.tcc.strata.analyzer.protocol.Envelope;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.MethodHandler;
import br.pucpr.tcc.strata.analyzer.protocol.RequestRejected;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class RepositoryDescribe implements MethodHandler<MethodHandler.EmptyParams> {

  static final String METHOD = "repository.describe";

  private final RepositoryValidator validator;

  public RepositoryDescribe(RepositoryValidator validator) {
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  @Override
  public String method() {
    return METHOD;
  }

  @Override
  public Class<MethodHandler.EmptyParams> paramsType() {
    return MethodHandler.EmptyParams.class;
  }

  @Override
  public Object handle(MethodHandler.EmptyParams params, Envelope.Request request) {
    final Path claimed = claimedRoot(request.repositoryPath());
    return switch (validator.validate(claimed)) {
      case RepositoryOutcome.Rejected<ValidatedRepository> rejected -> {
        Log.info(METHOD + ": recusou " + claimed + " com " + rejected.reason().code());
        throw new RequestRejected(ErrorCodes.repository(rejected.reason()));
      }
      case RepositoryOutcome.Accepted<ValidatedRepository> accepted -> {
        logWarnings(accepted.warnings());
        yield RepositoryDescription.of(accepted.value());
      }
    };
  }

  private static Path claimedRoot(String wire) {
    if (wire == null) {
      throw invalid("repositoryPath é obrigatório");
    }
    final Path claimed;
    try {
      claimed = Path.of(wire);
    } catch (InvalidPathException malformed) {
      throw invalid("repositoryPath não é um caminho válido");
    }
    if (!claimed.isAbsolute() || !claimed.equals(claimed.normalize())) {
      throw invalid("repositoryPath deve ser absoluto e normalizado");
    }
    return claimed;
  }

  private static RequestRejected invalid(String detail) {
    return new RequestRejected(ErrorCodes.invalidPayload(METHOD, detail));
  }

  private static void logWarnings(List<RepositoryIssue> warnings) {
    for (final RepositoryIssue warning : warnings) {
      Log.warn(METHOD + ": " + warning.code() + " " + warning.params());
    }
  }
}
