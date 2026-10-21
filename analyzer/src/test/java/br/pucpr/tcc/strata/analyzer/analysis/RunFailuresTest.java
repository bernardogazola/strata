package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.pucpr.tcc.strata.analyzer.persistence.CacheInvalidation;
import br.pucpr.tcc.strata.analyzer.persistence.CacheUnavailableException;
import br.pucpr.tcc.strata.analyzer.persistence.TestWriteFailures;
import br.pucpr.tcc.strata.analyzer.protocol.ErrorCodes;
import br.pucpr.tcc.strata.analyzer.protocol.ProtocolError;
import br.pucpr.tcc.strata.analyzer.repository.RepositoryIssue;
import java.sql.SQLException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

final class RunFailuresTest {

  private static final String CAMINHO = "/Users/alguem/projetos/segredo/analysis.sqlite";

  private static final ProtocolError SEM_JAVA =
      ErrorCodes.repository(RepositoryIssue.noJavaSources());

  private static final String ANALYSIS_CACHE_CORRUPTED = "ANALYSIS_CACHE_CORRUPTED";

  private static RunFailures.Verdict classificar(Throwable original) {
    return RunFailures.classify(original, false, null);
  }

  private static SQLiteException sqlite(SQLiteErrorCode codigo) {
    return new SQLiteException("falhou em " + CAMINHO, codigo);
  }

  private static RunFailures.Failed falhou(RunFailures.Verdict veredito) {
    assertThat(veredito).isInstanceOf(RunFailures.Failed.class);
    return (RunFailures.Failed) veredito;
  }

  private static void corrompido(RunFailures.Verdict veredito) {
    final RunFailures.Failed falha = falhou(veredito);
    assertThat(falha.corruptCache())
        .as("o executor reconstrói o cache e emite o cache.invalidated antes do terminal")
        .isTrue();
    assertThat(falha.error().code())
        .as("um código novo, e não o CACHE_CORRUPT do cache.invalidated")
        .isEqualTo(ANALYSIS_CACHE_CORRUPTED);
    assertThat(falha.error().params()).isEmpty();
    assertThat(falha.error().origin()).isEqualTo(ErrorCodes.ORIGIN_ANALYZER);
    assertThat(falha.error().detail()).doesNotContain(CAMINHO);
  }

  @Test
  void cancellationExceptionComOCancelamentoPedidoECancelled() {
    assertThat(RunFailures.classify(new CancellationException("análise cancelada"), true, null))
        .isEqualTo(new RunFailures.Cancelled(false));
  }

  @Test
  void cancellationExceptionSemCancelamentoESemIrmaoEDefeito() {
    final RunFailures.Failed falha =
        falhou(classificar(new CancellationException("análise cancelada")));

    assertThat(falha.error())
        .as("ninguém cancelou: é defeito, e o tipo sozinho não faz um cancelamento")
        .isEqualTo(ErrorCodes.internalUnexpected(CancellationException.class.getName()));
    assertThat(falha.corruptCache()).isFalse();
  }

  @Test
  void cancellationExceptionSemCancelamentoComIrmaoQueFalhouEAFalhaDoIrmao() {
    final RunFailures.Verdict veredito =
        RunFailures.classify(
            new CancellationException("análise cancelada"),
            false,
            new StageFailedException(SEM_JAVA));

    assertThat(veredito)
        .as("a falha do irmão cancelou este membro do par; a análise falha com a original")
        .isEqualTo(new RunFailures.Failed(SEM_JAVA, false));
  }

  @Test
  void aFalhaDoIrmaoTambemEClassificadaPelaTabela() {
    final RunFailures.Verdict corrompido =
        RunFailures.classify(
            new CancellationException("análise cancelada"),
            false,
            TestWriteFailures.writeFailed(sqlite(SQLiteErrorCode.SQLITE_CORRUPT)));
    final RunFailures.Verdict defeito =
        RunFailures.classify(
            new CancellationException("análise cancelada"),
            false,
            new IllegalStateException("estado em " + CAMINHO));

    corrompido(corrompido);
    assertThat(defeito)
        .isEqualTo(
            new RunFailures.Failed(
                ErrorCodes.internalUnexpected(IllegalStateException.class.getName()), false));
  }

  @Test
  void comOCancelamentoPedidoUmaRecusaDeEstagioAindaECancelled() {
    assertThat(RunFailures.classify(new StageFailedException(SEM_JAVA), true, null))
        .as("{cancelled: true} promete analysis.cancelled")
        .isEqualTo(new RunFailures.Cancelled(false));
  }

  @Test
  void comOCancelamentoPedidoUmDefeitoAindaECancelled() {
    assertThat(RunFailures.classify(new IllegalStateException(CAMINHO), true, null))
        .isEqualTo(new RunFailures.Cancelled(false));
    assertThat(
            RunFailures.classify(
                new CancellationException("análise cancelada"),
                true,
                new StageFailedException(SEM_JAVA)))
        .as("nem a falha do irmão muda o desfecho de um cancelamento pedido")
        .isEqualTo(new RunFailures.Cancelled(false));
  }

  @Test
  void comOCancelamentoPedidoUmaCorrupcaoAindaReconstroi() {
    assertThat(RunFailures.classify(sqlite(SQLiteErrorCode.SQLITE_CORRUPT), true, null))
        .as("CANCELLED, e mesmo assim o cache é reconstruído e o cache.invalidated sai antes")
        .isEqualTo(new RunFailures.Cancelled(true));
    assertThat(
            RunFailures.classify(
                TestWriteFailures.writeFailed(sqlite(SQLiteErrorCode.SQLITE_NOTADB)), true, null))
        .isEqualTo(new RunFailures.Cancelled(true));
    assertThat(RunFailures.classify(sqlite(SQLiteErrorCode.SQLITE_FULL), true, null))
        .as("ambiente não é corrupção, cancelado ou não")
        .isEqualTo(new RunFailures.Cancelled(false));
  }

  @Test
  void stageFailedExceptionFalhaComOSeuErro() {
    final ProtocolError demais = ErrorCodes.tooManyJavaSources(60_000, 60_001);
    final ProtocolError payload =
        ErrorCodes.invalidPayload("analysis.start", "sourceRootId desconhecido");

    assertThat(classificar(new StageFailedException(SEM_JAVA)))
        .isEqualTo(new RunFailures.Failed(SEM_JAVA, false));
    assertThat(classificar(new StageFailedException(demais)))
        .isEqualTo(new RunFailures.Failed(demais, false));
    assertThat(classificar(new StageFailedException(payload)))
        .isEqualTo(new RunFailures.Failed(payload, false));
  }

  @ParameterizedTest
  @EnumSource(
      value = SQLiteErrorCode.class,
      names = {"SQLITE_CORRUPT", "SQLITE_CORRUPT_INDEX", "SQLITE_NOTADB"})
  void corrupcaoNoMeioDaAnaliseFalhaComAnalysisCacheCorruptedEReconstroi(SQLiteErrorCode codigo) {
    corrompido(classificar(TestWriteFailures.writeFailed(sqlite(codigo))));
    corrompido(classificar(sqlite(codigo)));
  }

  @ParameterizedTest
  @EnumSource(
      value = SQLiteErrorCode.class,
      names = {"SQLITE_FULL", "SQLITE_IOERR", "SQLITE_READONLY", "SQLITE_BUSY", "SQLITE_CANTOPEN"})
  void ambienteNaGravacaoFalhaComCacheUnavailableStorageSemReconstruir(SQLiteErrorCode codigo) {
    final ProtocolError storage =
        ErrorCodes.cacheUnavailable(CacheUnavailableException.Kind.STORAGE);

    assertThat(classificar(TestWriteFailures.writeFailed(sqlite(codigo))))
        .isEqualTo(new RunFailures.Failed(storage, false));
    assertThat(classificar(sqlite(codigo))).isEqualTo(new RunFailures.Failed(storage, false));
  }

  @ParameterizedTest
  @EnumSource(
      value = SQLiteErrorCode.class,
      names = {"SQLITE_ERROR", "SQLITE_CONSTRAINT", "SQLITE_MISMATCH"})
  void oNossoSqlNaGravacaoEDefeitoSemReconstruirESemAMensagem(SQLiteErrorCode codigo) {
    for (final Throwable original :
        new Throwable[] {TestWriteFailures.writeFailed(sqlite(codigo)), sqlite(codigo)}) {
      final RunFailures.Failed falha = falhou(classificar(original));

      assertThat(falha.corruptCache()).isFalse();
      assertThat(falha.error().code()).isEqualTo(ErrorCodes.INTERNAL_UNEXPECTED);
      assertThat(falha.error().detail())
          .as("o detalhe é um nome de classe, nunca a mensagem do SQLite")
          .matches("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+$")
          .doesNotContain(CAMINHO);
    }
  }

  @Test
  void umaSqlExceptionQueNaoVemDoSqliteEDefeito() {
    final RunFailures.Failed falha = falhou(classificar(new SQLException("falhou em " + CAMINHO)));

    assertThat(falha.error().code()).isEqualTo(ErrorCodes.INTERNAL_UNEXPECTED);
    assertThat(falha.error().detail()).doesNotContain(CAMINHO);
    assertThat(falha.corruptCache()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(CacheUnavailableException.Kind.class)
  void cacheIndisponivelFalhaComACausaDoKind(CacheUnavailableException.Kind kind) {
    final CacheUnavailableException indisponivel =
        new CacheUnavailableException(kind, "o cache em " + CAMINHO, null);

    final RunFailures.Failed falha = falhou(classificar(indisponivel));

    assertThat(falha).isEqualTo(new RunFailures.Failed(ErrorCodes.cacheUnavailable(kind), false));
    assertThat(falha.error().detail()).doesNotContain(CAMINHO);
  }

  @Test
  void cacheIndisponivelDepoisDeUmDescarteFalhaDoMesmoJeito() {
    final CacheUnavailableException depoisDoDescarte =
        new CacheUnavailableException(
            CacheUnavailableException.Kind.STORAGE,
            "a reconstrução em " + CAMINHO + " falhou",
            sqlite(SQLiteErrorCode.SQLITE_FULL),
            new CacheInvalidation.Corrupt());

    assertThat(classificar(depoisDoDescarte))
        .as("o cache.invalidated do discarded() é do executor, antes do terminal; o erro é o mesmo")
        .isEqualTo(
            new RunFailures.Failed(
                ErrorCodes.cacheUnavailable(CacheUnavailableException.Kind.STORAGE), false));
  }

  @Test
  void outraRuntimeExceptionEDefeitoComONomeDaClasseESemAMensagem() {
    assertThat(classificar(new IllegalArgumentException("o arquivo " + CAMINHO)))
        .isEqualTo(
            new RunFailures.Failed(
                ErrorCodes.internalUnexpected(IllegalArgumentException.class.getName()), false));
    assertThat(classificar(new NullPointerException(CAMINHO)))
        .isEqualTo(
            new RunFailures.Failed(
                ErrorCodes.internalUnexpected(NullPointerException.class.getName()), false));
  }

  @Test
  void umaRuntimeExceptionQueEmbrulhaUmaCorrupcaoNaoEClassificadaPelaCausa() {
    final RuntimeException embrulho =
        new IllegalStateException("embrulho", sqlite(SQLiteErrorCode.SQLITE_CORRUPT));

    assertThat(classificar(embrulho))
        .as("só a WriteFailedException é desembrulhada: a causa de outra exceção não reconstrói")
        .isEqualTo(
            new RunFailures.Failed(
                ErrorCodes.internalUnexpected(IllegalStateException.class.getName()), false));
  }
}
