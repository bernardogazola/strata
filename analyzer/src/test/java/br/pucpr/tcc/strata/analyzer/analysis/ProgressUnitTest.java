package br.pucpr.tcc.strata.analyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ProgressUnitTest {

  private static final Pattern SCHEMA =
      Pattern.compile("progressUnitSchema\\s*=\\s*z\\.enum\\(\\[(.*?)\\]\\)", Pattern.DOTALL);

  private static final Pattern MEMBER = Pattern.compile("\"([A-Z_]+)\"");

  private static Path analysisTs() {
    final Path fixtures =
        Path.of(
            Optional.ofNullable(System.getProperty("strata.protocol.fixtures"))
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "-Dstrata.protocol.fixtures não foi definido; veja o surefire no pom")));
    return fixtures.getParent().getParent().resolve("src/protocol/analysis.ts");
  }

  @Test
  void osMembrosSaoOsDoProgressUnitSchemaNaMesmaOrdem() throws IOException {
    final String fonte = Files.readString(analysisTs(), StandardCharsets.UTF_8);
    final Matcher schema = SCHEMA.matcher(fonte);
    assertThat(schema.find()).as("progressUnitSchema em analysis.ts").isTrue();

    final List<String> doTypeScript = new ArrayList<>();
    final Matcher membro = MEMBER.matcher(schema.group(1));
    while (membro.find()) {
      doTypeScript.add(membro.group(1));
    }

    assertThat(doTypeScript)
        .as("anti-vacuidade: a lista lida do TypeScript não pode sair vazia")
        .isNotEmpty();
    assertThat(Arrays.stream(ProgressUnit.values()).map(Enum::name).toList())
        .isEqualTo(doTypeScript);
  }
}
