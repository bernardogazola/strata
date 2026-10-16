package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class GitMetadataGuardTest {

  private static final RepositoryOutcome<RepositorySnapshot> NOT_A_GIT_REPOSITORY =
      new RepositoryOutcome.Rejected<>(RepositoryIssue.notAGitRepository());

  private static final String REDE = "//strata.invalid/share";

  private static final String SO_NO_UNIX =
      "só um Unix cola // em uma barra só e acha o caminho local; no Windows //C:/… não é caminho"
          + " nenhum, e lá a regra é provada pelos casos com strata.invalid e pelo predicado";

  private static final String REDE_INVERTIDA = "\\\\strata.invalid\\share";

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

  @Test
  void gitdirDaPastaGitAcimaDoTetoERecusadoENoTetoPassa() throws Exception {
    final Path repo = repositorioNaoNascido("gitdir");
    final String apontaParaORepo = repo.resolve(".git") + "\n";
    final int teto = tamanhoDoConfig(repo) + utf8(apontaParaORepo) + 64;
    final RepositoryLimits limites = limitesComConfigDe(teto);
    final Path gitdir = repo.resolve(".git/gitdir");

    esparso(gitdir, teto + 1L);
    assertThat(new GitMetadataGuard(limites).admits(repo))
        .as("o guessWorkTreeOrFail lê o gitdir inteiro mesmo em um .git diretório comum")
        .isFalse();
    assertThat(new RepositoryValidator(limites).inspect(repo)).isEqualTo(NOT_A_GIT_REPOSITORY);

    Files.writeString(gitdir, preenchido(apontaParaORepo, '\n', "", teto + 1));
    assertThat(new RepositoryValidator(limites).inspect(repo)).isEqualTo(NOT_A_GIT_REPOSITORY);

    Files.writeString(gitdir, preenchido(apontaParaORepo, '\n', "", teto));
    assertThat(new RepositoryValidator(limites).inspect(repo))
        .as("o JGit apara o conteúdo e põe a árvore no pai do .git: a própria raiz")
        .isInstanceOf(RepositoryOutcome.Accepted.class);
  }

  @Test
  void shallowAcimaDoTetoERecusadoENoTetoPassa() throws Exception {
    final Path repo = real.resolve("raso");
    final RevCommit commit = commitInicial(repo);
    final String linha = commit.name() + "\n";
    final int linhas = tamanhoDoConfig(repo) / linha.length() + 2;
    final int teto = linha.length() * linhas - 1;
    final RepositoryLimits limites = limitesComConfigDe(teto);
    final Path shallow = repo.resolve(".git/shallow");

    esparso(shallow, teto + 1L);
    assertThat(new GitMetadataGuard(limites).admits(repo))
        .as("o getShallowCommits lê linha a linha, sem teto de linha")
        .isFalse();
    assertThat(new RepositoryValidator(limites).inspect(repo)).isEqualTo(NOT_A_GIT_REPOSITORY);

    Files.writeString(shallow, linha.repeat(linhas));
    assertThat(new RepositoryValidator(limites).inspect(repo)).isEqualTo(NOT_A_GIT_REPOSITORY);

    Files.writeString(shallow, linha.repeat(linhas - 1) + commit.name());
    assertThat(inspecionado(new RepositoryValidator(limites).inspect(repo)).value().head())
        .isEqualTo(new HeadState(commit.name(), "main", true));
  }

  @Test
  void packedRefsAcimaDoTetoProprioERecusadoENoTetoPassa() throws Exception {
    final Path repo = real.resolve("empacotado");
    final RevCommit commit = commitInicial(repo);
    final String marco = commit.name() + " refs/tags/marco\n#";
    final int teto = 256;
    final RepositoryLimits limites = limitesComPackedRefsDe(teto);
    final Path packedRefs = repo.resolve(".git/packed-refs");

    esparso(packedRefs, teto + 1L);
    assertThat(new GitMetadataGuard(limites).admits(repo))
        .as("o exactRef(HEAD) sempre lê o packed-refs inteiro, e o teto dele é o próprio")
        .isFalse();
    assertThat(new RepositoryValidator(limites).inspect(repo)).isEqualTo(NOT_A_GIT_REPOSITORY);

    Files.writeString(packedRefs, preenchido(marco, 'x', "\n", teto + 1));
    assertThat(new RepositoryValidator(limites).inspect(repo)).isEqualTo(NOT_A_GIT_REPOSITORY);

    Files.writeString(packedRefs, preenchido(marco, 'x', "\n", teto));
    assertThat(new RepositoryValidator(limites).inspect(repo))
        .as("uma linha de comentário em qualquer lugar o JGit pula")
        .isInstanceOf(RepositoryOutcome.Accepted.class);
    assertThat(new RepositoryValidator(limitesComConfigDe(teto - 1)).inspect(repo))
        .as("o packed-refs tem teto próprio: o de configuração, menor que ele, não o alcança")
        .isInstanceOf(RepositoryOutcome.Accepted.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "//strata.invalid/share/x",
        "\\\\strata.invalid\\share\\x",
        "\\\\?\\UNC\\strata.invalid\\share\\x",
        "/\\strata.invalid\\share",
        "\\/strata.invalid/share",
        "\\\\.\\pipe\\strata"
      })
  void duasBarrasDeQualquerTipoNoComecoNomeiamARede(String caminho) {
    assertThat(GitMetadataGuard.namesNetworkLocation(caminho)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/repo/.git", "relativo/x", "../x", "~/x", "C:\\repo", "C:/repo", "/", "\\", ""})
  void umaBarraSoOuNenhumaNaoNomeiaARede(String caminho) {
    assertThat(GitMetadataGuard.namesNetworkLocation(caminho)).isFalse();
  }

  @Test
  void includeParaCaminhoDeRedeERecusadoNasDuasGrafias() throws Exception {
    final Path barras = repositorioNaoNascido("include-rede");
    anexarAoConfig(barras, "[include]\n\tpath = " + REDE + "/x.cfg\n");
    final Path invertidas = repositorioNaoNascido("include-rede-invertida");
    anexarAoConfig(
        invertidas, "[include]\n\tpath = " + REDE_INVERTIDA.replace("\\", "\\\\") + "\\\\x.cfg\n");

    assertThat(new RepositoryValidator().inspect(barras)).isEqualTo(NOT_A_GIT_REPOSITORY);
    assertThat(new RepositoryValidator().inspect(invertidas)).isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void commondirApontandoParaARedeERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("commondir-rede");
    Files.writeString(repo.resolve(".git/commondir"), REDE + "/principal.git\n");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo)).isFalse();
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = SO_NO_UNIX)
  void arquivoPontoGitApontandoParaARedeERecusado() throws Exception {
    final Path outro = repositorioNaoNascido("outro");
    final Path pasta = real.resolve("ponteiro-rede");
    TestRepositories.write(pasta, ".git", "gitdir: " + comDuasBarras(outro.resolve(".git")) + "\n");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(pasta)).isFalse();
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = SO_NO_UNIX)
  void coreWorktreeDeRedeERecusadoTambemVindoDeUmInclude() throws Exception {
    final Path direto = repositorioNaoNascido("worktree-rede");
    anexarAoConfig(direto, "[core]\n\tworktree = " + comDuasBarras(direto) + "\n");
    final Path incluido = repositorioNaoNascido("worktree-rede-incluido");
    TestRepositories.write(
        incluido, ".git/extra.cfg", "[core]\n\tworktree = " + comDuasBarras(incluido) + "\n");
    anexarAoConfig(incluido, "[include]\n\tpath = extra.cfg\n");

    assertThat(new RepositoryValidator().inspect(direto))
        .as("o guessWorkTreeOrFail canoniza o core.worktree, e canonizar é seguir")
        .isEqualTo(NOT_A_GIT_REPOSITORY);
    assertThat(new RepositoryValidator().inspect(incluido))
        .as("o JGit lê o core.worktree da config já com os includes")
        .isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = SO_NO_UNIX)
  void gitdirDaPastaGitApontandoParaARedeERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("gitdir-rede");
    Files.writeString(repo.resolve(".git/gitdir"), comDuasBarras(repo.resolve(".git")) + "\n");

    assertThat(new RepositoryValidator().inspect(repo))
        .as("o validador resolveria a árvore que esse ponteiro dá, para compará-la com a raiz")
        .isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void headQueESymlinkDentroDoPontoGitERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("head-link");
    final Path head = repo.resolve(".git/HEAD");
    Files.move(head, repo.resolve(".git/HEAD.real"));
    TestRepositories.symlink(head, repo.resolve(".git/HEAD.real"));

    assertThat(new RepositoryValidator().inspect(repo))
        .as("o conteúdo é válido; o que recusa é o link, que o Git nunca escreve ali")
        .isEqualTo(NOT_A_GIT_REPOSITORY);
  }

  @Test
  void includeQuePassaPorUmDiretorioSymlinkDentroDoRepositorioERecusado() throws Exception {
    final Path repo = repositorioNaoNascido("include-por-link");
    final Path fora = Files.createDirectories(real.resolve("fora"));
    TestRepositories.write(fora, "um.cfg", "[strata]\n\tinofensivo = sim\n");
    TestRepositories.symlink(repo.resolve(".git/atalho"), fora);
    anexarAoConfig(repo, "[include]\n\tpath = atalho/um.cfg\n");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo))
        .as("o arquivo é regular; o componente .git/atalho/ é link, e quem o pôs foi o repositório")
        .isFalse();
  }

  @Test
  void includeQuePassaPorUmLinkDoUsuarioForaDoRepositorioEAceito() throws Exception {
    final Path repo = repositorioNaoNascido("include-por-link-do-usuario");
    final Path fora = Files.createDirectories(real.resolve("dotfiles"));
    TestRepositories.write(fora, "trabalho.cfg", "[strata]\n\tinofensivo = sim\n");
    final Path atalho = TestRepositories.symlink(real.resolve("atalho"), fora);
    anexarAoConfig(
        repo, "[include]\n\tpath = " + barrasNormais(atalho.resolve("trabalho.cfg")) + "\n");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo)).isTrue();
  }

  @Test
  void worktreeCujoRepositorioPrincipalMoraAtrasDeUmLinkDoUsuarioEAceito() throws Exception {
    final Path externo = Files.createDirectories(real.resolve("externo"));
    final RevCommit commit = commitInicial(externo.resolve("principal"));
    TestRepositories.write(
        externo.resolve("principal"), ".git/refs/heads/recurso", commit.name() + "\n");
    final Path atalho = TestRepositories.symlink(real.resolve("code"), externo);
    final Path worktree =
        TestRepositories.linkedWorktree(
            atalho.resolve("principal"), real.resolve("worktree"), "recurso");
    TestRepositories.write(worktree, "src/main/java/A.java", "class A {}");

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(worktree)).isTrue();
    assertThat(inspecionado(new RepositoryValidator().inspect(worktree)).value().head())
        .isEqualTo(new HeadState(commit.name(), "recurso", false));
  }

  @Test
  void pontoGitDaRaizComoSymlinkLocalEAceito() throws Exception {
    final Path repo = real.resolve("ligado");
    final RevCommit commit = commitInicial(repo);
    final Path guardado = real.resolve("guardado.git");
    Files.move(repo.resolve(".git"), guardado);
    TestRepositories.symlink(repo.resolve(".git"), guardado);

    assertThat(inspecionado(new RepositoryValidator().inspect(repo)).value().head())
        .as("a única exceção: o .git da raiz pode ser link, para um alvo local sem link dentro")
        .isEqualTo(new HeadState(commit.name(), "main", false));
  }

  @Test
  @DisabledOnOs(
      value = OS.WINDOWS,
      disabledReason =
          "criar no Windows um link para um alvo UNC faz o JDK consultar o alvo, a própria"
              + " requisição SMB que este teste proíbe")
  void pontoGitDaRaizComoSymlinkParaARedeERecusado() throws Exception {
    final Path repo = Files.createDirectories(real.resolve("ligado-rede"));
    TestRepositories.symlink(repo.resolve(".git"), Path.of(REDE_INVERTIDA + "\\repo.git"));

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo))
        .as("o texto do link nomeia a rede, então ele nunca é lido seguindo o link")
        .isFalse();
  }

  @Test
  void pontoGitDaRaizApontandoParaOutroSymlinkDentroDoRepositorioERecusado() throws Exception {
    final Path repo = real.resolve("cadeia");
    commitInicial(repo);
    final Path guardado = real.resolve("guardado.git");
    Files.move(repo.resolve(".git"), guardado);
    final Path elo = TestRepositories.symlink(repo.resolve("elo"), guardado);
    TestRepositories.symlink(repo.resolve(".git"), elo);

    assertThat(new GitMetadataGuard(RepositoryLimits.PRODUCTION).admits(repo))
        .as("a exceção é um salto só dentro da pasta: o alvo do .git não pode ser outro link dela")
        .isFalse();
  }

  @Test
  void pontoGitDaRaizApontandoParaUmLinkDoUsuarioForaDoRepositorioEAceito() throws Exception {
    final Path repo = real.resolve("cadeia-do-usuario");
    final RevCommit commit = commitInicial(repo);
    final Path guardado = real.resolve("guardado-do-usuario.git");
    Files.move(repo.resolve(".git"), guardado);
    final Path elo = TestRepositories.symlink(real.resolve("elo-do-usuario"), guardado);
    TestRepositories.symlink(repo.resolve(".git"), elo);

    assertThat(inspecionado(new RepositoryValidator().inspect(repo)).value().head())
        .as("o elo fica fora da pasta escolhida: é da máquina de quem usa, e é seguido")
        .isEqualTo(new HeadState(commit.name(), "main", false));
  }

  private Path repositorioNaoNascido(String nome) throws Exception {
    final Path repo = real.resolve(nome);
    TestRepositories.init(repo).close();
    return repo;
  }

  private static RevCommit commitInicial(Path repo) throws Exception {
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      return TestRepositories.commitAll(git, "primeiro");
    }
  }

  private static RepositoryOutcome.Accepted<RepositorySnapshot> inspecionado(
      RepositoryOutcome<RepositorySnapshot> outcome) {
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return (RepositoryOutcome.Accepted<RepositorySnapshot>) outcome;
  }

  private static int tamanhoDoConfig(Path repo) throws Exception {
    return Math.toIntExact(Files.size(repo.resolve(".git/config")));
  }

  private static int utf8(String texto) {
    return texto.getBytes(StandardCharsets.UTF_8).length;
  }

  private static void esparso(Path arquivo, long bytes) throws Exception {
    try (RandomAccessFile aberto = new RandomAccessFile(arquivo.toFile(), "rw")) {
      aberto.setLength(bytes);
    }
  }

  private static String preenchido(String antes, char enchimento, String depois, int bytes) {
    final String texto =
        antes + String.valueOf(enchimento).repeat(bytes - utf8(antes) - utf8(depois)) + depois;
    assertThat(utf8(texto)).isEqualTo(bytes);
    return texto;
  }

  private static String comDuasBarras(Path existente) {
    return "/" + barrasNormais(existente);
  }

  private static String barrasNormais(Path caminho) {
    return caminho.toString().replace('\\', '/');
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

  private static RepositoryLimits limitesComPackedRefsDe(int bytes) {
    final RepositoryLimits producao = RepositoryLimits.PRODUCTION;
    return new RepositoryLimits(
        producao.maxEnumeratedFiles(),
        producao.maxDirectoryDepth(),
        producao.maxPathBytes(),
        producao.maxBuildFileBytes(),
        producao.maxGitConfigBytes(),
        producao.maxWarnings(),
        bytes);
  }
}
