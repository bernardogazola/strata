package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HermeticSystemReaderTest {

  private static final String PROCESS_START = "jdk.ProcessStart";
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
  void instalarDuasVezesNaoEmbrulhaOLeitorDeNovo() {
    final SystemReader instalado = SystemReader.getInstance();

    HermeticSystemReader.install();

    assertThat(HermeticSystemReader.isInstalled()).isTrue();
    assertThat(SystemReader.getInstance())
        .as("um segundo install embrulharia o hermético nele mesmo")
        .isSameAs(instalado);
  }

  @Test
  void nenhumaVariavelDeAmbienteChegaAoJGit() {
    assertThat(SystemReader.getInstance().getenv("PATH"))
        .as("sem PATH o JGit não acha git, bash nem xcode-select")
        .isNull();
    assertThat(SystemReader.getInstance().getenv("GIT_DIR")).isNull();
    assertThat(SystemReader.getInstance().getenv("HOME")).isNull();
  }

  @Test
  void oNomeDaMaquinaNaoConsultaODns() {
    assertThat(SystemReader.getInstance().getHostname()).isEqualTo("localhost");
  }

  @Test
  void configuracoesDeUsuarioDeSistemaEDoJGitNascemVazias() throws Exception {
    final SystemReader reader = SystemReader.getInstance();

    assertThat(reader.getUserConfig().getSections())
        .as("nem ~/.gitconfig nem configuração XDG")
        .isEmpty();
    assertThat(reader.getSystemConfig().getSections())
        .as("o padrão descobriria /etc/gitconfig rodando git config --system")
        .isEmpty();
    assertThat(reader.getJGitConfig().getSections())
        .as("o FileStoreAttributes gravaria aqui uma seção [filesystem] depois de medir")
        .isEmpty();
  }

  @Test
  void aConfiguracaoDeUsuarioRespondeAResolucaoDeDataParaQualquerSistemaDeArquivos()
      throws Exception {
    final long semResposta = -1;

    final long resolucao =
        SystemReader.getInstance()
            .getUserConfig()
            .getTimeUnit(
                "filesystem",
                "Fornecedor|25|/dev/qualquer",
                "timestampResolution",
                semResposta,
                TimeUnit.NANOSECONDS);

    assertThat(resolucao)
        .as(
            "o FS.FileStoreAttributes só mede (criando .probe-* no .git) quando esta pergunta"
                + " volta sem resposta")
        .isEqualTo(TimeUnit.SECONDS.toNanos(2));
  }

  @Test
  void inspecionarNaoCriaArquivoDeSondagemNemMudaOPontoGit() throws Exception {
    final Path repo = repositorioComUmCommit("sondagem");
    final Path pontoGit = repo.resolve(".git");
    final FileTime antes = Files.getLastModifiedTime(pontoGit);

    final var outcome = new RepositoryValidator().inspect(repo);

    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    try (Stream<Path> entradas = Files.list(pontoGit)) {
      assertThat(entradas.map(entrada -> entrada.getFileName().toString()))
          .noneMatch(nome -> nome.startsWith(".probe-"));
    }
    assertThat(Files.getLastModifiedTime(pontoGit))
        .as("criar e apagar um .probe-* muda a data do diretório mesmo sem deixar rastro")
        .isEqualTo(antes);
  }

  @Test
  void nenhumProcessoNasceNaInspecaoNemOndeOJGitDescobreOGit() throws Exception {
    final Optional<Path> git = gitNoPathReal();
    Assumptions.assumeTrue(
        git.isPresent(),
        "não há git no PATH desta máquina: o JGit não iniciaria nenhum processo mesmo sem o leitor"
            + " hermético, então uma contagem de zero processos aqui não provaria nada");
    assertThat(FlightRecorder.getFlightRecorder().getEventTypes())
        .extracting(EventType::getName)
        .as("um nome de evento que o JFR não conhece grava zero em silêncio")
        .contains(PROCESS_START, FILE_READ);
    final Path gravacao = Files.createDirectories(real.resolve("jfr")).resolve("inspecao.jfr");

    final RepositoryOutcome<RepositorySnapshot> outcome;
    try (Recording recording = new Recording()) {
      recording.enable(PROCESS_START);
      recording.enable(FILE_READ).withoutThreshold();
      recording.start();
      FS.detect().getGitSystemConfig();
      SystemReader.getInstance().openSystemConfig(null, FS.detect()).load();
      outcome = new RepositoryValidator().inspect(repositorioComUmCommit("processos"));
      recording.stop();
      recording.dump(gravacao);
    }
    final List<RecordedEvent> eventos = RecordingFile.readAllEvents(gravacao);

    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    assertThat(eventos)
        .filteredOn(evento -> FILE_READ.equals(evento.getEventType().getName()))
        .as("controle positivo: a gravação estava ligada enquanto a inspeção lia o .git")
        .isNotEmpty();
    assertThat(eventos)
        .filteredOn(evento -> PROCESS_START.equals(evento.getEventType().getName()))
        .as("com %s no PATH, o JGit padrão rodaria git --version e git config --system", git.get())
        .isEmpty();
  }

  @Test
  void oValidadorSeRecusaANascerSemOLeitorHermetico() {
    final SystemReader hermetico = SystemReader.getInstance();
    SystemReader.setInstance(null);
    try {
      assertThatThrownBy(RepositoryValidator::new)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("HermeticSystemReader");
    } finally {
      SystemReader.setInstance(hermetico);
    }
    assertThat(HermeticSystemReader.isInstalled()).isTrue();
  }

  private Path repositorioComUmCommit(String nome) throws Exception {
    final Path repo = real.resolve(nome);
    try (Git git = TestRepositories.init(repo)) {
      TestRepositories.write(repo, "src/main/java/A.java", "class A {}");
      TestRepositories.commitAll(git, "primeiro");
    }
    return repo;
  }

  private static Optional<Path> gitNoPathReal() {
    final String path = System.getenv("PATH");
    if (path == null) {
      return Optional.empty();
    }
    final String executable =
        System.getProperty("os.name").startsWith("Windows") ? "git.exe" : "git";
    return Stream.of(path.split(File.pathSeparator))
        .filter(diretorio -> !diretorio.isEmpty())
        .map(diretorio -> Path.of(diretorio, executable))
        .filter(Files::isExecutable)
        .findFirst();
  }
}
