package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class GitMetadataGuardTest {

  private static final RepositoryOutcome<RepositorySnapshot> NOT_A_GIT_REPOSITORY =
      new RepositoryOutcome.Rejected<>(RepositoryIssue.notAGitRepository());

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

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "o Windows não tem /dev/zero")
  void includeParaDevZeroERecusadoSemEstourarAMemoria() throws Exception {
    final Path repo = repositorioNaoNascido("zero");
    anexarAoConfig(repo, "[include]\n\tpath = /dev/zero\n");

    assertThat(new RepositoryValidator().inspect(repo))
        .as("sem a conferência o JGit lê /dev/zero inteiro: OutOfMemoryError, medido")
        .isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "o Windows não tem /dev/zero")
  void includeEmSubsecaoTambemEConferido() throws Exception {
    final Path repo = repositorioNaoNascido("subsecao");
    anexarAoConfig(repo, "[include \"qualquer\"]\n\tpath = /dev/zero\n");

    assertThat(new RepositoryValidator().inspect(repo))
        .as("o JGit segue [include] com qualquer subseção, não só a nula")
        .isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void includeParaUmDiretorioERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("diretorio");
    Files.createDirectories(repo.resolve(".git/incluido"));
    anexarAoConfig(repo, "[include]\n\tpath = incluido\n");

    assertThat(new RepositoryValidator().inspect(repo)).isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void includeAninhadoRelativoResolveContraAPastaDoConfigDeTopoComoNoJGit() throws Exception {
    final Path repo = repositorioNaoNascido("aninhado");
    final Path fora = Files.createDirectories(real.resolve("fora"));
    TestRepositories.write(fora, "um.cfg", "[include]\n\tpath = dois.cfg\n");
    TestRepositories.write(fora, "dois.cfg", "[strata]\n\tinofensivo = sim\n");
    Files.createDirectories(repo.resolve(".git/dois.cfg"));
    anexarAoConfig(
        repo, "[include]\n\tpath = " + fora.resolve("um.cfg").toString().replace('\\', '/') + "\n");

    assertThat(new RepositoryValidator().inspect(repo))
        .as(
            "o JGit lê dois.cfg em .git/, onde ele é um diretório; conferir o fora/dois.cfg"
                + " regular deixaria passar o arquivo que o JGit de fato abre")
        .isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void configAcimaDoTetoInjetadoERecusadoENoTetoPassa() throws Exception {
    final Path repo = repositorioNaoNascido("teto");
    final int tamanho = Math.toIntExact(Files.size(repo.resolve(".git/config")));

    assertThat(new RepositoryValidator(limitesComConfigDe(tamanho - 1)).inspect(repo))
        .as("um byte acima do teto")
        .isEqualTo(NOT_A_GIT_REPOSITORY);
    assertThat(new RepositoryValidator(limitesComConfigDe(tamanho)).inspect(repo))
        .as("o teto é \"no máximo\", inclusive")
        .isInstanceOf(RepositoryOutcome.Accepted.class);
  }

  @Test
  void arquivoIncluidoAcimaDoTetoERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("incluido-grande");
    final int teto = Math.toIntExact(Files.size(repo.resolve(".git/config"))) + 64;
    TestRepositories.write(repo, ".git/grande.cfg", "#".repeat(teto + 1));
    anexarAoConfig(repo, "[include]\n\tpath = grande.cfg\n");

    assertThat(new RepositoryValidator(limitesComConfigDe(teto)).inspect(repo))
        .isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void includeLegitimoParaArquivoPequenoRegularEAceito() throws Exception {
    final Path repo = repositorioNaoNascido("legitimo");
    TestRepositories.write(repo, ".git/extra.cfg", "[strata]\n\tmarcador = incluido\n");
    anexarAoConfig(repo, "[include]\n\tpath = extra.cfg\n\tpath = nao-existe.cfg\n");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo)).isTrue();
    assertThat(new RepositoryValidator().inspect(repo))
        .as("um alvo ausente o JGit ignora, e o guarda também")
        .isInstanceOf(RepositoryOutcome.Accepted.class);
  }

  @Test
  void includeQueSeRepeteERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("laco");
    anexarAoConfig(repo, "[include]\n\tpath = config\n");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo))
        .as("um config que inclui a si mesmo é laço; o JGit só desistiria no décimo nível")
        .isFalse();
  }

  @Test
  void arquivoPontoGitComLixoERecusado() throws Exception {
    final Path pasta = real.resolve("lixo");
    TestRepositories.write(pasta, ".git", "isto não é um ponteiro de worktree\n");
    TestRepositories.write(pasta, "src/main/java/A.java", "class A {}");

    assertThat(new RepositoryValidator().inspect(pasta)).isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void arquivoPontoGitApontandoParaLugarNenhumERecusado() throws Exception {
    final Path pasta = real.resolve("orfa");
    TestRepositories.write(pasta, ".git", "gitdir: " + real.resolve("nao-existe") + "\n");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(pasta)).isFalse();
    assertThat(new RepositoryValidator().inspect(pasta)).isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void arquivoPontoGitAcimaDoTetoERecusado() throws Exception {
    final Path pasta = real.resolve("ponteiro-grande");
    TestRepositories.write(pasta, ".git", "gitdir: " + "x".repeat(200) + "\n");

    assertThat(new GitMetadataGuard(limitesComConfigDe(64)).admits(pasta))
        .as("o JGit lê o arquivo .git inteiro na memória")
        .isFalse();
  }

  @Test
  void headComoDiretorioERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("head-diretorio");
    final Path head = repo.resolve(".git/HEAD");
    Files.delete(head);
    Files.createDirectories(head);

    assertThat(new RepositoryValidator().inspect(repo)).isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void headApontandoParaNomeDeRefInvalidoERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("ref-invalido");
    Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/../../../fora\n");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo))
        .as("ref: refs/../../../fora faria o JGit ler um arquivo fora do .git")
        .isFalse();
  }

  @Test
  void refSoltoDoHeadComoDiretorioERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("ref-diretorio");
    Files.createDirectories(repo.resolve(".git/refs/heads/main"));

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo)).isFalse();
  }

  @Test
  void worktreeLigadoEAdmitidoComOsRefsNaPastaComum() throws Exception {
    final Path principal = real.resolve("principal");
    try (Git git = TestRepositories.init(principal)) {
      TestRepositories.write(principal, "src/main/java/A.java", "class A {}");
      TestRepositories.commitAll(git, "primeiro");
      git.branchCreate().setName("recurso").call();
    }
    final Path worktree =
        TestRepositories.linkedWorktree(principal, real.resolve("worktree"), "recurso");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(worktree)).isTrue();
  }

  @Test
  void pastaSemMetadadoGitEAdmitidaParaOJGitRecusar() throws Exception {
    final Path pasta = Files.createDirectories(real.resolve("solta"));

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(pasta))
        .as("não há o que travar; quem diz que não é repositório é o JGit, com o mesmo código")
        .isTrue();
  }

  private Path repositorioNaoNascido(String nome) throws Exception {
    final Path repo = real.resolve(nome);
    TestRepositories.init(repo).close();
    return repo;
  }

  private static void anexarAoConfig(Path repo, String texto) throws Exception {
    Files.writeString(
        repo.resolve(".git/config"), texto, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
  }

  private static RepositoryLimits limitesComConfigDe(int bytes) {
    final RepositoryLimits producao = RepositoryLimits.PRODUCTION;
    return new RepositoryLimits(
        producao.maxEnumeratedFiles(),
        producao.maxDirectoryDepth(),
        producao.maxPathBytes(),
        producao.maxBuildFileBytes(),
        bytes,
        producao.maxWarnings(),
        producao.maxPackedRefsBytes());
  }
}
