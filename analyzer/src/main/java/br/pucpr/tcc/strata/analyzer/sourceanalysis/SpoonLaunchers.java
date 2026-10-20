package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.reflect.factory.ClassFactory;
import spoon.reflect.factory.Factory;
import spoon.reflect.factory.FactoryImpl;
import spoon.support.DefaultCoreFactory;
import spoon.support.compiler.SpoonProgress;

final class SpoonLaunchers {

  private static final ClassLoader PLATFORM_ONLY = new PlatformOnlyClassLoader();

  private SpoonLaunchers() {}

  static Launcher configured(Path emptyClasspath, BooleanSupplier cancelled) {
    Objects.requireNonNull(emptyClasspath, "emptyClasspath");
    Objects.requireNonNull(cancelled, "cancelled");
    final Launcher launcher = new GuardedLauncher();
    final Environment environment = launcher.getEnvironment();
    environment.setNoClasspath(true);
    environment.setIgnoreSyntaxErrors(true);
    environment.setCommentEnabled(false);
    environment.setComplianceLevel(SpoonModelBuilder.COMPLIANCE_LEVEL);
    // Sem classpath declarado, o ECJ usa o java.class.path do próprio analisador. Uma pasta
    // vazia é o único classpath que não traz jar nenhum.
    environment.setSourceClasspath(new String[] {emptyClasspath.toString()});
    environment.setInputClassLoader(PLATFORM_ONLY);
    environment.setEncodingProvider((file, content) -> SourceCharsets.detect(content));
    environment.setSpoonProgress(new CancellationCheckpoint(cancelled));
    return launcher;
  }

  private static final class GuardedLauncher extends Launcher {

    @Override
    public Factory createFactory() {
      return new GuardedFactory(createEnvironment());
    }
  }

  @SuppressWarnings("overrides")
  private static final class GuardedFactory extends FactoryImpl {

    private static final long serialVersionUID = 1L;

    private final transient ClassFactory classes = new GuardedClassFactory(this);

    private GuardedFactory(Environment environment) {
      super(new DefaultCoreFactory(), environment);
    }

    @Override
    public ClassFactory Class() {
      return classes;
    }
  }

  private static final class PlatformOnlyClassLoader extends ClassLoader {

    private PlatformOnlyClassLoader() {
      super("strata-spoon-platform-only", ClassLoader.getPlatformClassLoader());
    }
  }

  private static final class CancellationCheckpoint implements SpoonProgress {

    private final BooleanSupplier cancelled;

    private CancellationCheckpoint(BooleanSupplier cancelled) {
      this.cancelled = cancelled;
    }

    @Override
    public void start(Process process) {
      check();
    }

    @Override
    public void step(Process process, String task, int taskId, int nbTask) {
      check();
    }

    @Override
    public void step(Process process, String task) {
      check();
    }

    @Override
    public void end(Process process) {
      check();
    }

    private void check() {
      if (cancelled.getAsBoolean()) {
        throw new CancellationException(
            "análise de código cancelada no meio do build de um modelo");
      }
    }
  }
}
