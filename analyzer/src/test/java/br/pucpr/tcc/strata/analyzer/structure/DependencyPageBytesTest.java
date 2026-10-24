package br.pucpr.tcc.strata.analyzer.structure;

import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType.IMPORTS;
import static br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.sourceanalysis.CodeElementId;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DependencyPageBytesTest {

  private static final String PACOTE = "pkg:app";

  private static DependencyEntry linha(String nome, String rotulo) {
    return new DependencyEntry(
        EdgeIds.of("type:app.Alvo", "type:app." + nome),
        new CodeElementId("type:app." + nome),
        ElementKind.TYPE,
        rotulo,
        "app." + nome,
        new CodeElementId(PACOTE),
        false,
        false,
        RESOLVED,
        Map.of(IMPORTS, 1L),
        1);
  }

  private static List<DependencyEntry> cinco() {
    final List<DependencyEntry> linhas = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      linhas.add(linha("T" + "x".repeat(i * 37), "T" + i));
    }
    return List.copyOf(linhas);
  }

  private static long soma(List<DependencyEntry> linhas, int quantas) {
    long total = DependencyPageBytes.PAGE_OVERHEAD_BYTES;
    for (int i = 0; i < quantas; i++) {
      total += DependencyPageBytes.estimate(linhas.get(i));
    }
    return total;
  }

  @Test
  void oTetoEhInclusivoEExato() {
    final List<DependencyEntry> linhas = cinco();
    final long tres = soma(linhas, 3);

    assertThat(DependencyPageBytes.fit(linhas, 10, tres)).isEqualTo(3);
    assertThat(DependencyPageBytes.fit(linhas, 10, tres - 1)).isEqualTo(2);
    assertThat(DependencyPageBytes.fit(linhas, 10, tres + 1)).isEqualTo(3);
    assertThat(DependencyPageBytes.fit(linhas, 10, soma(linhas, 5))).isEqualTo(5);
  }

  @Test
  void aPrimeiraLinhaEntraSempre() {
    final List<DependencyEntry> linhas = cinco();

    assertThat(DependencyPageBytes.fit(linhas, 10, 1)).isEqualTo(1);
    assertThat(DependencyPageBytes.fit(linhas, 10, soma(linhas, 1) - 1)).isEqualTo(1);
  }

  @Test
  void oLimiteCortaAntesDoOrcamento() {
    final List<DependencyEntry> linhas = cinco();

    assertThat(DependencyPageBytes.fit(linhas, 2, Long.MAX_VALUE)).isEqualTo(2);
    assertThat(DependencyPageBytes.fit(linhas, 1, Long.MAX_VALUE)).isEqualTo(1);
    assertThat(DependencyPageBytes.fit(List.of(), 10, Long.MAX_VALUE)).isZero();
  }

  @Test
  void umLimiteAbaixoDeUmERecusado() {
    assertThatThrownBy(() -> DependencyPageBytes.fit(cinco(), 0, Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cadaCaractereEscapadoValeSeisEOsOutrosASuaLarguraUtf8() {
    final long base = DependencyPageBytes.estimate(linha("Base", "a".repeat(10)));

    assertThat(DependencyPageBytes.estimate(linha("Base", "\"".repeat(10)))).isEqualTo(base + 50);
    assertThat(DependencyPageBytes.estimate(linha("Base", "\\".repeat(10)))).isEqualTo(base + 50);
    assertThat(DependencyPageBytes.estimate(linha("Base", Character.toString(0x01).repeat(10))))
        .isEqualTo(base + 50);
    assertThat(DependencyPageBytes.estimate(linha("Base", Character.toString(0x1F).repeat(10))))
        .isEqualTo(base + 50);
    assertThat(DependencyPageBytes.estimate(linha("Base", Character.toString(0x7F).repeat(10))))
        .isEqualTo(base);
    assertThat(DependencyPageBytes.estimate(linha("Base", Character.toString(0xE9).repeat(10))))
        .isEqualTo(base + 10);
    assertThat(DependencyPageBytes.estimate(linha("Base", Character.toString(0xFF3A).repeat(10))))
        .isEqualTo(base + 20);
    assertThat(DependencyPageBytes.estimate(linha("Base", Character.toString(0x1D419).repeat(10))))
        .isEqualTo(base + 30);
    assertThat(DependencyPageBytes.estimate(linha("Base", String.valueOf((char) 0xD835) + "a")))
        .isEqualTo(base - 10 + 6 + 1);
  }

  @Test
  void asCincoCadeiasDaLinhaEntramNaConta() {
    final DependencyEntry curta = linha("Ab", "Ab");
    final DependencyEntry nomeMaior = linha("Abc", "Ab");
    final DependencyEntry rotuloMaior = linha("Ab", "Abc");

    assertThat(DependencyPageBytes.estimate(nomeMaior))
        .isEqualTo(DependencyPageBytes.estimate(curta) + 2);
    assertThat(DependencyPageBytes.estimate(rotuloMaior))
        .isEqualTo(DependencyPageBytes.estimate(curta) + 1);
    assertThat(DependencyPageBytes.estimate(curta))
        .isEqualTo(
            DependencyPageBytes.ROW_OVERHEAD_BYTES
                + curta.edgeId().length()
                + curta.elementId().value().length()
                + curta.label().length()
                + curta.qualifiedName().length()
                + PACOTE.length());
  }
}
