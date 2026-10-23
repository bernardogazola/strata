package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStage;
import br.pucpr.tcc.strata.analyzer.analysis.AnalysisStatus;
import br.pucpr.tcc.strata.analyzer.analysis.HistoryScope;
import br.pucpr.tcc.strata.analyzer.analysis.StageStatus;
import br.pucpr.tcc.strata.analyzer.persistence.dao.DaoTestDatabase;
import br.pucpr.tcc.strata.analyzer.repository.BuildSystem;
import br.pucpr.tcc.strata.analyzer.repository.SourceRootKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.SourceFileStatus;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.DependencyType;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ElementKind;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.ResolutionQuality;
import br.pucpr.tcc.strata.analyzer.sourceanalysis.model.TypeKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sqlite.SQLiteErrorCode;

final class SchemaTest {

  private static final List<String> TABLES =
      List.of(
          "analysis",
          "analysis_stage",
          "archaeology_link",
          "cache_metadata",
          "change_metric",
          "cochange_exclusion",
          "cochange_pair",
          "code_element",
          "code_metric",
          "commit_parent",
          "dependency",
          "dependency_evidence",
          "file_change",
          "git_author",
          "git_commit",
          "graph_metric",
          "hotspot_evidence",
          "hotspot_metric",
          "hotspot_profile",
          "module_dependency",
          "package_edge",
          "path_lineage",
          "path_rename",
          "provider_metadata",
          "repository",
          "schema_migration",
          "source_file",
          "source_root",
          "tour_step",
          "tour_step_reason");

  private static final int FOREIGN_KEYS = 51;

  private static final String FOREIGN_KEY_CHILDREN =
      """
      SELECT t.name || '.' || f."from"
        FROM sqlite_master t, pragma_foreign_key_list(t.name) f
       WHERE t.type = 'table'""";

  private static final String UNLED_FOREIGN_KEY_CHILDREN =
      FOREIGN_KEY_CHILDREN
          + """

             AND NOT EXISTS (SELECT 1
                               FROM pragma_index_list(t.name) i, pragma_index_info(i.name) c
                              WHERE i.partial = 0 AND c.seqno = 0 AND c.name = f."from")
             AND NOT EXISTS (SELECT 1
                               FROM pragma_table_info(t.name) p
                              WHERE p.pk = 1 AND p.name = f."from")""";

  private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']*)'");

  @TempDir private Path temp;

  private record EnumColumn(String table, String column, List<String> members, String insert) {

    @Override
    public String toString() {
      return table + "." + column;
    }
  }

  private static List<String> names(Enum<?>[] members) {
    return Arrays.stream(members).map(Enum::name).toList();
  }

  static Stream<EnumColumn> colunasDeEnum() {
    final String analysis =
        "INSERT INTO analysis (analysis_id, status, started_at, analyzer_version, schema_version,"
            + " protocol_version, config_hash, config_json, include_tests, history_scope)"
            + " VALUES ('an_probe', %s, 0, '0.1.0', 1, 1, 'h', '{}', 0, %s)";
    return Stream.of(
        new EnumColumn(
            "analysis", "status", names(AnalysisStatus.values()), analysis.formatted("?", "'ALL'")),
        new EnumColumn(
            "analysis",
            "history_scope",
            names(HistoryScope.values()),
            analysis.formatted("'RUNNING'", "?")),
        new EnumColumn(
            "analysis_stage",
            "stage",
            names(AnalysisStage.values()),
            "INSERT INTO analysis_stage (analysis_pk, stage, status) VALUES (1, ?, 'PENDING')"),
        new EnumColumn(
            "analysis_stage",
            "status",
            names(StageStatus.values()),
            "INSERT INTO analysis_stage (analysis_pk, stage, status) VALUES (1, 'PERSISTENCE', ?)"),
        new EnumColumn(
            "source_file",
            "status",
            names(SourceFileStatus.values()),
            "INSERT INTO source_file (analysis_pk, relative_path, size_bytes, status)"
                + " VALUES (1, 'A.java', 0, ?)"),
        new EnumColumn(
            "source_root",
            "kind",
            names(SourceRootKind.values()),
            "INSERT INTO source_root (analysis_pk, source_root_id, relative_path, kind)"
                + " VALUES (1, 'sr_00000000', 'src', ?)"),
        new EnumColumn(
            "code_element",
            "kind",
            names(ElementKind.values()),
            "INSERT INTO code_element (analysis_pk, element_id, kind, simple_name, qualified_name,"
                + " sort_key) VALUES (1, 'probe', ?, 'p', 'p', 'p')"),
        new EnumColumn(
            "code_element",
            "type_kind",
            names(TypeKind.values()),
            "INSERT INTO code_element (analysis_pk, element_id, kind, simple_name, qualified_name,"
                + " sort_key, type_kind) VALUES (1, 'type:Probe', 'TYPE', 'Probe', 'Probe', 'Probe',"
                + " ?)"),
        new EnumColumn(
            "dependency",
            "type",
            Arrays.stream(DependencyType.values())
                .filter(DependencyType::isTypeEdge)
                .map(Enum::name)
                .toList(),
            "INSERT INTO dependency (analysis_pk, source_pk, target_pk, type, resolution)"
                + " VALUES (1, 2, 1, ?, 'RESOLVED')"),
        new EnumColumn(
            "dependency",
            "resolution",
            names(ResolutionQuality.values()),
            "INSERT INTO dependency (analysis_pk, source_pk, target_pk, type, resolution)"
                + " VALUES (1, 2, 1, 'CALLS', ?)"),
        new EnumColumn(
            "dependency_evidence",
            "resolution",
            names(ResolutionQuality.values()),
            "INSERT INTO dependency_evidence (dependency_pk, resolution) VALUES (1, ?)"),
        new EnumColumn(
            "repository",
            "build_system",
            names(BuildSystem.values()),
            "INSERT INTO repository (id, repository_key, display_name, path_fingerprint,"
                + " build_system, first_seen_at, last_seen_at)"
                + " VALUES (1, 'a6d309ef4118082d', 'r', '"
                + "0".repeat(64)
                + "', ?, 0, 0)"));
  }

  private static void pais(Connection banco) throws SQLException {
    DaoTestDatabase.execute(
        banco,
        "INSERT INTO analysis (id, analysis_id, status, started_at, analyzer_version,"
            + " schema_version, protocol_version, config_hash, config_json, include_tests,"
            + " history_scope) VALUES (1, 'an_parent', 'RUNNING', 0, '0.1.0', 1, 1, 'h', '{}',"
            + " 0, 'ALL')");
    final String tipo =
        "INSERT INTO code_element (id, analysis_pk, element_id, kind, simple_name, qualified_name,"
            + " sort_key) VALUES (?, 1, ?, 'TYPE', ?, ?, ?)";
    DaoTestDatabase.execute(banco, tipo, 1, "type:a.Source", "Source", "a.Source", "a.Source");
    DaoTestDatabase.execute(banco, tipo, 2, "type:a.Target", "Target", "a.Target", "a.Target");
    DaoTestDatabase.execute(
        banco,
        "INSERT INTO dependency (id, analysis_pk, source_pk, target_pk, type, resolution)"
            + " VALUES (1, 1, 1, 2, 'CALLS', 'RESOLVED')");
  }

  private static void inserirEDesfazer(Connection banco, String insercao, Object... valores)
      throws SQLException {
    banco.setAutoCommit(false);
    try {
      DaoTestDatabase.execute(banco, insercao, valores);
    } finally {
      banco.rollback();
      banco.setAutoCommit(true);
    }
  }

  @Test
  void temExatamenteAs30TabelasDoMvp() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(TestCaches.userTables(banco)).isEqualTo(TABLES);
    }
  }

  @Test
  void todaTabelaEhStrict() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(
              TestCaches.column(
                  banco,
                  "SELECT name FROM pragma_table_list WHERE schema = 'main' AND type = 'table'"
                      + " AND name NOT LIKE 'sqlite_%' AND strict = 0"))
          .isEmpty();
      assertThat(TestCaches.strictTables(banco)).isEqualTo(TABLES.size());
    }
  }

  @Test
  void todaColunaFilhaDeChaveEstrangeiraLideraUmIndiceOuAChavePrimaria() throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      assertThat(TestCaches.column(banco, FOREIGN_KEY_CHILDREN)).hasSize(FOREIGN_KEYS);
      assertThat(TestCaches.column(banco, UNLED_FOREIGN_KEY_CHILDREN)).isEmpty();
    }
  }

  @Test
  void oIndiceDeReusoNaoEhUnicoEAceitaDuasAnalisesCompletasIguais() throws Exception {
    final String completa =
        "INSERT INTO analysis (analysis_id, status, started_at, head_commit_id, analyzer_version,"
            + " schema_version, protocol_version, config_hash, config_json, source_fingerprint,"
            + " include_tests, history_scope) VALUES (?, 'COMPLETED', 0, ?, '0.1.0', 1, 1, ?,"
            + " '{}', ?, 0, 'ALL')";
    final String head = "fa7da60406a5f3ea0cbf4335426a66d992d3c3d1";

    try (Connection banco = DaoTestDatabase.create(temp)) {
      DaoTestDatabase.execute(banco, completa, "an_a", head, "c".repeat(64), "f".repeat(64));
      DaoTestDatabase.execute(banco, completa, "an_b", head, "c".repeat(64), "f".repeat(64));

      assertThat(
              DaoTestDatabase.scalar(
                  banco, "SELECT count(*) FROM analysis WHERE status = 'COMPLETED'"))
          .isEqualTo("2");
      assertThat(
              DaoTestDatabase.scalar(
                  banco,
                  "SELECT \"unique\" || '|' || partial FROM pragma_index_list('analysis')"
                      + " WHERE name = 'idx_analysis_reuse'"))
          .isEqualTo("0|1");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("colunasDeEnum")
  void todoMembroDoEnumPassaPeloCheck(EnumColumn coluna) throws Exception {
    try (Connection banco = DaoTestDatabase.create(temp)) {
      pais(banco);

      for (final String membro : coluna.members()) {
        assertThatCode(() -> inserirEDesfazer(banco, coluna.insert(), membro))
            .as(coluna + " = " + membro)
            .doesNotThrowAnyException();
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("colunasDeEnum")
  void umValorForaDoEnumFalhaNoCheck(EnumColumn coluna) throws Exception {
    final List<String> invalidos =
        List.of("BOGUS", "", coluna.members().getFirst().toLowerCase(Locale.ROOT));

    try (Connection banco = DaoTestDatabase.create(temp)) {
      pais(banco);

      for (final String invalido : invalidos) {
        assertThatThrownBy(() -> inserirEDesfazer(banco, coluna.insert(), invalido))
            .as(coluna + " = '" + invalido + "'")
            .satisfies(
                failure ->
                    assertThat(DaoTestDatabase.resultCode(failure))
                        .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK));
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("colunasDeEnum")
  void oCheckNaoAceitaNadaAlemDoEnum(EnumColumn coluna) throws Exception {
    final Pattern check =
        Pattern.compile(
            "CHECK\\s*\\(\\s*" + Pattern.quote(coluna.column()) + "\\s+IN\\s*\\(([^)]*)\\)");

    try (Connection banco = DaoTestDatabase.create(temp)) {
      final String ddl =
          DaoTestDatabase.scalar(
              banco,
              "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
              coluna.table());
      final Matcher lista = check.matcher(ddl);

      assertThat(lista.find()).as("o CHECK de " + coluna).isTrue();
      final List<String> literais = new ArrayList<>();
      final Matcher literal = QUOTED_LITERAL.matcher(lista.group(1));
      while (literal.find()) {
        literais.add(literal.group(1));
      }
      assertThat(literais).containsExactlyInAnyOrderElementsOf(coluna.members());
      assertThat(lista.find()).as("um CHECK só para " + coluna).isFalse();
    }
  }

  @Test
  void oCheckSoAdmiteUmaDependenciaAmbiguaUnresolvedExternal() throws Exception {
    final String dependencia =
        "INSERT INTO dependency (analysis_pk, source_pk, target_pk, type, resolution, ambiguous)"
            + " VALUES (1, 2, 1, 'CALLS', ?, ?)";

    try (Connection banco = DaoTestDatabase.create(temp)) {
      pais(banco);

      for (final String qualidade : List.of("RESOLVED", "INFERRED")) {
        assertThatThrownBy(() -> inserirEDesfazer(banco, dependencia, qualidade, 1))
            .as("ambígua e " + qualidade)
            .satisfies(
                failure ->
                    assertThat(DaoTestDatabase.resultCode(failure))
                        .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK));
      }
      assertThatThrownBy(() -> inserirEDesfazer(banco, dependencia, "UNRESOLVED_EXTERNAL", 2))
          .as("ambiguous = 2")
          .satisfies(
              failure ->
                  assertThat(DaoTestDatabase.resultCode(failure))
                      .isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK));
      assertThatCode(() -> inserirEDesfazer(banco, dependencia, "UNRESOLVED_EXTERNAL", 1))
          .doesNotThrowAnyException();
      assertThatCode(() -> inserirEDesfazer(banco, dependencia, "RESOLVED", 0))
          .doesNotThrowAnyException();
      assertThat(DaoTestDatabase.scalar(banco, "SELECT ambiguous FROM dependency WHERE id = 1"))
          .isEqualTo("0");
    }
  }
}
