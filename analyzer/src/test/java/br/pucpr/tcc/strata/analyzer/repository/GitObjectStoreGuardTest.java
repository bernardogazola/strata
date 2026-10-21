package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class GitObjectStoreGuardTest {

  private static final RepositoryOutcome<RepositorySnapshot> NOT_A_GIT_REPOSITORY =
      new RepositoryOutcome.Rejected<>(RepositoryIssue.notAGitRepository());

  private static final RepositoryLimits P = RepositoryLimits.PRODUCTION;

  private static final String SOLTO_DIR = "12";

  private static final String SOLTO_ARQUIVO = "3456789abcdef0123456789abcdef0123456789";

  private static final String PACK = "pack-0123456789abcdef0123456789abcdef01234567";

  private static final Pattern HEXA_2 = Pattern.compile("[0-9a-f]{2}");

  private static final Pattern LISTA_DA_GUARDA =
      Pattern.compile(
          "^(?:HEAD|config|config\\.worktree|commondir|gitdir|packed-refs|shallow|info/grafts"
              + "|refs/.+|reftable/.+"
              + "|objects/[0-9a-f]{2}/[0-9a-f]{38}"
              + "|objects/pack/pack-[0-9a-f]{40}\\.(?:idx|pack)|objects/pack/multi-pack-index"
              + "|objects/info/(?:alternates|commit-graph))$");

  private static final String FILE_READ = "jdk.FileRead";

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
  void alternatesComoDiretorioERecusado() throws Exception {
    final Path repo = naoNascido("alt-diretorio");
    Files.createDirectories(objetos(repo).resolve("info/alternates"));

    recusadoNomeando(repo, P, "alternates");
  }

  @Test
  void alternatesAcimaDoTetoInjetadoERecusadoENoTetoPassa() throws Exception {
    final Path repo = naoNascido("alt-teto");
    final int tamanho = Math.toIntExact(Files.size(repo.resolve(".git/config"))) + 200;
    Files.writeString(alternates(repo), linhaDe(real.resolve("nao-existe"), tamanho));
    assertThat(Files.size(alternates(repo))).isEqualTo(tamanho);

    recusadoNomeando(repo, comConfig(tamanho - 1), "alternates");
    admitido(repo, comConfig(tamanho));
  }

  @Test
  void alternatesComoSymlinkDentroDaRaizERecusado() throws Exception {
    final Path repo = naoNascido("alt-symlink");
    final Path dentro =
        TestRepositories.write(repo, "alternates-de-verdade.txt", real.resolve("nada") + "\n");
    TestRepositories.symlink(alternates(repo), dentro);

    recusadoNomeando(repo, P, "alternates");
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "o Windows não tem /dev/zero")
  void alternateDeForaCujoAlternatesEUmSymlinkParaDevZeroERecusado() throws Exception {
    final Path repo = naoNascido("alt-zero");
    final Path fora = Files.createDirectories(real.resolve("fora/objects"));
    TestRepositories.symlink(fora.resolve("info/alternates"), Path.of("/dev/zero"));
    Files.writeString(alternates(repo), fora + "\n");

    final List<String> log = recusado(repo, P);

    assertThat(log)
        .as("o link de fora da raiz é seguido, e a regra de tipo vale para o que ele alcança")
        .anyMatch(linha -> linha.contains(fora.toString()) || linha.contains("/dev/zero"));
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "o Windows não tem /dev/zero")
  void alternateCujoPaiEUmRepositorioComIncludeParaDevZeroERecusado() throws Exception {
    final Path repo = naoNascido("alt-include");
    final Path vizinho = naoNascido("vizinho-mau");
    Files.writeString(
        vizinho.resolve(".git/config"),
        "[include]\n\tpath = /dev/zero\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    Files.writeString(alternates(repo), objetos(vizinho) + "\n");

    final List<String> log = recusado(repo, P);

    assertThat(log)
        .as(
            "o JGit abre o pai do alternate como repositório: a lista inteira de metadados do .git vale")
        .anyMatch(linha -> linha.contains("vizinho-mau"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"//strata.invalid/share/objects", "\\\\strata.invalid\\share\\objects"})
  void alternatesQueNomeiaARedeERecusado(String linha) throws Exception {
    final Path repo = naoNascido("alt-rede-" + (linha.startsWith("/") ? "barra" : "invertida"));
    Files.writeString(alternates(repo), linha + "\n");

    recusadoNomeando(repo, P, "alternates");
  }

  @Test
  void alternatesQuebradoSoEmCrTemDuasLinhasEASegundaNaRedeERecusada() throws Exception {
    final Path repo = naoNascido("alt-cr");
    Files.writeString(
        alternates(repo), real.resolve("nao-existe/objects") + "\r//strata.invalid/share/objects");

    recusadoNomeando(repo, P, "nomeia o local de rede //strata.invalid/share/objects");
  }

  @Test
  void alternatesAninhadosAlemDoLimiteRecusadoENoLimitePassa() throws Exception {
    final int limite = AnalysisConstants.MAX_GIT_ALTERNATES_DEPTH;
    assertThat(limite + 1)
        .as("a cadeia cabe na contagem, para que só a profundidade recuse")
        .isLessThanOrEqualTo(AnalysisConstants.MAX_GIT_ALTERNATES);
    final Path noLimite = naoNascido("aninhado-no-limite");
    encadear(noLimite, "cadeia-a", limite);
    final Path alem = naoNascido("aninhado-alem");
    encadear(alem, "cadeia-b", limite + 1);

    admitido(noLimite, P);
    recusadoNomeando(alem, P, "alternates");
  }

  @Test
  void linhasDeAlternatesAlemDoLimiteNoFechoRecusadoENoLimitePassa() throws Exception {
    final int limite = AnalysisConstants.MAX_GIT_ALTERNATES;
    final Path noLimite = naoNascido("linhas-no-limite");
    final Path alem = naoNascido("linhas-alem");
    final Path aninhado = Files.createDirectories(real.resolve("linhas/aninhado/objects"));
    final List<String> doRepositorio = new ArrayList<>();
    doRepositorio.add(aninhado.toString());
    for (int i = 1; i < limite - 2; i++) {
      doRepositorio.add(
          Files.createDirectories(real.resolve("linhas/b" + i + "/objects")).toString());
    }
    doRepositorio.add(real.resolve("linhas/nao-existe/objects").toString());
    final String linhaAninhada = Files.createDirectories(real.resolve("linhas/c/objects")) + "\n";
    Files.createDirectories(aninhado.resolve("info"));
    Files.writeString(aninhado.resolve("info/alternates"), linhaAninhada);
    Files.writeString(alternates(noLimite), String.join("\n", doRepositorio) + "\n");
    Files.writeString(
        alternates(alem),
        String.join("\n", doRepositorio) + "\n" + real.resolve("linhas/mais-uma") + "\n");
    assertThat(doRepositorio.size() + 1)
        .as("no limite, somando a linha aninhada")
        .isEqualTo(limite);

    admitido(noLimite, P);
    recusadoNomeando(alem, P, "alternates");
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void cicloDeAlternatesEAdmitidoETermina() throws Exception {
    final Path repo = naoNascido("ciclo");
    final Path a = Files.createDirectories(real.resolve("ciclo-a/objects/info")).getParent();
    final Path b = Files.createDirectories(real.resolve("ciclo-b/objects/info")).getParent();
    Files.writeString(a.resolve("info/alternates"), b + "\n");
    Files.writeString(b.resolve("info/alternates"), a + "\n");
    Files.writeString(alternates(repo), a + "\n");

    admitido(repo, P);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void cloneCompartilhadoLegitimoEAdmitidoComLinhaAbsolutaOuRelativa(boolean absoluta)
      throws Exception {
    final Path base = real.resolve("base");
    final RevCommit commit = repositorioComCommit(base);
    final Path clone = naoNascido(absoluta ? "compartilhado-absoluto" : "compartilhado-relativo");
    Files.writeString(
        alternates(clone),
        (absoluta ? objetos(base).toString() : "../../../base/.git/objects") + "\n");
    Files.writeString(clone.resolve(".git/refs/heads/main"), commit.name() + "\n");

    final RepositorySnapshot snapshot = admitido(clone, P);

    assertThat(snapshot.head().commitId()).isEqualTo(commit.name());
    assertThat(lerCommit(clone, commit))
        .as("o JGit acha o commit pelo alternate")
        .isEqualTo(commit);
  }

  @Test
  void objetoSoltoComoDiretorioERecusado() throws Exception {
    final Path repo = real.resolve("solto-diretorio");
    repositorioComCommit(repo);
    Files.createDirectories(objetos(repo).resolve(SOLTO_DIR).resolve(SOLTO_ARQUIVO));

    recusadoNomeando(repo, P, SOLTO_ARQUIVO);
  }

  @Test
  void objetoSoltoComoSymlinkDentroDaRaizERecusado() throws Exception {
    final Path repo = real.resolve("solto-symlink");
    repositorioComCommit(repo);
    TestRepositories.symlink(
        objetos(repo).resolve(SOLTO_DIR).resolve(SOLTO_ARQUIVO),
        repo.resolve("src/main/java/A.java"));

    recusadoNomeando(repo, P, SOLTO_ARQUIVO);
  }

  @Test
  void entradasDosObjetosSoltosAcimaDoTetoInjetadoRecusadoENoTetoPassa() throws Exception {
    final Path repo = real.resolve("soltos");
    repositorioComCommit(repo);
    assertThat(contarSoltos(objetos(repo))).as("blob, árvores e commit").isGreaterThan(2);
    final int entradas = contarEntradas(objetos(repo));

    recusadoNomeando(repo, comEntradas(entradas - 1), acimaDe(entradas - 1));
    admitido(repo, comEntradas(entradas));
  }

  @Test
  void asEntradasDosAlternatesContamNoMesmoTeto() throws Exception {
    final Path base = real.resolve("base-soltos");
    repositorioComCommit(base);
    final Path clone = naoNascido("clone-soltos");
    Files.writeString(alternates(clone), objetos(base) + "\n");
    final int entradas = contarEntradas(objetos(clone)) + contarEntradas(objetos(base));

    recusadoNomeando(clone, comEntradas(entradas - 1), acimaDe(entradas - 1));
    admitido(clone, comEntradas(entradas));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pack", ".", SOLTO_DIR})
  void lixoNoBancoDeObjetosAcimaDoTetoInjetadoRecusadoENoTetoPassa(String pasta) throws Exception {
    final int arquivos = 40;
    final Path repo = naoNascido("lixo-" + (".".equals(pasta) ? "objects" : pasta));
    lixo(objetos(repo).resolve(pasta), arquivos);
    final int entradas = contarEntradas(objetos(repo));
    final int fanoutCriado = SOLTO_DIR.equals(pasta) ? 1 : 0;
    assertThat(entradas)
        .as(
            "o lixo, o info/ e o pack/ do git init, e a pasta de fanout quando o lixo está dentro de uma")
        .isEqualTo(arquivos + 2 + fanoutCriado);

    recusadoNomeando(repo, comEntradas(entradas - 1), acimaDe(entradas - 1));
    admitido(repo, comEntradas(entradas));
  }

  @Test
  void aGuardaConsultaOCancelamentoACadaPassoECanceladaLancaNoPrimeiro() throws Exception {
    final int passo = AnalysisConstants.WALK_CHECK_STRIDE_ENTRIES;
    final Path repo = naoNascido("lixo-cancelado");
    lixo(objetos(repo).resolve("pack"), 3 * passo);
    final int entradas = contarEntradas(objetos(repo));
    final AtomicInteger consultas = new AtomicInteger();

    final RepositoryOutcome<RepositorySnapshot> semCancelar =
        new RepositoryValidator(P)
            .inspect(
                repo,
                () -> {
                  consultas.incrementAndGet();
                  return false;
                },
                vistos -> {});

    assertThat(semCancelar).isInstanceOf(RepositoryOutcome.Accepted.class);
    assertThat(consultas).as("uma consulta por passo inteiro").hasValue(entradas / passo);

    consultas.set(0);
    final Throwable cancelado =
        catchThrowable(
            () ->
                new RepositoryValidator(P)
                    .inspect(
                        repo,
                        () -> {
                          consultas.incrementAndGet();
                          return true;
                        },
                        vistos -> {}));

    assertThat(cancelado)
        .isInstanceOf(CancellationException.class)
        .hasMessage("conferência do object store do git cancelada na entrada " + passo);
    assertThat(consultas).as("parou na primeira consulta").hasValue(1);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void idxComoDiretorioERecusado() throws Exception {
    final Path repo = naoNascido("idx-diretorio");
    Files.createDirectories(objetos(repo).resolve("pack").resolve(PACK + ".idx"));

    recusadoNomeando(repo, P, PACK + ".idx");
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void packComoDiretorioERecusado() throws Exception {
    final Path repo = naoNascido("pack-diretorio");
    Files.createDirectories(objetos(repo).resolve("pack").resolve(PACK + ".pack"));

    recusadoNomeando(repo, P, PACK + ".pack");
  }

  @Test
  void aPastaPackComoSymlinkDentroDaRaizERecusada() throws Exception {
    final Path repo = naoNascido("pack-symlink");
    final Path pack = objetos(repo).resolve("pack");
    try (Stream<Path> dentro = Files.list(pack)) {
      assertThat(dentro).as("a pasta pack nasce vazia").isEmpty();
    }
    Files.delete(pack);
    final Path outraPasta = Files.createDirectories(repo.resolve("outros-packs"));
    TestRepositories.symlink(pack, outraPasta);

    recusadoNomeando(repo, P, "pack");
  }

  @Test
  void idxAcimaDoTetoInjetadoERecusadoENoTetoPassa() throws Exception {
    final Path repo = naoNascido("idx-teto");
    escreverBytes(objetos(repo).resolve("pack").resolve(PACK + ".idx"), 1_001);

    recusadoNomeando(repo, comIndices(1_000, P.maxPackIndexTotalBytes()), PACK + ".idx");
    admitido(repo, comIndices(1_001, P.maxPackIndexTotalBytes()));
  }

  @Test
  void aSomaDosIndicesAcimaDoTotalInjetadoERecusadaENoTotalPassa() throws Exception {
    final Path repo = naoNascido("indices-soma");
    final Path pack = objetos(repo).resolve("pack");
    escreverBytes(pack.resolve("pack-" + "a".repeat(40) + ".idx"), 700);
    escreverBytes(pack.resolve("pack-" + "b".repeat(40) + ".idx"), 700);
    escreverBytes(pack.resolve("multi-pack-index"), 100);

    recusado(repo, comIndices(1_000, 1_499));
    admitido(repo, comIndices(1_000, 1_500));
  }

  @Test
  void osIndicesDosAlternatesContamNaMesmaSoma() throws Exception {
    final Path repo = naoNascido("indices-fecho");
    final Path fora =
        Files.createDirectories(real.resolve("indices-fora/objects/pack")).getParent();
    escreverBytes(objetos(repo).resolve("pack").resolve("pack-" + "c".repeat(40) + ".idx"), 800);
    escreverBytes(fora.resolve("pack").resolve("pack-" + "d".repeat(40) + ".idx"), 800);
    Files.writeString(alternates(repo), fora + "\n");

    recusado(repo, comIndices(1_000, 1_599));
    admitido(repo, comIndices(1_000, 1_600));
  }

  @Test
  void multiPackIndexAcimaDoTetoInjetadoERecusado() throws Exception {
    final Path repo = naoNascido("midx-teto");
    escreverBytes(objetos(repo).resolve("pack/multi-pack-index"), 1_001);

    recusadoNomeando(repo, comIndices(1_000, P.maxPackIndexTotalBytes()), "multi-pack-index");
    admitido(repo, comIndices(1_001, P.maxPackIndexTotalBytes()));
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void commitGraphComoDiretorioERecusado() throws Exception {
    final Path repo = naoNascido("cgraph-diretorio");
    Files.createDirectories(objetos(repo).resolve("info/commit-graph"));

    recusadoNomeando(repo, P, "commit-graph");
  }

  @Test
  void commitGraphAcimaDoTetoInjetadoERecusadoENoTetoPassa() throws Exception {
    final Path repo = naoNascido("cgraph-teto");
    escreverBytes(objetos(repo).resolve("info/commit-graph"), 1_001);

    recusadoNomeando(repo, comCommitGraph(1_000), "commit-graph");
    admitido(repo, comCommitGraph(1_001));
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "setLength não cria arquivo esparso no NTFS")
  void osTetosDeProducaoSaoOsDoAnalysisConstants() throws Exception {
    final Path idxNoTeto = naoNascido("producao-idx-no-teto");
    esparso(
        objetos(idxNoTeto).resolve("pack").resolve(PACK + ".idx"),
        AnalysisConstants.MAX_PACK_INDEX_BYTES);
    final Path idxAcima = naoNascido("producao-idx-acima");
    esparso(
        objetos(idxAcima).resolve("pack").resolve(PACK + ".idx"),
        AnalysisConstants.MAX_PACK_INDEX_BYTES + 1);
    final Path somaAcima = naoNascido("producao-soma-acima");
    final long metade = AnalysisConstants.MAX_PACK_INDEX_TOTAL_BYTES / 2;
    esparso(objetos(somaAcima).resolve("pack").resolve("pack-" + "e".repeat(40) + ".idx"), metade);
    esparso(objetos(somaAcima).resolve("pack").resolve("pack-" + "f".repeat(40) + ".idx"), metade);
    esparso(objetos(somaAcima).resolve("pack/multi-pack-index"), 1);
    final Path grafo = naoNascido("producao-cgraph-acima");
    esparso(
        objetos(grafo).resolve("info/commit-graph"), AnalysisConstants.MAX_COMMIT_GRAPH_BYTES + 1);

    admitido(idxNoTeto, P);
    recusadoNomeando(idxAcima, P, PACK + ".idx");
    recusado(somaAcima, P);
    recusadoNomeando(grafo, P, "commit-graph");
  }

  @Test
  void preservedNaoVazioERecusadoEVazioPassa() throws Exception {
    final Path vazio = naoNascido("preserved-vazio");
    Files.createDirectories(objetos(vazio).resolve("pack/preserved"));
    final Path cheio = naoNascido("preserved-cheio");
    TestRepositories.write(objetos(cheio), "pack/preserved/" + PACK + ".old-pack", "PACK");

    admitido(vazio, P);
    recusadoNomeando(cheio, P, "preserved");
  }

  @Test
  void graftsComoDiretorioERecusado() throws Exception {
    final Path repo = naoNascido("grafts-diretorio");
    Files.createDirectories(repo.resolve(".git/info/grafts"));

    recusadoNomeando(repo, P, "grafts");
  }

  @Test
  void graftsAcimaDoTetoDeConfigERecusadoEPequenoPassa() throws Exception {
    final Path repo = naoNascido("grafts-teto");
    final int teto = Math.toIntExact(Files.size(repo.resolve(".git/config"))) + 64;
    final Path grafts = repo.resolve(".git/info/grafts");
    Files.createDirectories(grafts.getParent());
    Files.writeString(grafts, "#".repeat(teto + 1));

    recusadoNomeando(repo, comConfig(teto), "grafts");

    Files.writeString(grafts, "0123456789abcdef0123456789abcdef01234567\n");
    admitido(repo, comConfig(teto));
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "unix:nlink não existe no Windows")
  void oJGitSemGuardaLinkaDeVoltaOPackPreservadoEAGuardaRecusa() throws Exception {
    final Path gemeo = real.resolve("gemeo");
    final RevCommit commit = soPreservado(gemeo);
    final Path preservada = mesmoFormatoPreservado(real.resolve("guardado"), commit);

    assertThat(lerCommit(gemeo, commit)).as("o JGit achou o commit no preserved").isEqualTo(commit);
    final List<Path> restaurados;
    try (Stream<Path> pack = Files.list(objetos(gemeo).resolve("pack"))) {
      restaurados = pack.filter(Files::isRegularFile).toList();
    }
    assertThat(restaurados).as("o JGit escreveu em objects/pack").isNotEmpty();
    for (final Path restaurado : restaurados) {
      assertThat(Files.getAttribute(restaurado, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
          .as("%s é um link duro do preservado", restaurado.getFileName())
          .isEqualTo(2);
    }

    recusadoNomeando(preservada, P, "preserved");
  }

  @Test
  void todaLeituraDaAberturaEDaCaminhadaDaRaizEstaNaListaDaGuarda() throws Exception {
    assertThat(FlightRecorder.getFlightRecorder().getEventTypes())
        .extracting(EventType::getName)
        .contains(FILE_READ);
    final Path solto = real.resolve("jfr-solto");
    try (Git git = TestRepositories.init(solto)) {
      TestRepositories.write(solto, "src/main/java/A.java", "class A {}");
      TestRepositories.commitAll(git, "primeiro");
      TestRepositories.write(solto, "src/main/java/B.java", "class B {}");
      TestRepositories.commitAll(git, "segundo");
    }
    final Path empacotado = real.resolve("jfr-empacotado");
    repositorioComCommit(empacotado);
    empacotar(empacotado);
    final Path base = real.resolve("jfr-base");
    final RevCommit doBase = repositorioComCommit(base);
    final Path compartilhado = naoNascido("jfr-compartilhado");
    Files.writeString(alternates(compartilhado), objetos(base) + "\n");
    Files.writeString(compartilhado.resolve(".git/refs/heads/main"), doBase.name() + "\n");
    final List<Path> pontosGit =
        List.of(
            solto.resolve(".git"),
            empacotado.resolve(".git"),
            base.resolve(".git"),
            compartilhado.resolve(".git"));

    for (final Path repo : List.of(solto, empacotado, compartilhado)) {
      final List<Path> lidos = lidosNaAberturaENaCaminhada(repo);

      assertThat(lidos)
          .as(
              "controle positivo (%s): a caminhada da raiz leu o banco de objetos",
              repo.getFileName())
          .anyMatch(
              lido -> pontosGit.stream().anyMatch(g -> lido.startsWith(g.resolve("objects"))));
      for (final Path lido : lidos) {
        final Path pontoGit = pontosGit.stream().filter(lido::startsWith).findFirst().orElse(null);
        assertThat(pontoGit)
            .as("%s foi lido fora de todo .git, abrindo %s", lido, repo.getFileName())
            .isNotNull();
        assertThat(TestRepositories.posix(pontoGit.relativize(lido)))
            .as("%s está fora da lista que a guarda confere", lido)
            .matches(LISTA_DA_GUARDA);
      }
    }
  }

  private List<Path> lidosNaAberturaENaCaminhada(Path repo) throws Exception {
    final Path gravacao =
        Files.createDirectories(real.resolve("jfr")).resolve(repo.getFileName() + ".jfr");
    try (Recording recording = new Recording()) {
      recording.enable(FILE_READ).withoutThreshold();
      recording.start();
      final RepositorySnapshot snapshot = admitido(repo, P);
      RootCommits.firstParentRoot(snapshot.head(), snapshot.root(), () -> false);
      recording.stop();
      recording.dump(gravacao);
    }
    final List<Path> lidos = new ArrayList<>();
    for (final RecordedEvent evento : RecordingFile.readAllEvents(gravacao)) {
      final String caminho = evento.getString("path");
      if (caminho != null
          && Path.of(caminho).startsWith(real)
          && !Path.of(caminho).startsWith(real.resolve("jfr"))) {
        lidos.add(Path.of(caminho));
      }
    }
    return lidos;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "simple-packages",
        "inheritance",
        "cyclic",
        "method-calls",
        "missing-classpath",
        "broken-source",
        "multi-module"
      })
  void todaFixtureEstruturalReproduzidaEAdmitida(String fixture) throws Exception {
    final Path repo = real.resolve(fixture);
    TestRepositories.reproduceCorpusFixture(fixture, repo);

    admitido(repo, P);
  }

  @Test
  void umRepositorioEmpacotadoPeloGcEAdmitido() throws Exception {
    final Path repo = real.resolve("empacotado");
    repositorioComCommit(repo);
    empacotar(repo);

    admitido(repo, P);
  }

  private Path naoNascido(String nome) throws Exception {
    final Path repo = real.resolve(nome);
    TestRepositories.init(repo).close();
    return repo;
  }

  private static RevCommit repositorioComCommit(Path repo) throws Exception {
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      return TestRepositories.commitAll(git, "primeiro");
    }
  }

  private static RevCommit soPreservado(Path repo) throws Exception {
    final RevCommit commit = repositorioComCommit(repo);
    preservar(repo);
    return commit;
  }

  private static Path mesmoFormatoPreservado(Path repo, RevCommit esperado) throws Exception {
    final RevCommit commit = repositorioComCommit(repo);
    assertThat(commit).as("mesma árvore, identidade e data: o mesmo commit").isEqualTo(esperado);
    preservar(repo);
    return repo;
  }

  private static void preservar(Path repo) throws Exception {
    empacotar(repo);
    final Path objetos = objetos(repo);
    try (Stream<Path> entradas = Files.list(objetos)) {
      for (final Path fanout :
          entradas.filter(e -> HEXA_2.matcher(e.getFileName().toString()).matches()).toList()) {
        apagarArvore(fanout);
      }
    }
    final Path pack = objetos.resolve("pack");
    final Path preserved = Files.createDirectories(pack.resolve("preserved"));
    try (Stream<Path> entradas = Files.list(pack)) {
      for (final Path entrada : entradas.filter(Files::isRegularFile).toList()) {
        final String nome = entrada.getFileName().toString();
        if (nome.endsWith(".pack") || nome.endsWith(".idx")) {
          final int ponto = nome.lastIndexOf('.');
          Files.move(
              entrada,
              preserved.resolve(nome.substring(0, ponto) + ".old-" + nome.substring(ponto + 1)));
        } else {
          Files.delete(entrada);
        }
      }
    }
    assertThat(contarSoltos(objetos)).isZero();
  }

  private static RevCommit lerCommit(Path repo, ObjectId id) throws IOException {
    try (Repository repository = abrir(repo);
        RevWalk walk = new RevWalk(repository)) {
      return walk.parseCommit(id);
    }
  }

  private static void empacotar(Path repo) throws Exception {
    try (Repository repository = abrir(repo);
        Git git = Git.wrap(repository)) {
      git.gc().call();
    }
  }

  private static Repository abrir(Path repo) throws IOException {
    return new FileRepositoryBuilder()
        .setFS(FS.DETECTED)
        .addCeilingDirectory(repo.getParent().toFile())
        .findGitDir(repo.toFile())
        .setMustExist(true)
        .build();
  }

  private static Path objetos(Path repo) {
    return repo.resolve(".git/objects");
  }

  private static Path alternates(Path repo) throws IOException {
    return Files.createDirectories(objetos(repo).resolve("info")).resolve("alternates");
  }

  private void encadear(Path repo, String prefixo, int niveis) throws IOException {
    Path anterior = alternates(repo);
    for (int nivel = 1; nivel <= niveis; nivel++) {
      final Path elo =
          Files.createDirectories(real.resolve(prefixo + "/n" + nivel + "/objects/info"))
              .getParent();
      Files.writeString(anterior, elo + "\n");
      anterior = elo.resolve("info/alternates");
    }
  }

  private static String linhaDe(Path inexistente, int bytes) {
    final StringBuilder linha = new StringBuilder(inexistente.toString());
    while (linha.length() + 1 < bytes) {
      linha.append(linha.length() + 2 < bytes ? "/d" : "d");
    }
    return linha.append('\n').toString();
  }

  private static void lixo(Path pasta, int arquivos) throws IOException {
    Files.createDirectories(pasta);
    for (int i = 0; i < arquivos; i++) {
      Files.createFile(pasta.resolve("lixo-" + i));
    }
  }

  private static int contarEntradas(Path objetos) throws IOException {
    int entradas = 0;
    try (Stream<Path> listadas = Files.list(objetos)) {
      for (final Path entrada : listadas.toList()) {
        entradas++;
        final String nome = entrada.getFileName().toString();
        final boolean enumerada = HEXA_2.matcher(nome).matches() || "pack".equals(nome);
        if (enumerada && Files.isDirectory(entrada, LinkOption.NOFOLLOW_LINKS)) {
          try (Stream<Path> dentro = Files.list(entrada)) {
            entradas += Math.toIntExact(dentro.count());
          }
        }
      }
    }
    return entradas;
  }

  private static String acimaDe(int teto) {
    return "para mais de " + teto + " entradas";
  }

  private static int contarSoltos(Path objetos) throws IOException {
    int soltos = 0;
    try (Stream<Path> fanouts = Files.list(objetos)) {
      for (final Path fanout :
          fanouts.filter(e -> HEXA_2.matcher(e.getFileName().toString()).matches()).toList()) {
        try (Stream<Path> dentro = Files.list(fanout)) {
          soltos += Math.toIntExact(dentro.count());
        }
      }
    }
    return soltos;
  }

  private static void escreverBytes(Path arquivo, int bytes) throws IOException {
    Files.createDirectories(arquivo.getParent());
    Files.write(arquivo, new byte[bytes]);
  }

  private static void esparso(Path arquivo, long bytes) throws IOException {
    Files.createDirectories(arquivo.getParent());
    try (RandomAccessFile acesso = new RandomAccessFile(arquivo.toFile(), "rw")) {
      acesso.setLength(bytes);
    }
  }

  private static void apagarArvore(Path raiz) throws IOException {
    try (Stream<Path> entradas = Files.walk(raiz)) {
      for (final Path entrada : entradas.sorted((a, b) -> b.compareTo(a)).toList()) {
        Files.delete(entrada);
      }
    }
  }

  private static RepositoryLimits comConfig(int bytes) {
    return tetos(
        bytes,
        P.maxObjectStoreEntries(),
        P.maxPackIndexBytes(),
        P.maxPackIndexTotalBytes(),
        P.maxCommitGraphBytes());
  }

  private static RepositoryLimits comEntradas(int entradas) {
    return tetos(
        P.maxGitConfigBytes(),
        entradas,
        P.maxPackIndexBytes(),
        P.maxPackIndexTotalBytes(),
        P.maxCommitGraphBytes());
  }

  private static RepositoryLimits comIndices(long cada, long total) {
    return tetos(
        P.maxGitConfigBytes(), P.maxObjectStoreEntries(), cada, total, P.maxCommitGraphBytes());
  }

  private static RepositoryLimits comCommitGraph(long bytes) {
    return tetos(
        P.maxGitConfigBytes(),
        P.maxObjectStoreEntries(),
        P.maxPackIndexBytes(),
        P.maxPackIndexTotalBytes(),
        bytes);
  }

  private static RepositoryLimits tetos(
      int config, int entradas, long indice, long totalIndices, long commitGraph) {
    return new RepositoryLimits(
        P.maxEnumeratedFiles(),
        P.maxDirectoryDepth(),
        P.maxPathBytes(),
        P.maxBuildFileBytes(),
        config,
        P.maxWarnings(),
        P.maxPackedRefsBytes(),
        P.maxGitAlternates(),
        P.maxGitAlternatesDepth(),
        entradas,
        indice,
        totalIndices,
        commitGraph);
  }

  private static RepositorySnapshot admitido(Path repo, RepositoryLimits limites) {
    final RepositoryOutcome<RepositorySnapshot> outcome =
        new RepositoryValidator(limites).inspect(repo);
    assertThat(outcome)
        .as("%s tinha de ser admitido", repo.getFileName())
        .isInstanceOf(RepositoryOutcome.Accepted.class);
    return ((RepositoryOutcome.Accepted<RepositorySnapshot>) outcome).value();
  }

  private static List<String> recusado(Path repo, RepositoryLimits limites) {
    final PrintStream original = System.err;
    final ByteArrayOutputStream capturado = new ByteArrayOutputStream();
    final RepositoryOutcome<RepositorySnapshot> outcome;
    System.setErr(new PrintStream(capturado, true, StandardCharsets.UTF_8));
    try {
      outcome = new RepositoryValidator(limites).inspect(repo);
    } finally {
      System.setErr(original);
    }
    assertThat(outcome)
        .as("%s tinha de ser recusado", repo.getFileName())
        .isEqualTo(NOT_A_GIT_REPOSITORY);
    return capturado.toString(StandardCharsets.UTF_8).lines().toList();
  }

  private static void recusadoNomeando(Path repo, RepositoryLimits limites, String entrada) {
    assertThat(recusado(repo, limites))
        .as("o motivo no stderr nomeia a entrada da regra (%s)", entrada)
        .anyMatch(linha -> linha.contains(entrada));
  }
}
