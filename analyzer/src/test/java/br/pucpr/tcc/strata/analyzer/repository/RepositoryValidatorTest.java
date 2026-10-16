package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
            RepositoryLimits.PRODUCTION.maxWarnings(),
            RepositoryLimits.PRODUCTION.maxPackedRefsBytes());

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

  private static RepositoryOutcome.Accepted<RepositorySnapshot> inspecionado(
      RepositoryOutcome<RepositorySnapshot> outcome) {
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return (RepositoryOutcome.Accepted<RepositorySnapshot>) outcome;
  }

  private Path repositorioComUmCommit(String nome) throws Exception {
    final Path repo = real.resolve(nome);
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      TestRepositories.commitAll(git, "primeiro");
    }
    return repo;
  }

  @Test
  void escolherAPropriaPastaPontoGitERecusado() throws Exception {
    final Path repo = repositorioComUmCommit("repo");

    assertThat(new RepositoryValidator().inspect(repo.resolve(".git")))
        .as("o JGit abre o .git e põe a árvore de trabalho no pai, que o handle não autorizou")
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.notAGitRepository()));
  }

  @Test
  void pontoGitComCoreBareVerdadeiroERecusadoComoBare() throws Exception {
    final Path repo = repositorioComUmCommit("core-bare");
    try (Git git = Git.open(repo.toFile())) {
      final StoredConfig config = git.getRepository().getConfig();
      config.setBoolean("core", null, "bare", true);
      config.save();
    }

    assertThat(new RepositoryValidator().inspect(repo))
        .as("o git de linha de comando diz \"this operation must be run in a work tree\"")
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.isBare()));
  }

  @Test
  void coreWorktreeApontandoParaOutraPastaERecusado() throws Exception {
    final Path repo = repositorioComUmCommit("desviado");
    final Path outra = Files.createDirectories(real.resolve("outra"));
    try (Git git = Git.open(repo.toFile())) {
      final StoredConfig config = git.getRepository().getConfig();
      config.setString("core", null, "worktree", outra.toString());
      config.save();
    }

    assertThat(new RepositoryValidator().inspect(repo))
        .as("a árvore que o JGit leria não é a pasta escolhida")
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.notAGitRepository()));
  }

  @Test
  void headDestacadoTemBranchNuloENuncaOSha() throws Exception {
    final Path repo = real.resolve("destacado");
    final RevCommit commit;
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      commit = TestRepositories.commitAll(git, "primeiro");
    }
    Files.writeString(repo.resolve(".git/HEAD"), commit.name() + "\n");

    final var accepted = inspecionado(new RepositoryValidator().inspect(repo));

    assertThat(accepted.value().head())
        .as("getBranch() devolveria o SHA, impresso como se fosse nome de branch")
        .isEqualTo(new HeadState(commit.name(), null, false));
    assertThat(accepted.warnings()).isEmpty();
  }

  @Test
  void arquivoShallowComOCommitDoHeadMarcaOCloneComoRaso() throws Exception {
    final Path repo = real.resolve("raso");
    final RevCommit commit;
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      commit = TestRepositories.commitAll(git, "primeiro");
    }
    Files.writeString(repo.resolve(".git/shallow"), commit.name() + "\n");

    assertThat(inspecionado(new RepositoryValidator().inspect(repo)).value().head())
        .isEqualTo(new HeadState(commit.name(), "main", true));
  }

  @Test
  void headNaoNascidoAvisaNaInspecaoEOInventarioSegue() throws Exception {
    final Path repo = real.resolve("recem-criado");
    TestRepositories.init(repo).close();
    TestRepositories.write(repo, "src/main/java/A.java", "class A {}");

    final var accepted = inspecionado(new RepositoryValidator().inspect(repo));

    assertThat(accepted.warnings()).containsExactly(RepositoryIssue.noHistory());
    assertThat(accepted.value().head()).isEqualTo(new HeadState(null, "main", false));
    assertThat(accepted.value().inventory().javaSources())
        .extracting(JavaSource::relativePath)
        .containsExactly("src/main/java/A.java");
    assertThat(accepted.value().root()).isEqualTo(repo);
    assertThat(accepted.value().displayName()).isEqualTo("recem-criado");
  }

  @Test
  void naInspecaoOAvisoDoHeadVemAntesDosDaVarreduraEOTetoValeParaOsDois() throws Exception {
    final Path fora = Files.createDirectories(real.resolve("fora"));
    final Path repo = real.resolve("repo");
    TestRepositories.init(repo).close();
    TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
    TestRepositories.symlink(repo.resolve("vazamento"), fora);
    final RepositoryLimits umAviso =
        new RepositoryLimits(
            RepositoryLimits.PRODUCTION.maxEnumeratedFiles(),
            RepositoryLimits.PRODUCTION.maxDirectoryDepth(),
            RepositoryLimits.PRODUCTION.maxPathBytes(),
            RepositoryLimits.PRODUCTION.maxBuildFileBytes(),
            RepositoryLimits.PRODUCTION.maxGitConfigBytes(),
            1,
            RepositoryLimits.PRODUCTION.maxPackedRefsBytes());

    assertThat(inspecionado(new RepositoryValidator().inspect(repo)).warnings())
        .containsExactly(RepositoryIssue.noHistory(), RepositoryIssue.pathEscapesRoot("vazamento"));
    assertThat(inspecionado(new RepositoryValidator(umAviso).inspect(repo)).warnings())
        .containsExactly(RepositoryIssue.noHistory());
  }

  @Test
  void naInspecaoOSymlinkQueFogeViraAvisoEOAlvoNuncaViraFonte() throws Exception {
    final Path fora = real.resolve("fora");
    TestRepositories.write(fora, "Segredo.java", "package fora; class Segredo {}");
    final Path repo = repositorioComUmCommit("repo");
    TestRepositories.symlink(repo.resolve("src/main/java/Fuga.java"), fora.resolve("Segredo.java"));
    TestRepositories.symlink(repo.resolve("vazamento"), fora);
    TestRepositories.write(repo, "src/main/java/b/B.java", "package b; class B {}");

    final var accepted = inspecionado(new RepositoryValidator().inspect(repo));

    assertThat(accepted.warnings())
        .containsExactlyInAnyOrder(
            RepositoryIssue.pathEscapesRoot("src/main/java/Fuga.java"),
            RepositoryIssue.pathEscapesRoot("vazamento"));
    assertThat(accepted.value().inventory().javaSources())
        .extracting(JavaSource::relativePath)
        .containsExactly("src/main/java/A.java", "src/main/java/b/B.java");
    assertThat(accepted.value().inventory().totalFileCount()).isEqualTo(4);
  }

  @Test
  void worktreeLigadoNaInspecaoLeOHeadDoAdministrativoEOsRefsDoPrincipal() throws Exception {
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

    final var accepted = inspecionado(new RepositoryValidator().inspect(worktree));

    assertThat(accepted.value().head()).isEqualTo(new HeadState(commit.name(), "recurso", false));
    assertThat(accepted.value().displayName()).isEqualTo("worktree");
    assertThat(accepted.value().inventory().totalFileCount())
        .as("o arquivo .git do worktree não conta")
        .isEqualTo(1);
  }

  @Test
  void caminhoRelativoERecusadoComRepoPathChanged() throws Exception {
    repositorioComUmCommit("repo");

    assertThat(new RepositoryValidator().inspect(Path.of("repo")))
        .as("o Rust só manda caminho absoluto; um relativo seria lido contra o diretório corrente")
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.pathChanged()));
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "permissão POSIX não existe no Windows")
  void raizQueNaoPodeSerListadaERecusadaComRepoRootUnreadable() throws Exception {
    final Path repo = repositorioComUmCommit("trancado");
    Files.setPosixFilePermissions(repo, Set.of());
    try {
      Assumptions.assumeFalse(
          Files.isReadable(repo),
          "este usuário lê um diretório sem permissões (rodando como root?): a raiz ilegível"
              + " não pode ser produzida aqui");

      assertThat(new RepositoryValidator().inspect(repo))
          .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.rootUnreadable()));
    } finally {
      Files.setPosixFilePermissions(repo, Set.of(PosixFilePermission.values()));
    }
  }
}
