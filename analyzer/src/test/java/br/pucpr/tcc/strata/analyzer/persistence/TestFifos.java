package br.pucpr.tcc.strata.analyzer.persistence;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class TestFifos {

  private static final int OWNER_READ_WRITE = 0x180;

  private TestFifos() {}

  @SuppressWarnings("restricted")
  static Path create(Path path) throws IOException {
    final Linker linker = Linker.nativeLinker();
    final MethodHandle mkfifo =
        linker.downcallHandle(
            linker
                .defaultLookup()
                .find("mkfifo")
                .orElseThrow(() -> new IOException("esta libc não tem mkfifo")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    final int result;
    try (Arena arena = Arena.ofConfined()) {
      result = (int) mkfifo.invokeExact(arena.allocateFrom(path.toString()), OWNER_READ_WRITE);
    } catch (Throwable failure) {
      throw new IOException("não foi possível chamar mkfifo para " + path, failure);
    }
    if (result != 0) {
      throw new IOException("mkfifo falhou para " + path);
    }
    if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .isOther()) {
      throw new IOException(path + " não é um FIFO depois do mkfifo");
    }
    return path;
  }
}
