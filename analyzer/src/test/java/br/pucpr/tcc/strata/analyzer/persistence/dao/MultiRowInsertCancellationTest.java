package br.pucpr.tcc.strata.analyzer.persistence.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.bootstrap.AnalysisConstants;
import br.pucpr.tcc.strata.analyzer.persistence.SqlTransactions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MultiRowInsertCancellationTest {

  private static final String INSERT_HEAD = "INSERT INTO t (x) VALUES";

  private static final MultiRowInsert.RowBinder<Long> BINDER =
      (statement, first, row) -> statement.setLong(first, row);

  private static final int LINHAS = 3 * AnalysisConstants.ROWS_PER_INSERT + 7;

  private static final int PEDACOS = 4;

  @TempDir private Path temp;

  private Connection connection;

  @BeforeEach
  void abrir() throws Exception {
    connection = DaoTestDatabase.create(temp.toRealPath());
    DaoTestDatabase.execute(connection, "CREATE TABLE t (x INTEGER NOT NULL)");
  }

  @AfterEach
  void fechar() throws SQLException {
    connection.close();
  }

  private static List<Long> linhas(int quantas) {
    return LongStream.range(0, quantas).boxed().toList();
  }

  private long contar() throws SQLException {
    return Long.parseLong(DaoTestDatabase.scalar(connection, "SELECT count(*) FROM t"));
  }

  private static Method sobrecargaCancelavel() {
    final Method overload;
    try {
      overload =
          MultiRowInsert.class.getDeclaredMethod(
              "insertRows",
              Connection.class,
              String.class,
              int.class,
              List.class,
              MultiRowInsert.RowBinder.class,
              BooleanSupplier.class);
    } catch (NoSuchMethodException missing) {
      throw new AssertionError(
          "MultiRowInsert não tem a sobrecarga insertRows(Connection, String, int, List,"
              + " RowBinder, BooleanSupplier), então nenhum bloco de INSERT é ponto de verificação"
              + " de cancelamento",
          missing);
    }
    assertThat(Modifier.isStatic(overload.getModifiers())).as("insertRows é estático").isTrue();
    return overload;
  }

  private static void inserir(
      Method overload, Connection connection, List<Long> rows, BooleanSupplier cancelled)
      throws SQLException {
    try {
      overload.invoke(null, connection, INSERT_HEAD, 1, rows, BINDER, cancelled);
    } catch (IllegalAccessException inaccessible) {
      throw new AssertionError(inaccessible);
    } catch (InvocationTargetException thrown) {
      switch (thrown.getCause()) {
        case SQLException sql -> throw sql;
        case RuntimeException runtime -> throw runtime;
        case Error error -> throw error;
        default -> throw new AssertionError(thrown.getCause());
      }
    }
  }

  @Test
  void semCancelamentoGravaTudoEConsultaAntesDeCadaPedaco() throws Exception {
    final Method overload = sobrecargaCancelavel();
    final AtomicInteger consultas = new AtomicInteger();

    inserir(
        overload,
        connection,
        linhas(LINHAS),
        () -> {
          consultas.incrementAndGet();
          return false;
        });

    assertThat(contar()).isEqualTo(LINHAS);
    assertThat(consultas)
        .as("um ponto de verificação antes de cada pedaço")
        .hasValueGreaterThanOrEqualTo(PEDACOS);
  }

  @Test
  void canceladoDesdeOComecoNaoExecutaPedacoNenhum() throws Exception {
    final Method overload = sobrecargaCancelavel();

    assertThatThrownBy(() -> inserir(overload, connection, linhas(LINHAS), () -> true))
        .isInstanceOf(CancellationException.class);

    assertThat(contar()).isZero();
  }

  @Test
  void umCancelamentoEntreDoisPedacosParaNoProximoSemExecutarOResto() throws Exception {
    final Method overload = sobrecargaCancelavel();
    final int dois = 2 * AnalysisConstants.ROWS_PER_INSERT;
    final BooleanSupplier depoisDoSegundo =
        () -> {
          try {
            return contar() >= dois;
          } catch (SQLException failure) {
            throw new AssertionError(failure);
          }
        };

    assertThatThrownBy(() -> inserir(overload, connection, linhas(LINHAS), depoisDoSegundo))
        .isInstanceOf(CancellationException.class);

    assertThat(contar()).as("nem o terceiro pedaço nem o resto rodaram").isEqualTo(dois);
  }

  @Test
  void dentroDeUmaTransacaoOCancelamentoDesfazAGravacaoInteira() throws Exception {
    final Method overload = sobrecargaCancelavel();
    final AtomicInteger consultasDaSegunda = new AtomicInteger();

    assertThatThrownBy(
            () ->
                SqlTransactions.run(
                    connection,
                    c -> {
                      inserir(overload, c, linhas(LINHAS), () -> false);
                      inserir(
                          overload,
                          c,
                          linhas(LINHAS),
                          () -> consultasDaSegunda.incrementAndGet() >= 2);
                      return null;
                    }))
        .isInstanceOf(CancellationException.class);

    assertThat(contar()).as("0 linhas da gravação cancelada").isZero();
    assertThat(connection.getAutoCommit()).isTrue();
  }
}
