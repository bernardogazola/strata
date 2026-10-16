package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class RepositoryWalkerTest {

  @TempDir private Path temp;

  private Path real;

  @BeforeEach
  void caminhoReal() throws Exception {
    real = temp.toRealPath();
  }

  @Test
  void noTetoDeProfundidadeAvisaEAVarreduraSegue() throws Exception {
    TestRepositories.write(real, "a/b/c/Fundo.java", "class Fundo {}");
    TestRepositories.write(real, "a/A.java", "class A {}");
    TestRepositories.write(real, "z.txt", "z");

    final var accepted = aceito(walker(limites(1_000, 2, 1_024, 100)).walk(real));

    assertThat(accepted.warnings())
        .as("a/b está no nível 2: o NIO o entrega como entrada, e é ali que o teto é visto")
        .containsExactly(RepositoryIssue.unreadable("a/b"));
    assertThat(accepted.value().javaSources())
        .extracting(JavaSource::relativePath)
        .containsExactly("a/A.java");
    assertThat(accepted.value().totalFileCount()).isEqualTo(2);
  }

  @Test
  void caminhoAcimaDoTetoDeBytesEPuladoEAvisado() throws Exception {
    TestRepositories.write(real, "diretorio-longo/Dentro.java", "class Dentro {}");
    TestRepositories.write(real, "NomeMuitoLongo.java", "class NomeMuitoLongo {}");
    TestRepositories.write(real, "A.java", "class A {}");

    final var accepted = aceito(walker(limites(1_000, 64, 12, 100)).walk(real));

    assertThat(accepted.warnings())
        .containsExactlyInAnyOrder(
            RepositoryIssue.unreadable("diretorio-longo"),
            RepositoryIssue.unreadable("NomeMuitoLongo.java"));
    assertThat(accepted.value().javaSources())
        .extracting(JavaSource::relativePath)
        .as("o arquivo longo é contado mas não registrado; o diretório longo nem é descido")
        .containsExactly("A.java");
    assertThat(accepted.value().totalFileCount()).isEqualTo(2);
  }

  @Test
  void symlinkEmLacoEPendenteNaoSaoFugaEAVarreduraTermina() throws Exception {
    TestRepositories.write(real, "src/A.java", "class A {}");
    TestRepositories.symlink(real.resolve("lacoA"), real.resolve("lacoB"));
    TestRepositories.symlink(real.resolve("lacoB"), real.resolve("lacoA"));
    TestRepositories.symlink(real.resolve("pendente"), real.resolve("nao-existe"));
    TestRepositories.symlink(real.resolve("src/volta"), real.resolve("src"));

    final var accepted = aceito(walker(RepositoryLimits.PRODUCTION).walk(real));

    assertThat(accepted.warnings())
        .as("um link que não resolve não aponta para fora; um que volta à raiz está dentro")
        .isEmpty();
    assertThat(accepted.value().javaSources())
        .extracting(JavaSource::relativePath)
        .containsExactly("src/A.java");
    assertThat(accepted.value().totalFileCount()).isEqualTo(5);
  }

  @Test
  void symlinkInternoParaDiretorioNaoESeguidoNemContaEmDobro() throws Exception {
    TestRepositories.write(real, "src/A.java", "class A {}");
    TestRepositories.write(real, "src/B.java", "class B {}");
    TestRepositories.symlink(real.resolve("atalho"), real.resolve("src"));
    TestRepositories.symlink(real.resolve("Atalho.java"), real.resolve("src/A.java"));

    final var accepted = aceito(walker(RepositoryLimits.PRODUCTION).walk(real));

    assertThat(accepted.warnings()).isEmpty();
    assertThat(accepted.value().javaSources())
        .extracting(JavaSource::relativePath)
        .as("seguir o atalho analisaria A e B duas vezes; o link .java nunca vira fonte")
        .containsExactly("src/A.java", "src/B.java");
    assertThat(accepted.value().totalFileCount()).isEqualTo(4);
  }

  @Test
  void symlinkParaDiretorioDeForaAvisaPeloCaminhoDoProprioLinkENaoEDescido() throws Exception {
    final Path repo = Files.createDirectories(real.resolve("repo"));
    TestRepositories.write(real, "fora/Segredo.java", "class Segredo {}");
    TestRepositories.write(real, "repo-vizinho/Vizinho.java", "class Vizinho {}");
    TestRepositories.symlink(repo.resolve("vazamento"), real.resolve("fora"));
    TestRepositories.symlink(repo.resolve("vizinho"), real.resolve("repo-vizinho"));

    final var accepted = aceito(walker(RepositoryLimits.PRODUCTION).walk(repo));

    assertThat(accepted.warnings())
        .as("repo-vizinho começa com o texto de repo, mas não é componente dele")
        .containsExactlyInAnyOrder(
            RepositoryIssue.pathEscapesRoot("vazamento"),
            RepositoryIssue.pathEscapesRoot("vizinho"));
    assertThat(accepted.value().javaSources()).isEmpty();
    assertThat(accepted.value().totalFileCount()).isEqualTo(2);
  }

  @Test
  void osAvisosParamNoTetoInjetado() throws Exception {
    final Path repo = Files.createDirectories(real.resolve("repo"));
    final Path fora = Files.createDirectories(real.resolve("fora"));
    for (final String nome : new String[] {"um", "dois", "tres"}) {
      TestRepositories.symlink(repo.resolve(nome), fora);
    }

    final var accepted = aceito(walker(limites(1_000, 64, 1_024, 2)).walk(repo));

    assertThat(accepted.warnings())
        .hasSize(2)
        .allMatch(aviso -> aviso.code() == RepositoryIssue.Code.REPO_PATH_ESCAPES_ROOT);
    assertThat(accepted.value().totalFileCount())
        .as("o teto corta o aviso, não a contagem")
        .isEqualTo(3);
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "permissão POSIX não existe no Windows")
  void subdiretorioIlegivelAvisaEAVarreduraSegue() throws Exception {
    TestRepositories.write(real, "trancada/Escondido.java", "class Escondido {}");
    TestRepositories.write(real, "A.java", "class A {}");
    final Path trancada = real.resolve("trancada");
    Files.setPosixFilePermissions(trancada, Set.of());
    try {
      abortarSeAPermissaoNaoMorde(trancada);

      final var accepted = aceito(walker(RepositoryLimits.PRODUCTION).walk(real));

      assertThat(accepted.warnings()).containsExactly(RepositoryIssue.unreadable("trancada"));
      assertThat(accepted.value().javaSources())
          .extracting(JavaSource::relativePath)
          .containsExactly("A.java");
    } finally {
      Files.setPosixFilePermissions(trancada, Set.of(PosixFilePermission.values()));
    }
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "permissão POSIX não existe no Windows")
  void raizIlegivelERecusadaComRepoRootUnreadable() throws Exception {
    final Path raiz = Files.createDirectories(real.resolve("raiz"));
    TestRepositories.write(raiz, "A.java", "class A {}");
    Files.setPosixFilePermissions(raiz, Set.of());
    try {
      abortarSeAPermissaoNaoMorde(raiz);

      assertThat(walker(RepositoryLimits.PRODUCTION).walk(raiz))
          .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.rootUnreadable()));
    } finally {
      Files.setPosixFilePermissions(raiz, Set.of(PosixFilePermission.values()));
    }
  }

  @Test
  void acimaDoTetoDeEntradasParaNaPrimeiraEntradaExcedente() throws Exception {
    TestRepositories.write(real, "a.txt", "a");
    TestRepositories.write(real, "b.txt", "b");
    TestRepositories.write(real, "c.txt", "c");
    TestRepositories.write(real, "d.txt", "d");

    assertThat(walker(limites(2, 64, 1_024, 100)).walk(real))
        .as("found é a entrada que estourou o teto, nunca a contagem total")
        .isEqualTo(new RepositoryOutcome.Rejected<>(RepositoryIssue.tooManyFiles(2, 3)));
  }

  @Test
  void totalContaTodaEntradaQueNaoEDiretorioForaDeQualquerPontoGit() throws Exception {
    TestRepositories.write(real, ".git/HEAD", "ref: refs/heads/main\n");
    TestRepositories.write(real, ".git/objects/ab/cdef", "objeto");
    TestRepositories.write(real, "a.txt", "a");
    TestRepositories.write(real, "sub/b.txt", "b");
    TestRepositories.write(real, "sub/.git", "gitdir: ../.git/modules/sub\n");
    TestRepositories.write(real, "pom.xml", "<project/>");
    TestRepositories.symlink(real.resolve("build.gradle"), real.resolve("a.txt"));

    final var accepted = aceito(walker(RepositoryLimits.PRODUCTION).walk(real));

    assertThat(accepted.value().totalFileCount())
        .as(
            "a.txt, sub/b.txt, pom.xml e o link build.gradle; nada de .git, em profundidade nenhuma")
        .isEqualTo(4);
    assertThat(accepted.value().buildFiles())
        .as("o build.gradle symlink existe (o sistema não é NONE), mas nunca será aberto")
        .containsExactly(
            new BuildFileEntry("build.gradle", false, tamanhoDoProprioLink("build.gradle")),
            new BuildFileEntry("pom.xml", true, Files.size(real.resolve("pom.xml"))));
  }

  @Test
  void fontesSaemOrdenadosComTamanhoEDataDosAtributos() throws Exception {
    TestRepositories.write(real, "b/B.java", "class B {}");
    TestRepositories.write(real, "a/Z.java", "class Z { int z; }");
    TestRepositories.write(real, "a/A.java", "class A {}");

    final var accepted = aceito(walker(RepositoryLimits.PRODUCTION).walk(real));

    final Path z = real.resolve("a/Z.java");
    assertThat(accepted.value().javaSources())
        .containsExactly(
            fonte("a/A.java"),
            new JavaSource("a/Z.java", Files.size(z), Files.getLastModifiedTime(z).toMillis()),
            fonte("b/B.java"));
  }

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "no Windows a barra invertida é separador")
  void barraInvertidaNoNomeFicaComoEstaNoCaminhoRelativo() throws Exception {
    TestRepositories.write(real, "src/a\\b.java", "class B {}");

    final var accepted = aceito(walker(RepositoryLimits.PRODUCTION).walk(real));

    assertThat(accepted.value().javaSources())
        .extracting(JavaSource::relativePath)
        .as("trocar \\ por / inventaria um diretório a que não existe")
        .containsExactly("src/a\\b.java");
  }

  private JavaSource fonte(String relativo) throws Exception {
    final Path arquivo = real.resolve(relativo);
    return new JavaSource(
        relativo, Files.size(arquivo), Files.getLastModifiedTime(arquivo).toMillis());
  }

  private long tamanhoDoProprioLink(String relativo) throws Exception {
    return Files.readAttributes(
            real.resolve(relativo), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .size();
  }

  private static RepositoryWalker walker(RepositoryLimits limits) {
    return new RepositoryWalker(limits);
  }

  private static RepositoryOutcome.Accepted<RepositoryInventory> aceito(
      RepositoryOutcome<RepositoryInventory> outcome) {
    assertThat(outcome).isInstanceOf(RepositoryOutcome.Accepted.class);
    return (RepositoryOutcome.Accepted<RepositoryInventory>) outcome;
  }

  private static RepositoryLimits limites(
      int maxEnumeratedFiles, int maxDirectoryDepth, int maxPathBytes, int maxWarnings) {
    return new RepositoryLimits(
        maxEnumeratedFiles,
        maxDirectoryDepth,
        maxPathBytes,
        RepositoryLimits.PRODUCTION.maxBuildFileBytes(),
        RepositoryLimits.PRODUCTION.maxGitConfigBytes(),
        maxWarnings,
        RepositoryLimits.PRODUCTION.maxPackedRefsBytes());
  }

  private static void abortarSeAPermissaoNaoMorde(Path trancada) {
    Assumptions.assumeFalse(
        Files.isReadable(trancada),
        "este usuário lê um diretório sem permissões (rodando como root?): o caso ilegível"
            + " não pode ser produzido aqui");
  }
}
