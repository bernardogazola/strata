CREATE TABLE schema_migration (
  version    INTEGER PRIMARY KEY,
  name       TEXT    NOT NULL,
  checksum   TEXT    NOT NULL,
  applied_at INTEGER NOT NULL
) STRICT;

CREATE TABLE cache_metadata (
  id               INTEGER PRIMARY KEY CHECK (id = 1),
  repository_key   TEXT    NOT NULL,
  schema_version   INTEGER NOT NULL,
  analyzer_version TEXT    NOT NULL,
  protocol_version INTEGER NOT NULL,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL
) STRICT;

CREATE TABLE repository (
  id               INTEGER PRIMARY KEY CHECK (id = 1),
  repository_key   TEXT    NOT NULL UNIQUE,
  display_name     TEXT    NOT NULL,
  path_fingerprint TEXT    NOT NULL,
  root_commit_id   TEXT,
  default_branch   TEXT,
  is_shallow       INTEGER NOT NULL DEFAULT 0 CHECK (is_shallow IN (0,1)),
  build_system     TEXT    NOT NULL DEFAULT 'UNKNOWN'
                   CHECK (build_system IN ('MAVEN','GRADLE','ANT','NONE','UNKNOWN')),
  first_seen_at    INTEGER NOT NULL,
  last_seen_at     INTEGER NOT NULL
) STRICT;

CREATE TABLE analysis (
  id                    INTEGER PRIMARY KEY,
  analysis_id           TEXT    NOT NULL UNIQUE,
  status                TEXT    NOT NULL
                        CHECK (status IN ('RUNNING','COMPLETED','CANCELLED','FAILED')),
  started_at            INTEGER NOT NULL,
  finished_at           INTEGER,
  head_commit_id        TEXT,
  analyzer_version      TEXT    NOT NULL,
  schema_version        INTEGER NOT NULL,
  protocol_version      INTEGER NOT NULL,
  config_hash           TEXT    NOT NULL,
  config_json           TEXT    NOT NULL,
  source_fingerprint    TEXT,
  include_tests         INTEGER NOT NULL CHECK (include_tests IN (0,1)),
  history_scope         TEXT    NOT NULL CHECK (history_scope IN ('ALL','LAST_N_COMMITS')),
  history_scope_commits INTEGER,
  total_files           INTEGER NOT NULL DEFAULT 0,
  skipped_files         INTEGER NOT NULL DEFAULT 0,
  parse_failures        INTEGER NOT NULL DEFAULT 0,
  shallow_clone         INTEGER NOT NULL DEFAULT 0 CHECK (shallow_clone IN (0,1)),
  history_truncated_at  INTEGER,
  failure_code          TEXT,
  failure_detail        TEXT,
  CHECK (0 <= parse_failures AND parse_failures <= skipped_files
         AND skipped_files <= total_files)
) STRICT;
CREATE INDEX idx_analysis_status ON analysis(status, started_at DESC);
CREATE INDEX idx_analysis_reuse
  ON analysis(head_commit_id, source_fingerprint, config_hash,
              analyzer_version, schema_version)
  WHERE status = 'COMPLETED';

CREATE TABLE analysis_stage (
  analysis_pk   INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  stage         TEXT NOT NULL CHECK (stage IN (
                  'REPOSITORY_VALIDATION','LAYOUT_DISCOVERY','SOURCE_ANALYSIS',
                  'GIT_HISTORY_CHEAP','STRUCTURAL_GRAPH','GIT_HISTORY_DETAILED',
                  'EVOLUTIONARY_COUPLING','HOTSPOT_PROFILES','GUIDED_TOUR','PERSISTENCE')),
  status        TEXT NOT NULL CHECK (status IN
                  ('PENDING','RUNNING','COMPLETED','SKIPPED','FAILED','CANCELLED')),
  started_at    INTEGER,
  duration_ms   INTEGER,
  counters_json TEXT NOT NULL DEFAULT '{}',
  PRIMARY KEY (analysis_pk, stage)
) STRICT, WITHOUT ROWID;

CREATE TABLE source_root (
  id              INTEGER PRIMARY KEY,
  analysis_pk     INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  source_root_id  TEXT    NOT NULL,
  relative_path   TEXT    NOT NULL,
  kind            TEXT    NOT NULL CHECK (kind IN ('MAIN','TEST','GENERATED')),
  java_file_count INTEGER NOT NULL DEFAULT 0,
  UNIQUE (analysis_pk, source_root_id)
) STRICT;

CREATE TABLE source_file (
  id            INTEGER PRIMARY KEY,
  analysis_pk   INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  relative_path TEXT    NOT NULL,
  size_bytes    INTEGER NOT NULL,
  line_count    INTEGER,
  mtime_millis  INTEGER,
  content_hash  TEXT,
  status        TEXT NOT NULL CHECK (status IN
                  ('ANALYZED','SKIPPED_TOO_LARGE','PARSE_FAILED')),
  UNIQUE (analysis_pk, relative_path)
) STRICT;

CREATE TABLE code_element (
  id             INTEGER PRIMARY KEY,
  analysis_pk    INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  element_id     TEXT    NOT NULL,
  kind           TEXT    NOT NULL CHECK (kind IN ('PACKAGE','TYPE','METHOD','FILE')),
  parent_pk      INTEGER REFERENCES code_element(id) ON DELETE CASCADE,
  simple_name    TEXT    NOT NULL,
  qualified_name TEXT    NOT NULL,
  sort_key       TEXT    NOT NULL,
  type_kind      TEXT    CHECK (type_kind IN
                   ('CLASS','INTERFACE','ENUM','RECORD','ANNOTATION')),
  modifiers      TEXT    NOT NULL DEFAULT '',
  is_external    INTEGER NOT NULL DEFAULT 0 CHECK (is_external IN (0,1)),
  is_test        INTEGER NOT NULL DEFAULT 0 CHECK (is_test IN (0,1)),
  source_root_pk INTEGER REFERENCES source_root(id) ON DELETE SET NULL,
  file_pk        INTEGER REFERENCES source_file(id) ON DELETE SET NULL,
  start_line     INTEGER,
  end_line       INTEGER,
  UNIQUE (analysis_pk, element_id)
) STRICT;
CREATE INDEX idx_element_parent      ON code_element(analysis_pk, parent_pk, kind, sort_key);
CREATE INDEX idx_element_name        ON code_element(analysis_pk, simple_name);
CREATE INDEX idx_element_file        ON code_element(file_pk);
CREATE INDEX idx_fk_element_parent   ON code_element(parent_pk);
CREATE INDEX idx_fk_element_root     ON code_element(source_root_pk);

CREATE TABLE dependency (
  id          INTEGER PRIMARY KEY,
  analysis_pk INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  source_pk   INTEGER NOT NULL REFERENCES code_element(id) ON DELETE CASCADE,
  target_pk   INTEGER NOT NULL REFERENCES code_element(id) ON DELETE CASCADE,
  type        TEXT NOT NULL CHECK (type IN
                ('IMPORTS','EXTENDS','IMPLEMENTS','CALLS','FIELD_TYPE',
                 'PARAMETER_TYPE','RETURN_TYPE','ANNOTATION_REFERENCE','MODULE_DEPENDENCY')),
  resolution  TEXT NOT NULL CHECK (resolution IN
                ('RESOLVED','INFERRED','UNRESOLVED_EXTERNAL')),
  occurrences INTEGER NOT NULL DEFAULT 1,
  weight      INTEGER NOT NULL DEFAULT 1,
  UNIQUE (analysis_pk, source_pk, target_pk, type)
) STRICT;
CREATE INDEX idx_dep_out ON dependency(source_pk, type);
CREATE INDEX idx_dep_in  ON dependency(target_pk, type);

CREATE TABLE dependency_evidence (
  id             INTEGER PRIMARY KEY,
  dependency_pk  INTEGER NOT NULL REFERENCES dependency(id) ON DELETE CASCADE,
  from_method_pk INTEGER REFERENCES code_element(id) ON DELETE CASCADE,
  to_method_pk   INTEGER REFERENCES code_element(id) ON DELETE SET NULL,
  file_pk        INTEGER REFERENCES source_file(id) ON DELETE SET NULL,
  line           INTEGER,
  resolution     TEXT NOT NULL CHECK (resolution IN
                   ('RESOLVED','INFERRED','UNRESOLVED_EXTERNAL'))
) STRICT;
CREATE INDEX idx_dep_evidence           ON dependency_evidence(dependency_pk, line);
CREATE INDEX idx_fk_evidence_from       ON dependency_evidence(from_method_pk);
CREATE INDEX idx_fk_evidence_to         ON dependency_evidence(to_method_pk);
CREATE INDEX idx_fk_evidence_file       ON dependency_evidence(file_pk);

CREATE TABLE code_metric (
  element_pk    INTEGER PRIMARY KEY REFERENCES code_element(id) ON DELETE CASCADE,
  analysis_pk   INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  loc           INTEGER NOT NULL DEFAULT 0,
  source_lines  INTEGER NOT NULL DEFAULT 0,
  comment_lines INTEGER NOT NULL DEFAULT 0,
  method_count  INTEGER NOT NULL DEFAULT 0,
  field_count   INTEGER NOT NULL DEFAULT 0,
  cc_sum        INTEGER NOT NULL DEFAULT 0,
  cc_max        INTEGER NOT NULL DEFAULT 0,
  cc_mean       REAL    NOT NULL DEFAULT 0,
  cc_p90        REAL    NOT NULL DEFAULT 0,
  nesting_max   INTEGER NOT NULL DEFAULT 0
) STRICT, WITHOUT ROWID;
CREATE INDEX idx_code_metric_loc ON code_metric(analysis_pk, loc DESC);

CREATE TABLE graph_metric (
  element_pk     INTEGER PRIMARY KEY REFERENCES code_element(id) ON DELETE CASCADE,
  analysis_pk    INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  fan_in         INTEGER NOT NULL DEFAULT 0,
  fan_out        INTEGER NOT NULL DEFAULT 0,
  fan_out_external INTEGER NOT NULL DEFAULT 0,
  pagerank       REAL    NOT NULL DEFAULT 0,
  pagerank_out   REAL    NOT NULL DEFAULT 0,
  scc_id         INTEGER,
  scc_size       INTEGER,
  component_id   INTEGER,
  is_entry_point INTEGER NOT NULL DEFAULT 0 CHECK (is_entry_point IN (0,1)),
  entry_point_kind TEXT
) STRICT, WITHOUT ROWID;
CREATE INDEX idx_graph_metric_rank ON graph_metric(analysis_pk, pagerank DESC);

CREATE TABLE git_author (
  id           INTEGER PRIMARY KEY,
  analysis_pk  INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  identity_key TEXT    NOT NULL,
  display_name TEXT    NOT NULL,
  email        TEXT    NOT NULL DEFAULT '',
  UNIQUE (analysis_pk, identity_key)
) STRICT;

CREATE TABLE git_commit (
  id            INTEGER PRIMARY KEY,
  analysis_pk   INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  commit_id     TEXT    NOT NULL,
  short_id      TEXT    NOT NULL,
  authored_at   INTEGER NOT NULL,
  committed_at  INTEGER NOT NULL,
  author_pk     INTEGER NOT NULL REFERENCES git_author(id) ON DELETE CASCADE,
  committer_pk  INTEGER REFERENCES git_author(id) ON DELETE SET NULL,
  subject       TEXT    NOT NULL,
  body          TEXT,
  parent_count  INTEGER NOT NULL DEFAULT 0,
  files_changed INTEGER NOT NULL DEFAULT 0,
  additions     INTEGER,
  deletions     INTEGER,
  commit_class  TEXT    NOT NULL CHECK (commit_class IN
                  ('NORMAL','MERGE','BULK','INITIAL')),
  detailed_pass INTEGER NOT NULL DEFAULT 0 CHECK (detailed_pass IN (0,1)),
  UNIQUE (analysis_pk, commit_id)
) STRICT;
CREATE INDEX idx_commit_time      ON git_commit(analysis_pk, committed_at DESC, id DESC);
CREATE INDEX idx_commit_class     ON git_commit(analysis_pk, commit_class);
CREATE INDEX idx_fk_commit_author ON git_commit(author_pk);
CREATE INDEX idx_fk_commit_committer ON git_commit(committer_pk);

CREATE TABLE commit_parent (
  commit_pk        INTEGER NOT NULL REFERENCES git_commit(id) ON DELETE CASCADE,
  ordinal          INTEGER NOT NULL,
  parent_commit_id TEXT    NOT NULL,
  PRIMARY KEY (commit_pk, ordinal)
) STRICT, WITHOUT ROWID;

CREATE TABLE path_lineage (
  id          INTEGER PRIMARY KEY,
  analysis_pk INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  head_path   TEXT,
  change_count INTEGER NOT NULL DEFAULT 0
) STRICT;
CREATE INDEX idx_lineage_head ON path_lineage(analysis_pk, head_path);

CREATE TABLE path_rename (
  analysis_pk INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  lineage_pk  INTEGER NOT NULL REFERENCES path_lineage(id) ON DELETE CASCADE,
  ordinal     INTEGER NOT NULL,
  path        TEXT    NOT NULL,
  commit_id   TEXT,
  PRIMARY KEY (lineage_pk, ordinal)
) STRICT, WITHOUT ROWID;
CREATE INDEX idx_fk_rename_analysis ON path_rename(analysis_pk);

CREATE TABLE file_change (
  id            INTEGER PRIMARY KEY,
  analysis_pk   INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  commit_pk     INTEGER NOT NULL REFERENCES git_commit(id) ON DELETE CASCADE,
  lineage_pk    INTEGER REFERENCES path_lineage(id) ON DELETE SET NULL,
  path          TEXT    NOT NULL,
  previous_path TEXT,
  change_type   TEXT    NOT NULL CHECK (change_type IN
                  ('ADD','MODIFY','DELETE','RENAME','COPY')),
  additions     INTEGER,
  deletions     INTEGER,
  element_pk    INTEGER REFERENCES code_element(id) ON DELETE SET NULL
) STRICT;
CREATE INDEX idx_change_path      ON file_change(analysis_pk, path, commit_pk);
CREATE INDEX idx_change_commit    ON file_change(commit_pk);
CREATE INDEX idx_change_element   ON file_change(element_pk, commit_pk);
CREATE INDEX idx_fk_change_lineage ON file_change(lineage_pk);

CREATE TABLE change_metric (
  element_pk       INTEGER PRIMARY KEY REFERENCES code_element(id) ON DELETE CASCADE,
  analysis_pk      INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  commit_count     INTEGER NOT NULL DEFAULT 0,
  commit_count_excluding_bulk INTEGER NOT NULL DEFAULT 0,
  distinct_authors INTEGER NOT NULL DEFAULT 0,
  additions        INTEGER NOT NULL DEFAULT 0,
  deletions        INTEGER NOT NULL DEFAULT 0,
  churn            INTEGER NOT NULL DEFAULT 0,
  first_changed_at INTEGER,
  last_changed_at  INTEGER
) STRICT, WITHOUT ROWID;
CREATE INDEX idx_change_metric_rank ON change_metric(analysis_pk, commit_count DESC);

CREATE TABLE cochange_pair (
  analysis_pk       INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  left_pk           INTEGER NOT NULL REFERENCES code_element(id) ON DELETE CASCADE,
  right_pk          INTEGER NOT NULL REFERENCES code_element(id) ON DELETE CASCADE,
  support           INTEGER NOT NULL,
  changes_left      INTEGER NOT NULL,
  changes_right     INTEGER NOT NULL,
  jaccard           REAL    NOT NULL,
  confidence_lr     REAL    NOT NULL,
  confidence_rl     REAL    NOT NULL,
  last_cochanged_at INTEGER,
  PRIMARY KEY (analysis_pk, left_pk, right_pk),
  CHECK (left_pk < right_pk)
) STRICT, WITHOUT ROWID;
CREATE INDEX idx_cochange_l ON cochange_pair(left_pk,  support DESC);
CREATE INDEX idx_cochange_r ON cochange_pair(right_pk, support DESC);

CREATE TABLE cochange_exclusion (
  analysis_pk     INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  reason          TEXT NOT NULL CHECK (reason IN
                    ('MERGE','BULK','SIZE_CEILING','BINARY_ONLY','SINGLE_FILE')),
  commit_count    INTEGER NOT NULL,
  threshold_files INTEGER,
  PRIMARY KEY (analysis_pk, reason)
) STRICT, WITHOUT ROWID;

CREATE TABLE hotspot_profile (
  element_pk                 INTEGER PRIMARY KEY
                             REFERENCES code_element(id) ON DELETE CASCADE,
  analysis_pk                INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  granularity                TEXT NOT NULL CHECK (granularity IN ('TYPE','PACKAGE')),
  cohort_size                INTEGER NOT NULL,
  small_cohort               INTEGER NOT NULL DEFAULT 0 CHECK (small_cohort IN (0,1)),
  change_pressure_pct        REAL,
  structural_difficulty_pct  REAL,
  architectural_exposure_pct REAL,
  is_candidate_70            INTEGER NOT NULL DEFAULT 0 CHECK (is_candidate_70 IN (0,1)),
  is_candidate_75            INTEGER NOT NULL DEFAULT 0 CHECK (is_candidate_75 IN (0,1)),
  is_candidate_80            INTEGER NOT NULL DEFAULT 0 CHECK (is_candidate_80 IN (0,1)),
  is_candidate_90            INTEGER NOT NULL DEFAULT 0 CHECK (is_candidate_90 IN (0,1)),
  rank_within_candidates     INTEGER
) STRICT, WITHOUT ROWID;
CREATE INDEX idx_hotspot_75 ON hotspot_profile(analysis_pk, granularity,
                                               rank_within_candidates)
  WHERE is_candidate_75 = 1;
CREATE INDEX idx_fk_hotspot_analysis ON hotspot_profile(analysis_pk);

CREATE TABLE hotspot_metric (
  element_pk INTEGER NOT NULL REFERENCES hotspot_profile(element_pk) ON DELETE CASCADE,
  dimension  TEXT NOT NULL CHECK (dimension IN
               ('CHANGE_PRESSURE','STRUCTURAL_DIFFICULTY',
                'ARCHITECTURAL_EXPOSURE','CONTEXT')),
  metric     TEXT NOT NULL CHECK (metric IN
               ('COMMIT_COUNT','CHURN','LOC','CC_SUM','FAN_IN','FAN_OUT',
                'PAGERANK','RECENCY','DISTINCT_AUTHORS')),
  raw_value  REAL NOT NULL,
  percentile REAL,
  PRIMARY KEY (element_pk, metric)
) STRICT, WITHOUT ROWID;

CREATE TABLE hotspot_evidence (
  element_pk  INTEGER NOT NULL REFERENCES hotspot_profile(element_pk) ON DELETE CASCADE,
  ordinal     INTEGER NOT NULL,
  code        TEXT    NOT NULL,
  params_json TEXT    NOT NULL DEFAULT '{}',
  PRIMARY KEY (element_pk, ordinal)
) STRICT, WITHOUT ROWID;

CREATE TABLE tour_step (
  id          INTEGER PRIMARY KEY,
  analysis_pk INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  ordinal     INTEGER NOT NULL,
  element_pk  INTEGER NOT NULL REFERENCES code_element(id) ON DELETE CASCADE,
  tier        TEXT NOT NULL CHECK (tier IN
                ('ENTRY_POINT','ARCHITECTURAL_CORE','CONNECTOR','HOTSPOT',
                 'PERSISTENCE_BOUNDARY','EVOLUTIONARY_CLUSTER')),
  UNIQUE (analysis_pk, ordinal)
) STRICT;
CREATE INDEX idx_fk_tour_element ON tour_step(element_pk);

CREATE TABLE tour_step_reason (
  tour_step_pk INTEGER NOT NULL REFERENCES tour_step(id) ON DELETE CASCADE,
  ordinal      INTEGER NOT NULL,
  reason       TEXT    NOT NULL,
  params_json  TEXT    NOT NULL DEFAULT '{}',
  PRIMARY KEY (tour_step_pk, ordinal)
) STRICT, WITHOUT ROWID;

CREATE TABLE archaeology_link (
  id          INTEGER PRIMARY KEY,
  analysis_pk INTEGER NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
  commit_pk   INTEGER NOT NULL REFERENCES git_commit(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL CHECK (kind IN ('ISSUE','PULL_REQUEST')),
  reference   TEXT NOT NULL,
  confidence  TEXT NOT NULL CHECK (confidence IN
                ('EXPLICIT','PROVIDER_CONFIRMED','INFERRED_STRONG',
                 'INFERRED_WEAK','UNKNOWN')),
  provider    TEXT
) STRICT;
CREATE INDEX idx_arch_link_commit      ON archaeology_link(commit_pk);
CREATE INDEX idx_fk_arch_link_analysis ON archaeology_link(analysis_pk);

CREATE TABLE provider_metadata (
  id           INTEGER PRIMARY KEY,
  analysis_pk  INTEGER REFERENCES analysis(id) ON DELETE SET NULL,
  provider     TEXT    NOT NULL,
  entity_kind  TEXT    NOT NULL,
  entity_ref   TEXT    NOT NULL,
  payload_json TEXT    NOT NULL,
  fetched_at   INTEGER NOT NULL,
  UNIQUE (provider, entity_kind, entity_ref)
) STRICT;
CREATE INDEX idx_fk_provider_analysis ON provider_metadata(analysis_pk);
