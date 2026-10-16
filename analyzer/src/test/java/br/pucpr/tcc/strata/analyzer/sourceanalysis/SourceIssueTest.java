package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SourceIssueTest {

  private static final String E_ACUTE_NFD = "é";

  private static final String E_ACUTE_NFC = "é";

  private static final String C_CEDILLA_NFC = "ç";

  @Test
  void umCaminhoEmNfdSaiEmNfcNasDuasFabricas() {
    final String onDisk = "src/main/java/p/Caf" + E_ACUTE_NFD + ".java";

    final SourceIssue failed = SourceIssue.parseFailed(onDisk);
    final SourceIssue tooLarge = SourceIssue.fileTooLarge(onDisk, 2_097_152);

    final String expected = "src/main/java/p/Caf" + E_ACUTE_NFC + ".java";
    assertThat(onDisk).as("pré-condição: a entrada não é NFC").isNotEqualTo(expected);
    assertThat(failed.params()).containsEntry("filePath", expected);
    assertThat(tooLarge.params()).containsEntry("filePath", expected);
  }

  @Test
  void umCaminhoAcimaDe1024BytesViraOAncestralMaisLongoQueCabe() {
    final String fits = "src/" + E_ACUTE_NFC.repeat(260);
    final String tooLong = fits + "/" + C_CEDILLA_NFC.repeat(260) + "/A.java";
    assertThat(tooLong).hasSize(532);
    assertThat(tooLong.getBytes(StandardCharsets.UTF_8)).hasSize(1_052);

    final SourceIssue failed = SourceIssue.parseFailed(tooLong);
    final SourceIssue tooLarge = SourceIssue.fileTooLarge(tooLong, 2_097_152);

    assertThat(failed.params()).containsEntry("filePath", fits);
    assertThat(tooLarge.params()).containsEntry("filePath", fits);
  }

  @Test
  void oTetoMedeOCaminhoJaEmNfc() {
    final String onDisk = "src/" + E_ACUTE_NFD.repeat(340) + "/A.java";
    assertThat(onDisk.getBytes(StandardCharsets.UTF_8)).hasSize(1_031);

    final SourceIssue failed = SourceIssue.parseFailed(onDisk);

    assertThat(failed.params())
        .containsEntry("filePath", "src/" + E_ACUTE_NFC.repeat(340) + "/A.java");
  }

  @Test
  void oAvisoDeArquivoGrandeLevaOTetoQueRecebeu() {
    final SourceIssue issue = SourceIssue.fileTooLarge("src/main/java/p/Big.java", 1_234_567);

    assertThat(issue.code()).isEqualTo(SourceIssue.Code.SOURCE_FILE_TOO_LARGE);
    assertThat(issue.params())
        .containsOnly(
            entry("filePath", "src/main/java/p/Big.java"), entry("limitBytes", 1_234_567));
  }

  @Test
  void oAvisoDeFalhaDeParseLevaSoOCaminho() {
    final SourceIssue issue = SourceIssue.parseFailed("src/main/java/p/Broken.java");

    assertThat(issue.code()).isEqualTo(SourceIssue.Code.SOURCE_PARSE_FAILED);
    assertThat(issue.params()).containsOnly(entry("filePath", "src/main/java/p/Broken.java"));
  }
}
