package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.api.Git;

final class SourceTrees {

  static final BooleanSupplier NEVER = () -> false;

  private SourceTrees() {}

  static ValidatedRepository repository(Path directory, Map<String, String> files)
      throws Exception {
    for (final Map.Entry<String, String> file : files.entrySet()) {
      TestRepositories.write(directory, file.getKey(), file.getValue());
    }
    try (Git git = TestRepositories.init(directory)) {
      TestRepositories.commitAll(git, "fontes\n");
    }
    return validated(directory);
  }

  static ValidatedRepository fixture(String fixture, Path directory) throws Exception {
    TestRepositories.reproduceCorpusFixture(fixture, directory);
    return validated(directory);
  }

  static ValidatedRepository validated(Path directory) throws Exception {
    HermeticSystemReader.install();
    final RepositoryOutcome<ValidatedRepository> outcome =
        new RepositoryValidator().validate(directory.toRealPath());
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<ValidatedRepository>) outcome).value();
  }
}
