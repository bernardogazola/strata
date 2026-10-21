package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.analysis.RunFixtures.ManualNanoClock;
import br.pucpr.tcc.strata.analyzer.repository.HermeticSystemReader;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryOutcome;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryValidator;
import br.pucpr.tcc.strata.analyzer.repository.SourceRoot;
import br.pucpr.tcc.strata.analyzer.repository.TestRepositories;
import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.BatchVisitor;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.ModeledBatch;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceAnalysis;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFile;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SpoonModelBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceProgressVisitorTest {

  private static final AnalysisId ID = RunFixtures.FIXTURE_ID;

  @TempDir Path pasta;

  @BeforeAll
  static void isolarOJGit() {
    HermeticSystemReader.install();
  }

  private record Marcador(List<String> marcas) implements BatchVisitor {

    @Override
    public void begin(List<SourceFile> files) {
      marcas.add("begin");
    }

    @Override
    public void visit(ModeledBatch batch) {
      marcas.add("visit " + batch.files().size());
    }

    @Override
    public void end(SourceAnalysis analysis) {
      marcas.add("end");
    }
  }

  private ValidatedRepository repositorio() throws Exception {
    final Path raiz = pasta.toRealPath().resolve("repo");
    TestRepositories.write(raiz, "a/src/main/java/p/A1.java", "package p;\nclass A1 {}\n");
    TestRepositories.write(raiz, "a/src/main/java/p/A2.java", "package p;\nclass A2 {}\n");
    TestRepositories.write(
        raiz, "a/src/main/java/p/Quebrado.java", "package p;\nclass Quebrado {\n");
    TestRepositories.write(raiz, "b/src/main/java/q/B1.java", "package q;\nclass B1 {}\n");
    try (var git = TestRepositories.init(raiz)) {
      TestRepositories.commitAll(git, "duas raizes\n");
    }
    final RepositoryOutcome<ValidatedRepository> desfecho =
        new RepositoryValidator().validate(raiz);
    assertThat(desfecho).isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<ValidatedRepository>) desfecho).value();
  }

  @Test
  void contaOsSeladosNoBeginCadaLoteDepoisDeVoltarEOTerminalNoFim() throws Exception {
    final ValidatedRepository repositorio = repositorio();
    final List<SourceRoot> raizes = repositorio.layout().sourceRoots();
    final long total = raizes.stream().mapToLong(SourceRoot::javaFileCount).sum();
    final List<String> marcas = new ArrayList<>();
    final ProgressReporter progresso =
        new ProgressReporter(
            ID,
            AnalysisStage.SOURCE_ANALYSIS,
            new ProgressThrottle(
                ManualNanoClock.stepping(Duration.ofMillis(150)), Duration.ofMillis(100)),
            passo -> marcas.add("tick " + passo.completed() + "/" + passo.total()));

    final SourceAnalysis analise =
        new SpoonModelBuilder()
            .analyze(
                repositorio,
                raizes,
                new SourceProgressVisitor(new Marcador(marcas), progresso, total),
                () -> false);

    assertThat(total).as("anti-vacuidade: as duas raízes e os quatro arquivos").isEqualTo(4);
    assertThat(
            analise.files().stream().filter(f -> f.status() != SourceFileStatus.ANALYZED).count())
        .as("anti-vacuidade: o arquivo quebrado não é ANALYZED")
        .isEqualTo(1);
    assertThat(marcas)
        .as(
            "begin conta o selado; cada lote conta depois de o visitante voltar; o terminal sai"
                + " depois do end, (total, total)")
        .containsExactly(
            "begin", "tick 1/4", "visit 2", "tick 3/4", "visit 1", "tick 4/4", "end", "tick 4/4");
  }
}
