package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

final class SqliteConnectionsTest {

  private static final long NOT_YET_MILLIS = 300;

  private static final long DONE_SECONDS = 10;

  @TempDir private Path temp;

  private Path database;

  @BeforeEach
  void arquivo() throws Exception {
    database = temp.toRealPath().resolve("analysis.sqlite");
  }

  private static void criarProbe(SqliteConnections connections) throws SQLException {
    connections.write(
        c -> {
          executar(c, "CREATE TABLE probe (x INTEGER) STRICT");
          return null;
        });
  }

  private static void executar(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.executeUpdate();
    }
  }

  private static String escrito(SqliteConnections connections, String sql) throws SQLException {
    return connections.write(c -> TestCaches.scalar(c, sql));
  }

  private static String contar(SqliteConnections connections) throws SQLException {
    return connections.read(c -> TestCaches.scalar(c, "SELECT count(*) FROM probe"));
  }

  private static void aguardar(CountDownLatch latch) {
    try {
      if (!latch.await(DONE_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("o latch nunca foi liberado");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Test
  void umaQuintaLeituraEsperaUmDosQuatroLeitoresSerDevolvido() throws Exception {
    final int readers = AnalysisConstants.SQLITE_READER_CONNECTIONS;
    final Set<Connection> vistos = ConcurrentHashMap.newKeySet();
    final CountDownLatch dentro = new CountDownLatch(readers);
    final CountDownLatch soltar = new CountDownLatch(1);
    final CountDownLatch quintaDentro = new CountDownLatch(1);
    final ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      final List<Future<String>> leituras = new ArrayList<>();
      for (int index = 0; index < readers; index++) {
        leituras.add(
            pool.submit(
                () ->
                    connections.read(
                        c -> {
                          vistos.add(c);
                          dentro.countDown();
                          aguardar(soltar);
                          return TestCaches.scalar(c, "SELECT 1");
                        })));
      }
      aguardar(dentro);

      final Future<String> quinta =
          pool.submit(
              () ->
                  connections.read(
                      c -> {
                        vistos.add(c);
                        quintaDentro.countDown();
                        return TestCaches.scalar(c, "SELECT 1");
                      }));

      assertThat(quintaDentro.await(NOT_YET_MILLIS, TimeUnit.MILLISECONDS))
          .as("os quatro leitores estão emprestados; a quinta leitura espera")
          .isFalse();
      soltar.countDown();
      for (final Future<String> leitura : leituras) {
        assertThat(leitura.get(DONE_SECONDS, TimeUnit.SECONDS)).isEqualTo("1");
      }
      assertThat(quinta.get(DONE_SECONDS, TimeUnit.SECONDS)).isEqualTo("1");
      assertThat(vistos)
          .as("a quinta reusou um leitor devolvido: nunca mais de quatro")
          .hasSize(readers);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void umLeitorCujaUnidadeFalhaEhFechadoEAProximaLeituraAbreOutro() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      criarProbe(connections);
      final Connection primeiro = connections.read(c -> c);

      assertThatThrownBy(
              () ->
                  connections.read(
                      c -> {
                        executar(c, "INSERT INTO probe (x) VALUES (1)");
                        return null;
                      }))
          .isInstanceOf(SQLiteException.class);
      final Connection seguinte = connections.read(c -> c);

      assertThat(primeiro.isClosed())
          .as("o leitor da unidade que falhou não volta para o empréstimo")
          .isTrue();
      assertThat(seguinte).isNotSameAs(primeiro);
      assertThat(contar(connections)).isEqualTo("0");
    }
  }

  @Test
  void fecharEsperaAUnidadeDeEscritaEmAndamentoTerminar() throws Exception {
    final SqliteConnections connections = SqliteConnections.open(database);
    criarProbe(connections);
    final CountDownLatch dentro = new CountDownLatch(1);
    final CountDownLatch soltar = new CountDownLatch(1);
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      final Future<Object> escrita =
          pool.submit(
              () ->
                  connections.write(
                      c -> {
                        executar(c, "INSERT INTO probe (x) VALUES (1)");
                        dentro.countDown();
                        aguardar(soltar);
                        return null;
                      }));
      aguardar(dentro);
      final Future<Object> fechamento =
          pool.submit(
              () -> {
                connections.close();
                return null;
              });

      assertThatThrownBy(() -> fechamento.get(NOT_YET_MILLIS, TimeUnit.MILLISECONDS))
          .as("o escritor está no meio de uma unidade; fechar espera")
          .isInstanceOf(TimeoutException.class);
      soltar.countDown();
      escrita.get(DONE_SECONDS, TimeUnit.SECONDS);
      fechamento.get(DONE_SECONDS, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    try (Connection raw = TestCaches.raw(database)) {
      assertThat(TestCaches.scalar(raw, "SELECT count(*) FROM probe")).isEqualTo("1");
    }
  }

  @Test
  void depoisDeFechadoNenhumaUnidadeRoda() throws Exception {
    final SqliteConnections connections = SqliteConnections.open(database);
    connections.close();

    assertThatThrownBy(() -> connections.write(c -> null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> connections.read(c -> null)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> connections.withWriter(c -> null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void umaUnidadeDoEscritorDentroDeOutraNaMesmaThreadEhDefeitoENaoTravamento() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      criarProbe(connections);

      assertThatThrownBy(() -> connections.write(c -> connections.write(inner -> null)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("rodando aqui");
      assertThatThrownBy(() -> connections.write(c -> connections.withWriter(inner -> null)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("rodando aqui");
      assertThatThrownBy(() -> connections.withWriter(c -> connections.write(inner -> null)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("rodando aqui");
      assertThat(escrito(connections, "SELECT 1")).isEqualTo("1");
    }
  }

  @Test
  void withWriterEntregaOEscritorEmAutocommitParaDuasTransacoesEmSequencia() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      final Boolean autocommit =
          connections.withWriter(
              writer -> {
                final boolean recebido = writer.getAutoCommit();
                SqlTransactions.run(
                    writer,
                    c -> {
                      executar(c, "CREATE TABLE probe (x INTEGER) STRICT");
                      return null;
                    });
                SqlTransactions.run(
                    writer,
                    c -> {
                      executar(c, "INSERT INTO probe (x) VALUES (1)");
                      return null;
                    });
                return recebido;
              });

      assertThat(autocommit).isTrue();
      assertThat(contar(connections)).isEqualTo("1");
    }
  }

  @Test
  void withWriterRecusaUmTrabalhoQueDeixaUmaTransacaoAbertaEADesfaz() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      criarProbe(connections);

      assertThatThrownBy(
              () ->
                  connections.withWriter(
                      writer -> {
                        writer.setAutoCommit(false);
                        executar(writer, "INSERT INTO probe (x) VALUES (1)");
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("o trabalho deixou uma transação aberta");

      assertThat(contar(connections)).isEqualTo("0");
      assertThat(connections.withWriter(Connection::getAutoCommit))
          .as("o escritor voltou ao autocommit: a unidade seguinte abre a própria transação")
          .isTrue();
    }
  }

  @Test
  void withWriterPropagaAExcecaoVerificadaDoTrabalhoSemEmbrulhoEDesfazOQueFicouAberto()
      throws Exception {
    final CacheDiscardException descarte =
        new CacheDiscardException(new CacheInvalidation.Corrupt(), "probe");
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      criarProbe(connections);

      assertThatThrownBy(
              () ->
                  connections.withWriter(
                      writer -> {
                        writer.setAutoCommit(false);
                        executar(writer, "INSERT INTO probe (x) VALUES (1)");
                        throw descarte;
                      }))
          .isSameAs(descarte);

      assertThat(descarte.getSuppressed()).isEmpty();
      assertThat(contar(connections)).isEqualTo("0");
      assertThat(connections.withWriter(Connection::getAutoCommit)).isTrue();
    }
  }

  @Test
  void umArquivoVazioContaComoCriadoERecebeAutoVacuumIncremental() throws Exception {
    Files.createFile(database);

    try (SqliteConnections connections = SqliteConnections.open(database)) {
      assertThat(connections.created())
          .as("um arquivo de 0 bytes é o que sobra de uma criação interrompida")
          .isTrue();
      assertThat(escrito(connections, "PRAGMA auto_vacuum")).isEqualTo("2");
      assertThat(connections.databaseFile()).isEqualTo(database);
    }
  }

  @Test
  void abrirUmArquivoQueNaoEhBancoLancaAFalhaOriginalQueARecuperacaoClassificaComoCorrupcao()
      throws Exception {
    TestCaches.overwrite(database, 0, 512, 7L);

    assertThatThrownBy(() -> SqliteConnections.open(database))
        .isInstanceOf(SQLiteException.class)
        .satisfies(
            failure -> {
              assertThat(((SQLiteException) failure).getResultCode().code & 0xFF)
                  .isEqualTo(SQLiteErrorCode.SQLITE_NOTADB.code);
              assertThat(CacheRecovery.classify(failure)).isEqualTo(CacheRecovery.Verdict.CORRUPT);
            });
    assertThat(Files.size(database)).as("nada escreveu no arquivo estragado").isEqualTo(512);
  }

  @Test
  void umLeitorNumArquivoForaDoWalEhRecusadoSemMudarOModo() throws Exception {
    try (Connection raw = TestCaches.raw(database)) {
      executar(raw, "CREATE TABLE probe (x INTEGER) STRICT");
    }
    final SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);

    try (Connection reader = config.createConnection("jdbc:sqlite:" + database)) {
      assertThatThrownBy(() -> SqlitePragmas.applyToReader(reader))
          .isInstanceOf(PragmaRefusedException.class)
          .hasMessageContaining("journal_mode");
    }
    try (Connection raw = TestCaches.raw(database)) {
      assertThat(TestCaches.scalar(raw, "PRAGMA journal_mode")).isEqualTo("delete");
    }
  }

  @Test
  @DisabledOnOs(
      value = OS.WINDOWS,
      disabledReason = "o leitor envenenado segura o arquivo, e o Windows não apaga o @TempDir")
  void fecharSegueDepoisDeUmPassoQueFalhaELancaAPrimeiraFalha() throws Exception {
    final SqliteConnections connections = SqliteConnections.open(database);
    criarProbe(connections);
    connections.write(
        c -> {
          executar(c, "INSERT INTO probe (x) VALUES (1)");
          return null;
        });
    connections.read(
        c -> {
          assertThatThrownBy(() -> c.prepareStatement("")).isInstanceOf(SQLException.class);
          return null;
        });
    final Path wal = TestCaches.sibling(database, "-wal");
    assertThat(wal).isNotEmptyFile();

    assertThatThrownBy(connections::close)
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("finalized");

    assertThat(Files.notExists(wal) || Files.size(wal) == 0)
        .as("o checkpoint rodou depois da falha do leitor")
        .isTrue();
    try (Connection raw = TestCaches.raw(database)) {
      assertThat(TestCaches.scalar(raw, "SELECT count(*) FROM probe")).isEqualTo("1");
    }
  }

  @Test
  void umFechamentoQueDeuCertoDizQueTudoFechouEFecharDeNovoNaoFazNada() throws Exception {
    final SqliteConnections connections = SqliteConnections.open(database);
    criarProbe(connections);
    assertThat(contar(connections)).isEqualTo("0");
    assertThat(connections.allClosed()).as("aberto, com um leitor ocioso").isFalse();

    connections.close();
    connections.close();

    assertThat(connections.allClosed()).isTrue();
  }

  @Test
  @DisabledOnOs(
      value = OS.WINDOWS,
      disabledReason = "o leitor envenenado segura o arquivo, e o Windows não apaga o @TempDir")
  void umLeitorCujaUnidadeEOFechamentoFalhamContinuaRastreadoEOFechamentoOFecha() throws Exception {
    final SqliteConnections connections = SqliteConnections.open(database);
    final AtomicReference<Connection> perdido = new AtomicReference<>();

    final Throwable falha =
        catchThrowable(
            () ->
                connections.read(
                    c -> {
                      perdido.set(c);
                      assertThatThrownBy(() -> c.prepareStatement(""))
                          .isInstanceOf(SQLException.class);
                      throw new SQLException("a unidade falhou");
                    }));

    assertThat(falha).hasMessage("a unidade falhou");
    assertThat(Arrays.stream(falha.getSuppressed()).map(Throwable::getMessage))
        .as("o close() da limpeza falhou e foi suprimido na falha da unidade")
        .anyMatch(message -> message.contains("finalized"));
    assertThat(perdido.get().isClosed()).isFalse();

    connections.close();

    assertThat(perdido.get().isClosed()).isTrue();
    assertThat(connections.allClosed()).isTrue();
  }

  @Test
  @DisabledOnOs(
      value = OS.WINDOWS,
      disabledReason = "o leitor envenenado segura o arquivo, e o Windows não apaga o @TempDir")
  void umFechamentoQueDeixaUmLeitorAbertoDizIssoEOFechamentoSeguinteTentaDeNovo() throws Exception {
    final SqliteConnections connections = SqliteConnections.open(database);
    final Connection envenenado =
        connections.read(
            c -> {
              assertThatThrownBy(() -> c.prepareStatement("")).isInstanceOf(SQLException.class);
              return c;
            });

    assertThatThrownBy(connections::close)
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("finalized");
    assertThat(envenenado.isClosed()).isFalse();
    assertThat(connections.allClosed())
        .as("quem ia renomear o arquivo ou soltar o lock fica sabendo")
        .isFalse();

    connections.close();

    assertThat(envenenado.isClosed()).as("fechar de novo tentou de novo").isTrue();
    assertThat(connections.allClosed()).isTrue();
  }

  @Test
  void umErroNaUnidadeDeLeituraFechaOLeitorENaoODevolve() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      final AtomicReference<Connection> doErro = new AtomicReference<>();

      assertThatThrownBy(
              () ->
                  connections.read(
                      c -> {
                        doErro.set(c);
                        throw new StackOverflowError("probe");
                      }))
          .isInstanceOf(StackOverflowError.class);
      final Connection seguinte = connections.read(c -> c);

      assertThat(doErro.get().isClosed()).isTrue();
      assertThat(seguinte).isNotSameAs(doErro.get());
    }
  }

  @Test
  void umErroNoTrabalhoDoWithWriterDesfazATransacaoEDevolveOAutocommit() throws Exception {
    try (SqliteConnections connections = SqliteConnections.open(database)) {
      criarProbe(connections);

      assertThatThrownBy(
              () ->
                  connections.withWriter(
                      writer -> {
                        writer.setAutoCommit(false);
                        executar(writer, "INSERT INTO probe (x) VALUES (1)");
                        throw new StackOverflowError("probe");
                      }))
          .isInstanceOf(StackOverflowError.class);

      assertThat(contar(connections)).isEqualTo("0");
      assertThat(connections.withWriter(Connection::getAutoCommit)).isTrue();
    }
  }
}
