package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

final class CancellationTokenTest {

  @Test
  void umTokenNovoNaoEstaCanceladoENaoLanca() {
    final CancellationToken token = CancellationToken.create();

    assertThat(token.isCancelled()).isFalse();
    assertThatCode(token::throwIfCancelled).doesNotThrowAnyException();
  }

  @Test
  void cancelarEIdempotenteEFazOPontoDeVerificacaoLancar() {
    final CancellationToken token = CancellationToken.create();

    token.cancel();
    token.cancel();

    assertThat(token.isCancelled()).isTrue();
    assertThatThrownBy(token::throwIfCancelled)
        .isExactlyInstanceOf(CancellationException.class)
        .hasMessage("análise cancelada");
  }

  @Test
  void oFilhoEONetoVeemOCancelamentoDoPai() {
    final CancellationToken raiz = CancellationToken.create();
    final CancellationToken filho = raiz.child();
    final CancellationToken neto = filho.child();

    raiz.cancel();

    assertThat(filho.isCancelled()).isTrue();
    assertThat(neto.isCancelled()).isTrue();
    assertThatThrownBy(neto::throwIfCancelled).isInstanceOf(CancellationException.class);
  }

  @Test
  void oPaiNaoVeOCancelamentoDoFilho() {
    final CancellationToken raiz = CancellationToken.create();
    final CancellationToken filho = raiz.child();

    filho.cancel();

    assertThat(filho.isCancelled()).isTrue();
    assertThat(raiz.isCancelled()).isFalse();
    assertThatCode(raiz::throwIfCancelled).doesNotThrowAnyException();
  }

  @Test
  void doisIrmaosNaoSeVeem() {
    final CancellationToken raiz = CancellationToken.create();
    final CancellationToken principal = raiz.child();
    final CancellationToken irmao = raiz.child();

    principal.cancel();

    assertThat(irmao.isCancelled()).isFalse();
    assertThat(raiz.isCancelled()).isFalse();
  }

  @Test
  void oSupplierAcompanhaOTokenDepoisDeEntregue() {
    final CancellationToken raiz = CancellationToken.create();
    final BooleanSupplier doFilho = raiz.child().asSupplier();

    assertThat(doFilho.getAsBoolean()).isFalse();
    raiz.cancel();
    assertThat(doFilho.getAsBoolean()).isTrue();
  }

  @Test
  void oCancelamentoDeOutraThreadEVisto() throws InterruptedException {
    final CancellationToken token = CancellationToken.create();
    final Thread leitor = new Thread(token::cancel, "leitor");

    leitor.start();
    leitor.join();

    assertThat(token.child().isCancelled()).isTrue();
  }
}
