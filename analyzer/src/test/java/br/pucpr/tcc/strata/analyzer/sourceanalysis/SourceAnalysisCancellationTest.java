package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.pucpr.tcc.strata.analyzer.repository.ValidatedRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.compiler.Environment;
import spoon.support.compiler.SpoonProgress;
import spoon.support.modelobs.FineModelChangeListener;

final class SourceAnalysisCancellationTest {

  private static final Map<String, String> TRES_ARQUIVOS =
      Map.of(
          "src/main/java/p/Order.java",
          """
          package p;

                    import java.util.List;

          public class Order {
            private final List<Line> lines = new ArrayList<>();

            int total() {
              int sum = 0;
              for (Line line : lines) {
                sum += line.amount();
              }
              return sum;
            }
          }
          """,
          "src/main/java/p/Line.java",
          "package p;\n\nrecord Line(int amount) {}\n",
          "src/main/java/q/Client.java",
          "package q;\n\nclass Client {\n  p.Order order;\n}\n");

  @TempDir private Path temp;

  private static final class Contador implements BatchVisitor {

    private int begins;
    private int visits;
    private int ends;

    @Override
    public void begin(List<SourceFile> files) {
      begins++;
    }

    @Override
    public void visit(ModeledBatch batch) {
      visits++;
    }

    @Override
    public void end(SourceAnalysis analysis) {
      ends++;
    }
  }

  private static Throwable disparar(FineModelChangeListener ouvinte, Method callback) {
    final Object[] argumentos =
        Arrays.stream(callback.getParameterTypes())
            .map(tipo -> tipo == int.class ? (Object) 0 : null)
            .toArray();
    try {
      callback.invoke(ouvinte, argumentos);
      return null;
    } catch (InvocationTargetException thrown) {
      return thrown.getCause();
    } catch (IllegalAccessException inaccessible) {
      throw new AssertionError(inaccessible);
    }
  }

  @Test
  void oLancadorInstalaAoLadoDoSpoonProgressUmOuvinteQueCancelaEmCadaCallback() throws Exception {
    final Path vazio = Files.createDirectories(temp.resolve("classpath-vazio"));
    final Environment cancelado = SpoonLaunchers.configured(vazio, () -> true).getEnvironment();
    final Environment livre = SpoonLaunchers.configured(vazio, () -> false).getEnvironment();
    assertThatThrownBy(() -> cancelado.getSpoonProgress().start(SpoonProgress.Process.COMPILE))
        .isInstanceOf(CancellationException.class);
    final List<Method> callbacks = List.of(FineModelChangeListener.class.getMethods());
    assertThat(callbacks).as("os 17 callbacks do Spoon 11.5.0").hasSize(17);

    for (final Method callback : callbacks) {
      assertThat(disparar(cancelado.getModelChangeListener(), callback))
          .as(
              "%s do ouvinte que o SpoonLaunchers instala consulta o cancelamento",
              callback.getName())
          .isInstanceOf(CancellationException.class);
      assertThat(disparar(livre.getModelChangeListener(), callback))
          .as("%s sem cancelamento não lança", callback.getName())
          .isNull();
    }
  }

  private static final class NoPrimeiroCallback implements BooleanSupplier {

    private static final StackWalker PILHA =
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final AtomicBoolean virado = new AtomicBoolean();

    @Override
    public boolean getAsBoolean() {
      if (!virado.get()
          && PILHA.walk(
              quadros ->
                  quadros.anyMatch(
                      quadro ->
                          FineModelChangeListener.class.isAssignableFrom(
                              quadro.getDeclaringClass())))) {
        virado.set(true);
      }
      return virado.get();
    }
  }

  private static boolean lancadaNumCallback(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      for (final StackTraceElement frame : current.getStackTrace()) {
        if (implementaOuvinte(frame.getClassName())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean implementaOuvinte(String className) {
    try {
      return FineModelChangeListener.class.isAssignableFrom(
          Class.forName(className, false, SourceAnalysisCancellationTest.class.getClassLoader()));
    } catch (ClassNotFoundException | LinkageError unknown) {
      return false;
    }
  }

  @Test
  void umCancelamentoNoPrimeiroCallbackDoOuvinteEncerraOBuildSemVirarFalhaDeArquivo()
      throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(temp.resolve("ouvinte"), TRES_ARQUIVOS);
    final NoPrimeiroCallback cancelamento = new NoPrimeiroCallback();
    final Contador visitante = new Contador();
    final PrintStream original = System.err;
    final ByteArrayOutputStream capturado = new ByteArrayOutputStream();
    final Throwable falha;
    System.setErr(new PrintStream(capturado, true, StandardCharsets.UTF_8));
    try {
      falha =
          catchThrowable(
              () ->
                  new SpoonModelBuilder()
                      .analyze(
                          repository, repository.layout().sourceRoots(), visitante, cancelamento));
    } finally {
      System.setErr(original);
    }
    final List<String> log = capturado.toString(StandardCharsets.UTF_8).lines().toList();

    assertThat(falha)
        .as(
            "o ouvinte de mudança do modelo consulta o cancelamento durante o build;"
                + " sem ele o build vai até o fim e o analyze volta normalmente")
        .isInstanceOf(CancellationException.class);
    assertThat(lancadaNumCallback(falha))
        .as(
            "o CancellationException sai do callback e atravessa o buildModel() intacto (nu,"
                + " sem causa), sem passar pelo caminho de falha do build")
        .isTrue();
    assertThat(visitante.begins).isEqualTo(1);
    assertThat(visitante.visits).isZero();
    assertThat(visitante.ends).isZero();
    assertThat(log)
        .noneMatch(line -> line.contains("o build do modelo de"))
        .noneMatch(line -> line.contains("a extração falhou em"));
  }

  @Test
  void umCancelamentoDepoisDoUltimoArquivoDoPreParseEncerraAntesDoBegin() throws Exception {
    final ValidatedRepository repository =
        SourceTrees.repository(temp.resolve("indice"), TRES_ARQUIVOS);
    final int arquivos = repository.snapshot().inventory().javaSources().size();
    final AtomicInteger consultas = new AtomicInteger();
    final BooleanSupplier depoisDoPreParse = () -> consultas.incrementAndGet() > arquivos;
    final Contador visitante = new Contador();

    final Throwable falha =
        catchThrowable(
            () ->
                new SpoonModelBuilder()
                    .analyze(
                        repository,
                        repository.layout().sourceRoots(),
                        visitante,
                        depoisDoPreParse));

    assertThat(arquivos).isEqualTo(3);
    assertThat(falha).isInstanceOf(CancellationException.class);
    assertThat(visitante.begins)
        .as(
            "o cancelamento entre o buildIndex e o visitor.begin encerra a análise"
                + " antes do begin, que é onde o ElementWriter grava os source_file")
        .isZero();
    assertThat(visitante.visits).isZero();
  }
}
