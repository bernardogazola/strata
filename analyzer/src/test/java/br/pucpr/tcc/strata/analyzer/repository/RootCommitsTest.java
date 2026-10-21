package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RootCommitsTest {

  private static final BooleanSupplier NUNCA = () -> false;

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
  void historiaLinearDaOPrimeiroCommit() throws Exception {
    final Path repo = real.resolve("linear");
    final ObjectId primeiro;
    try (Historia historia = Historia.em(repo)) {
      primeiro = historia.commit("um");
      final ObjectId segundo = historia.commit("dois", primeiro);
      historia.apontarMain(historia.commit("tres", segundo));
    }
    final RepositorySnapshot snapshot = inspecionar(repo);

    assertThat(RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), NUNCA))
        .contains(primeiro.name());
  }

  @Test
  void umRepositorioDeUmCommitSoEAPropriaRaiz() throws Exception {
    final Path repo = real.resolve("um-commit");
    final ObjectId unico;
    try (Historia historia = Historia.em(repo)) {
      unico = historia.commit("unico");
      historia.apontarMain(unico);
    }
    final RepositorySnapshot snapshot = inspecionar(repo);

    assertThat(RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), NUNCA))
        .contains(unico.name());
  }

  @Test
  void numaFusaoComOutraRaizNoSegundoPaiARespostaEADoPrimeiroPai() throws Exception {
    final Path repo = real.resolve("fusao");
    final ObjectId raizPrincipal;
    final ObjectId raizDoRamo;
    try (Historia historia = Historia.em(repo)) {
      raizPrincipal = historia.commit("principal um");
      final ObjectId principal = historia.commit("principal dois", raizPrincipal);
      raizDoRamo = historia.commit("ramo um, sem pai");
      final ObjectId ramo = historia.commit("ramo dois", raizDoRamo);
      final ObjectId fusao = historia.commit("funde o ramo", principal, ramo);
      historia.apontarMain(historia.commit("depois da fusao", fusao));
    }
    final RepositorySnapshot snapshot = inspecionar(repo);

    assertThat(RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), NUNCA))
        .as("%s também é raiz, mas não está na cadeia de primeiros pais", raizDoRamo.name())
        .contains(raizPrincipal.name());
  }

  @Test
  void cloneRasoDaVazioEACaminhadaIngenuaDevolveriaOHead() throws Exception {
    final Path repo = real.resolve("raso");
    final ObjectId raizDeVerdade;
    final ObjectId head;
    try (Historia historia = Historia.em(repo)) {
      raizDeVerdade = historia.commit("um");
      head = historia.commit("dois", raizDeVerdade);
      historia.apontarMain(head);
    }
    Files.writeString(repo.resolve(".git/shallow"), head.name() + "\n", StandardCharsets.US_ASCII);
    final RepositorySnapshot snapshot = inspecionar(repo);
    assertThat(snapshot.head().shallow()).as("a verificação leu o .git/shallow").isTrue();

    final Optional<String> raiz =
        RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), NUNCA);

    assertThat(raiz).as("uma fronteira não é raiz").isEmpty();
    assertThat(caminhadaIngenua(repo, head))
        .as("vacuidade: sem a regra do raso, a fronteira (o HEAD) sairia como raiz")
        .isEqualTo(head)
        .isNotEqualTo(raizDeVerdade);
  }

  @Test
  void headNaoNascidoDaVazioSemLerObjetoNenhum() throws Exception {
    final Path repo = real.resolve("nao-nascido");
    TestRepositories.init(repo).close();
    final RepositorySnapshot snapshot = inspecionar(repo);
    assertThat(snapshot.head().commitId()).isNull();
    final Path objetos = repo.resolve(".git/objects");
    try (var entradas = Files.walk(objetos)) {
      for (final Path entrada : entradas.sorted((a, b) -> b.compareTo(a)).toList()) {
        Files.delete(entrada);
      }
    }
    Files.writeString(objetos, "nada aqui\n");

    final List<String> log =
        stderrDe(
            () ->
                assertThat(RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), NUNCA))
                    .isEmpty());

    assertThat(log).as("nada foi aberto, então nada falhou").isEmpty();
  }

  @Test
  void umCancelamentoJaPedidoLancaCancellationException() throws Exception {
    final RepositorySnapshot snapshot = inspecionar(linearDe(real.resolve("cancelado"), 6));
    final AtomicInteger consultas = new AtomicInteger();

    assertThatThrownBy(
            () ->
                RootCommits.firstParentRoot(
                    snapshot.head(),
                    snapshot.root(),
                    () -> {
                      consultas.incrementAndGet();
                      return true;
                    }))
        .as("um cancelamento não é falha de caminhada: nunca vira vazio")
        .isInstanceOf(CancellationException.class);
    assertThat(consultas).hasValue(1);
  }

  @Test
  void oCancelamentoEConsultadoUmaVezPorCommit() throws Exception {
    final int commits = 6;
    final RepositorySnapshot snapshot = inspecionar(linearDe(real.resolve("contado"), commits));
    final AtomicInteger consultas = new AtomicInteger();

    final Optional<String> raiz =
        RootCommits.firstParentRoot(
            snapshot.head(),
            snapshot.root(),
            () -> {
              consultas.incrementAndGet();
              return false;
            });

    assertThat(raiz).isPresent();
    assertThat(consultas.get()).isBetween(commits - 1, commits);
  }

  @Test
  void umCancelamentoNoMeioParaNaConsultaSeguinte() throws Exception {
    final RepositorySnapshot snapshot = inspecionar(linearDe(real.resolve("meio"), 6));
    final AtomicInteger consultas = new AtomicInteger();

    assertThatThrownBy(
            () ->
                RootCommits.firstParentRoot(
                    snapshot.head(), snapshot.root(), () -> consultas.incrementAndGet() >= 3))
        .isInstanceOf(CancellationException.class);
    assertThat(consultas).as("lançou na terceira consulta, sem uma quarta").hasValue(3);
  }

  @Test
  void headQueNomeiaUmaArvoreDaVazioEAvisaSemDeixarEscaparNada() throws Exception {
    final Path repo = real.resolve("head-arvore");
    final ObjectId arvore;
    try (Historia historia = Historia.em(repo)) {
      historia.apontarMain(historia.commit("um"));
      arvore = historia.arvore;
    }
    Files.writeString(repo.resolve(".git/refs/heads/main"), arvore.name() + "\n");
    final RepositorySnapshot snapshot = inspecionar(repo);
    assertThat(snapshot.head().commitId())
        .as("a verificação não lê objeto: o id da árvore passa como commit")
        .isEqualTo(arvore.name());

    final List<String> log =
        stderrDe(
            () ->
                assertThat(RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), NUNCA))
                    .isEmpty());

    assertThat(log)
        .as("IncorrectObjectTypeException vira vazio e um Log.warn, nunca uma análise que falha")
        .anyMatch(linha -> linha.startsWith("[WARN]"));
  }

  @Test
  void umPaiQueFaltaNoBancoDeObjetosDaVazioEAvisa() throws Exception {
    final Path repo = real.resolve("pai-faltando");
    final ObjectId primeiro;
    try (Historia historia = Historia.em(repo)) {
      primeiro = historia.commit("um");
      historia.apontarMain(historia.commit("dois", primeiro));
    }
    Files.delete(loose(repo, primeiro));
    final RepositorySnapshot snapshot = inspecionar(repo);

    final List<String> log =
        stderrDe(
            () ->
                assertThat(RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), NUNCA))
                    .as("MissingObjectException: a chave degrada para só o caminho")
                    .isEmpty());

    assertThat(log).anyMatch(linha -> linha.startsWith("[WARN]"));
  }

  @Test
  void graftsERefsReplaceNaoMudamARaiz() throws Exception {
    final Path repo = real.resolve("enxertos");
    final ObjectId primeiro;
    try (Historia historia = Historia.em(repo)) {
      primeiro = historia.commit("um");
      final ObjectId segundo = historia.commit("dois", primeiro);
      final ObjectId head = historia.commit("tres", segundo);
      historia.apontarMain(head);
      final ObjectId substituto = historia.commit("tres, sem pai");
      historia.apontar("refs/replace/" + head.name(), substituto);
      Files.createDirectories(repo.resolve(".git/info"));
      Files.writeString(repo.resolve(".git/info/grafts"), segundo.name() + "\n");
    }
    final RepositorySnapshot snapshot = inspecionar(repo);

    assertThat(RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), NUNCA))
        .as("git: o enxerto faria de 'dois' a raiz, e o replace faria de 'tres, sem pai'")
        .contains(primeiro.name());
  }

  private Path linearDe(Path repo, int commits) throws Exception {
    try (Historia historia = Historia.em(repo)) {
      ObjectId atual = historia.commit("commit 0");
      for (int i = 1; i < commits; i++) {
        atual = historia.commit("commit " + i, atual);
      }
      historia.apontarMain(atual);
    }
    return repo;
  }

  private static RepositorySnapshot inspecionar(Path repo) throws IOException {
    final RepositoryOutcome<RepositorySnapshot> outcome = new RepositoryValidator().inspect(repo);
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<RepositorySnapshot>) outcome).value();
  }

  private static ObjectId caminhadaIngenua(Path repo, ObjectId head) throws IOException {
    final FileRepositoryBuilder builder =
        new FileRepositoryBuilder()
            .setFS(FS.DETECTED)
            .addCeilingDirectory(repo.getParent().toFile())
            .findGitDir(repo.toFile());
    try (Repository repository = builder.setMustExist(true).build();
        RevWalk walk = new RevWalk(repository)) {
      RevCommit atual = walk.parseCommit(head);
      while (atual.getParentCount() > 0) {
        atual = walk.parseCommit(atual.getParent(0));
      }
      return atual.getId();
    }
  }

  private static Path loose(Path repo, ObjectId id) {
    final String nome = id.name();
    return repo.resolve(".git/objects").resolve(nome.substring(0, 2)).resolve(nome.substring(2));
  }

  private static List<String> stderrDe(Acao acao) throws Exception {
    final PrintStream original = System.err;
    final ByteArrayOutputStream capturado = new ByteArrayOutputStream();
    System.setErr(new PrintStream(capturado, true, StandardCharsets.UTF_8));
    try {
      acao.executar();
    } finally {
      System.setErr(original);
    }
    return capturado.toString(StandardCharsets.UTF_8).lines().toList();
  }

  @FunctionalInterface
  private interface Acao {
    void executar() throws Exception;
  }

  private static final class Historia implements AutoCloseable {

    private final Git git;
    private final ObjectInserter inserter;
    private final ObjectId arvore;

    private Historia(Git git) throws IOException {
      this.git = git;
      this.inserter = git.getRepository().newObjectInserter();
      this.arvore = inserter.insert(new TreeFormatter());
      inserter.flush();
    }

    static Historia em(Path repo) throws Exception {
      return new Historia(TestRepositories.init(repo));
    }

    ObjectId commit(String mensagem, ObjectId... pais) throws IOException {
      final CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(arvore);
      commit.setParentIds(pais);
      commit.setAuthor(TestRepositories.FIXTURE_IDENT);
      commit.setCommitter(TestRepositories.FIXTURE_IDENT);
      commit.setMessage(mensagem + "\n");
      final ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }

    void apontarMain(ObjectId commit) throws IOException {
      apontar("refs/heads/main", commit);
    }

    void apontar(String ref, ObjectId alvo) throws IOException {
      final RefUpdate update = git.getRepository().updateRef(ref);
      update.setNewObjectId(alvo);
      update.setForceUpdate(true);
      final RefUpdate.Result resultado = update.update();
      assertThat(resultado).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED);
    }

    @Override
    public void close() {
      inserter.close();
      git.close();
    }
  }
}
