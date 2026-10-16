package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RepositoryValidatorTest {

  @TempDir private Path temp;

  private Path real;

  @BeforeAll
  static void hermetico() {
    HermeticSystemReader.install();
  }

  @BeforeEach
  void caminhoReal() throws Exception {
    real = temp.toRealPath();
  }

  private static RepositoryOutcome.Accepted<ValidatedRepository> aceito(
      RepositoryOutcome<ValidatedRepository> outcome) {
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return (RepositoryOutcome.Accepted<ValidatedRepository>) outcome;
  }

  private static List<String> raizes(RepositoryOutcome.Accepted<ValidatedRepository> accepted) {
    return accepted.value().layout().sourceRoots().stream().map(SourceRoot::relativePath).toList();
  }

  @Test
  void umSymlinkQueApontaParaForaNaoESeguidoEViraAvisoEnquantoAVarreduraContinua()
      throws Exception {
    final Path fora = real.resolve("fora");
    TestRepositories.write(fora, "Segredo.java", "package fora; class Segredo {}");
    final Path repo = real.resolve("repo");
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/a/A.java", "package a; class A {}");
      TestRepositories.commitAll(git, "primeiro");
    }
    TestRepositories.symlink(
        repo.resolve("src/main/java/a/Fuga.java"), fora.resolve("Segredo.java"));
    TestRepositories.symlink(repo.resolve("vazamento"), fora);
    TestRepositories.write(repo, "src/main/java/b/B.java", "package b; class B {}");

    final var accepted = aceito(new RepositoryValidator().validate(repo));

    assertThat(accepted.warnings())
        .contains(
            RepositoryIssue.pathEscapesRoot("src/main/java/a/Fuga.java"),
            RepositoryIssue.pathEscapesRoot("vazamento"));
    assertThat(accepted.value().snapshot().inventory().javaSources())
        .extracting(JavaSource::relativePath)
        .as("o link não vira fonte e o alvo nunca é lido; a varredura seguiu até b/B.java")
        .containsExactly("src/main/java/a/A.java", "src/main/java/b/B.java");
    assertThat(accepted.value().layout().sourceRoots())
        .singleElement()
        .extracting(SourceRoot::javaFileCount)
        .isEqualTo(2);
  }

  @Test
  void repositorioBareERecusadoComRepoIsBare() throws Exception {
    final Path bare = TestRepositories.bare(real.resolve("bare.git"));

    assertThat(new RepositoryValidator().validate(bare))
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.isBare()));
  }

  @Test
  void repositorioSemJavaERecusadoComRepoNoJavaSources() throws Exception {
    final Path repo = real.resolve("sem-java");
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "README.md", "# sem java\n");
      TestRepositories.commitAll(git, "primeiro");
    }

    assertThat(new RepositoryValidator().validate(repo))
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.noJavaSources()));
  }

  @Test
  void acimaDoTetoDeArquivosERecusadoComOLimiteEOPisoEncontrado() throws Exception {
    final Path repo = real.resolve("grande");
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      TestRepositories.write(repo, "um.txt", "1");
      TestRepositories.write(repo, "dois.txt", "2");
      TestRepositories.write(repo, "tres.txt", "3");
      TestRepositories.commitAll(git, "primeiro");
    }
    final RepositoryLimits tres =
        new RepositoryLimits(
            3,
            RepositoryLimits.PRODUCTION.maxDirectoryDepth(),
            RepositoryLimits.PRODUCTION.maxPathBytes(),
            RepositoryLimits.PRODUCTION.maxBuildFileBytes(),
            RepositoryLimits.PRODUCTION.maxGitConfigBytes(),
            RepositoryLimits.PRODUCTION.maxWarnings());

    assertThat(new RepositoryValidator(tres).validate(repo))
        .as("a varredura para na quarta entrada: found é o piso, nunca a contagem total")
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.tooManyFiles(3, 4)));
  }

  @Test
  void headNaoNascidoAvisaRepoNoHistoryEAEstruturaSegue() throws Exception {
    final Path repo = real.resolve("recem-criado");
    TestRepositories.init(repo).close();
    TestRepositories.write(repo, "src/main/java/A.java", "class A {}");

    final var accepted = aceito(new RepositoryValidator().validate(repo));

    assertThat(accepted.warnings()).contains(RepositoryIssue.noHistory());
    assertThat(accepted.value().snapshot().head())
        .as("o branch é o que vai nascer; o commit não existe")
        .isEqualTo(new HeadState(null, "main", false));
    assertThat(raizes(accepted)).containsExactly("src/main/java");
  }

  @Test
  void pontoGitComoArquivoDeWorktreeLigadoEAceito() throws Exception {
    final Path principal = real.resolve("principal");
    final RevCommit commit;
    try (Git git = TestRepositories.init(principal)) {
      TestRepositories.write(principal, "src/main/java/A.java", "class A {}");
      commit = TestRepositories.commitAll(git, "primeiro");
      git.branchCreate().setName("recurso").call();
    }
    final Path worktree =
        TestRepositories.linkedWorktree(principal, real.resolve("worktree"), "recurso");
    TestRepositories.write(worktree, "src/main/java/A.java", "class A {}");

    final var accepted = aceito(new RepositoryValidator().validate(worktree));

    assertThat(Files.isRegularFile(worktree.resolve(".git"))).isTrue();
    assertThat(accepted.value().snapshot().head())
        .isEqualTo(new HeadState(commit.name(), "recurso", false));
    assertThat(accepted.value().snapshot().displayName()).isEqualTo("worktree");
    assertThat(raizes(accepted)).containsExactly("src/main/java");
  }

  @Test
  void caminhoQueNaoResolveMaisParaAMesmaPastaERecusadoComRepoPathChanged() throws Exception {
    final Path repo = real.resolve("repo");
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      TestRepositories.commitAll(git, "primeiro");
    }
    final Path apelido = TestRepositories.symlink(real.resolve("apelido"), repo);

    final RepositoryValidator validator = new RepositoryValidator();
    assertThat(validator.validate(apelido))
        .as("o caminho canonizado nunca tem symlink: ter um agora é troca depois da abertura")
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.pathChanged()));
    assertThat(validator.validate(real.resolve("sumiu")))
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.pathChanged()));
  }

  @Test
  void pastaSemGitOuSubpastaDeUmRepositorioERecusadaSemSubirNaArvore() throws Exception {
    final Path solta = real.resolve("solta");
    TestRepositories.write(solta, "src/main/java/A.java", "class A {}");
    final Path repo = real.resolve("repo");
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      TestRepositories.commitAll(git, "primeiro");
    }

    final RepositoryValidator validator = new RepositoryValidator();
    assertThat(validator.validate(solta))
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.notAGitRepository()));
    assertThat(validator.validate(repo.resolve("src")))
        .as("subir até o .git do pai analisaria uma pasta que o handle nunca autorizou")
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.notAGitRepository()));
  }
}
