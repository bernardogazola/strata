import type {
  AnalysisStage,
  AnalysisStatus,
  ProgressUnit,
} from "@/protocol/analysis";
import type {
  BuildSystem,
  CacheInvalidationReason,
  CacheUnavailableCause,
  ChangeType,
  CommitClass,
  DependencyDirection,
  DependencyType,
  ElementKind,
  EntryPointKind,
  GraphLevel,
  HistoryScope,
  HotspotDimension,
  HotspotEvidenceCode,
  HotspotMetric,
  RelationshipConfidence,
  ResolutionQuality,
  SourceRootKind,
  StageStatus,
  TourReasonTag,
  TourTier,
  TypeKind,
} from "@/protocol/enums";

export interface ExplainedLabel {
  readonly explanation: string;
  readonly label: string;
}

export interface EvidenceLabel extends ExplainedLabel {
  readonly template: string;
}

export const analysisStageLabels = {
  EVOLUTIONARY_COUPLING: "Calculando o acoplamento evolutivo",
  GIT_HISTORY_CHEAP: "Lendo o histórico",
  GIT_HISTORY_DETAILED: "Detalhando o histórico",
  GUIDED_TOUR: "Montando o roteiro guiado",
  HOTSPOT_PROFILES: "Identificando os pontos críticos",
  LAYOUT_DISCOVERY: "Descobrindo a estrutura do projeto",
  PERSISTENCE: "Salvando os resultados",
  REPOSITORY_VALIDATION: "Validando o repositório",
  SOURCE_ANALYSIS: "Analisando o código-fonte",
  STRUCTURAL_GRAPH: "Montando o grafo estrutural",
} as const satisfies Record<AnalysisStage, string>;

export const progressUnitLabels = {
  COMMIT_PAIRS: "pares de commits",
  COMMITS: "commits",
  JAVA_FILES: "arquivos Java",
  PACKAGES: "pacotes",
  TYPES: "tipos",
} as const satisfies Record<ProgressUnit, string>;

export const analysisStatusLabels = {
  CANCELLED: "Cancelada",
  COMPLETED: "Concluída",
  FAILED: "Falhou",
  RUNNING: "Em andamento",
} as const satisfies Record<AnalysisStatus, string>;

export const graphLevelLabels = {
  PACKAGE: "pacotes",
  TYPE: "tipos",
  TYPE_NEIGHBORHOOD: "vizinhança do tipo",
} as const satisfies Record<GraphLevel, string>;

export const dependencyTypeLabels = {
  ANNOTATION_REFERENCE: "referencia como anotação",
  CALLS: "chama",
  EXTENDS: "estende",
  FIELD_TYPE: "usa como tipo de campo",
  IMPLEMENTS: "implementa",
  IMPORTS: "importa",
  MODULE_DEPENDENCY: "depende do módulo",
  PARAMETER_TYPE: "recebe como parâmetro",
  RETURN_TYPE: "devolve",
} as const satisfies Record<DependencyType, string>;

export const resolutionQualityLabels = {
  INFERRED: "deduzida do código",
  RESOLVED: "resolvida",
  UNRESOLVED_EXTERNAL: "externa, não resolvida",
} as const satisfies Record<ResolutionQuality, string>;

export const relationshipConfidenceLabels = {
  EXPLICIT: "explícita",
  INFERRED_STRONG: "inferência forte",
  INFERRED_WEAK: "inferência fraca",
  PROVIDER_CONFIRMED: "confirmada pelo provedor",
  UNKNOWN: "sem evidência",
} as const satisfies Record<RelationshipConfidence, string>;

export const typeKindLabels = {
  ANNOTATION: "anotação",
  CLASS: "classe",
  ENUM: "enum",
  INTERFACE: "interface",
  RECORD: "record",
} as const satisfies Record<TypeKind, string>;

export const elementKindLabels = {
  FILE: "arquivo",
  METHOD: "método",
  PACKAGE: "pacote",
  TYPE: "tipo",
} as const satisfies Record<ElementKind, string>;

export const stageStatusLabels = {
  CANCELLED: "cancelado",
  COMPLETED: "concluído",
  FAILED: "falhou",
  PENDING: "pendente",
  RUNNING: "em andamento",
  SKIPPED: "pulado",
} as const satisfies Record<StageStatus, string>;

export const changeTypeLabels = {
  ADD: "adicionado",
  COPY: "copiado",
  DELETE: "removido",
  MODIFY: "modificado",
  RENAME: "renomeado",
} as const satisfies Record<ChangeType, string>;

export const commitClassLabels = {
  BULK: "commit em massa",
  INITIAL: "inicial",
  MERGE: "merge",
  NORMAL: "normal",
} as const satisfies Record<CommitClass, string>;

export const dependencyDirectionLabels = {
  IN: "quem depende deste",
  OUT: "de quem este depende",
} as const satisfies Record<DependencyDirection, string>;

export const hotspotDimensionLabels = {
  ARCHITECTURAL_EXPOSURE: "Exposição arquitetural",
  CHANGE_PRESSURE: "Pressão de mudança",
  CONTEXT: "Contexto",
  STRUCTURAL_DIFFICULTY: "Dificuldade estrutural",
} as const satisfies Record<HotspotDimension, string>;

export const hotspotMetricLabels = {
  CC_SUM: "complexidade ciclomática total",
  CHURN: "churn (linhas alteradas)",
  COMMIT_COUNT: "commits que alteraram",
  DISTINCT_AUTHORS: "autores distintos",
  FAN_IN: "fan-in (quem depende deste)",
  FAN_OUT: "fan-out (de quem este depende)",
  LOC: "LOC (linhas de código)",
  PAGERANK: "centralidade (PageRank)",
  RECENCY: "última alteração",
} as const satisfies Record<HotspotMetric, string>;

export const tourTierLabels = {
  ARCHITECTURAL_CORE: "Núcleo da arquitetura",
  CONNECTOR: "Conexões entre áreas",
  ENTRY_POINT: "Por onde o sistema começa",
  EVOLUTIONARY_CLUSTER: "O que está mudando agora",
  HOTSPOT: "Pontos críticos",
  PERSISTENCE_BOUNDARY: "Fronteiras de dados e integração",
} as const satisfies Record<TourTier, string>;

export const entryPointKindLabels = {
  CLI: "comando de terminal",
  FRAMEWORK_MAIN: "inicialização do framework",
  HTTP_ENDPOINT: "endpoint HTTP",
  MAIN: "método main",
  MESSAGING_ENDPOINT: "consumidor de mensagens",
  SCHEDULED: "tarefa agendada",
  TEST_MAIN: "método main de teste",
} as const satisfies Record<EntryPointKind, string>;

export const tourReasonTagLabels = {
  API_BOUNDARY: {
    explanation: "Recebe chamadas de fora do sistema.",
    label: "fronteira de API",
  },
  CENTRAL: {
    explanation: "Muitas partes do sistema apontam para este ponto.",
    label: "central",
  },
  COCHANGE_CLUSTER: {
    explanation: "Costuma mudar junto com um grupo de outros arquivos.",
    label: "agrupamento evolutivo",
  },
  CONFIGURATION: {
    explanation: "Define como o sistema é montado.",
    label: "configuração",
  },
  CONNECTOR: {
    explanation: "Liga áreas do sistema que, sem ele, ficariam separadas.",
    label: "conector",
  },
  CROSSES_PACKAGES: {
    explanation: "Suas dependências atravessam vários pacotes.",
    label: "cruza pacotes",
  },
  ENTRY_POINT: {
    explanation: "Por aqui a execução do sistema começa.",
    label: "ponto de entrada",
  },
  FREQUENTLY_CHANGED: {
    explanation: "Está entre os arquivos mais alterados do histórico.",
    label: "muda com frequência",
  },
  HIGH_COMPLEXITY: {
    explanation: "Concentra muitos caminhos de decisão.",
    label: "complexidade alta",
  },
  HIGH_FAN_IN: {
    explanation: "Muitos tipos dependem deste.",
    label: "fan-in alto",
  },
  HIGH_FAN_OUT: {
    explanation: "Este depende de muitos outros tipos.",
    label: "fan-out alto",
  },
  HOTSPOT: {
    explanation: "Muda com frequência e é estruturalmente difícil.",
    label: "ponto crítico",
  },
  IN_DEPENDENCY_CYCLE: {
    explanation: "Faz parte de um ciclo entre tipos.",
    label: "em ciclo de dependência",
  },
  INTEGRATION_BOUNDARY: {
    explanation: "Fala com serviços externos.",
    label: "fronteira de integração",
  },
  LARGE_TYPE: {
    explanation: "É um dos maiores arquivos do repositório.",
    label: "tipo extenso",
  },
  MANY_AUTHORS: {
    explanation: "Passou pelas mãos de muitas pessoas diferentes.",
    label: "muitos autores",
  },
  PERSISTENCE_BOUNDARY: {
    explanation: "Fala diretamente com o banco de dados.",
    label: "fronteira de persistência",
  },
  RECENTLY_CHANGED: {
    explanation: "Teve mudanças nos últimos meses.",
    label: "alterado recentemente",
  },
} as const satisfies Record<TourReasonTag, ExplainedLabel>;

export const hotspotEvidenceCodeLabels = {
  COCHANGES_ACROSS_PACKAGES: {
    explanation:
      "Em quantos pacotes diferentes estão os arquivos que mudam junto com este.",
    label: "co-mudança entre pacotes",
    template: "muda junto com arquivos de {packages} pacotes diferentes",
  },
  COMPLEX_METHOD: {
    explanation: "Complexidade ciclomática do método mais complexo deste tipo.",
    label: "método complexo",
    template: "método mais complexo com complexidade {cc}",
  },
  HIGH_CENTRALITY: {
    explanation:
      "Posição no PageRank do grafo estrutural, dentro da coorte comparada.",
    label: "centralidade alta",
    template: "centralidade no percentil {percentile}º",
  },
  HIGH_CHURN: {
    explanation: "Linhas adicionadas e removidas no período analisado.",
    label: "churn alto",
    template: "{churn} linhas alteradas no período",
  },
  HIGH_COMPLEXITY: {
    explanation: "Soma da complexidade ciclomática dos métodos deste tipo.",
    label: "complexidade alta",
    template: "complexidade ciclomática total de {ccSum}",
  },
  HIGH_FAN_IN: {
    explanation: "Quantos tipos internos dependem deste.",
    label: "fan-in alto",
    template: "fan-in alto: {fanIn} tipos dependem deste",
  },
  HIGH_FAN_OUT: {
    explanation: "De quantos tipos internos este depende.",
    label: "fan-out alto",
    template: "fan-out alto: depende de {fanOut} tipos",
  },
  IN_COCHANGE_CLUSTER: {
    explanation: "Quantos arquivos costumam mudar junto com este.",
    label: "agrupamento evolutivo",
    template: "muda junto com {neighbours} outros arquivos",
  },
  IN_DEPENDENCY_CYCLE: {
    explanation: "Tamanho do ciclo de dependências de que este tipo participa.",
    label: "em ciclo de dependência",
    template: "faz parte de um ciclo com {sccSize} tipos",
  },
  LARGE_TYPE: {
    explanation: "Tamanho em linhas de código.",
    label: "tipo extenso",
    template: "{loc} linhas de código",
  },
  MANY_AUTHORS: {
    explanation: "Quantas pessoas diferentes alteraram este elemento.",
    label: "muitos autores",
    template: "{authors} autores distintos",
  },
  MODIFIED_IN_N_COMMITS: {
    explanation: "Quantos commits do período tocaram este elemento.",
    label: "commits que alteraram",
    template: "alterado em {commits} commits",
  },
  RECENTLY_CHANGED: {
    explanation: "Há quanto tempo foi a última alteração.",
    label: "alterado recentemente",
    template: "última alteração há {days} dias",
  },
} as const satisfies Record<HotspotEvidenceCode, EvidenceLabel>;

export const ENGLISH_TERMS = [
  "fan-in",
  "fan-out",
  "hotspot",
  "churn",
  "PageRank",
  "LOC",
  "commit",
  "endpoint",
] as const;

export type EnglishTerm = (typeof ENGLISH_TERMS)[number];

export const englishTermAccessibleNames = {
  churn: "volume de alterações",
  commit: "commit",
  endpoint: "ponto de acesso HTTP",
  "fan-in": "dependências que chegam",
  "fan-out": "dependências que saem",
  hotspot: "ponto crítico",
  LOC: "linhas de código",
  PageRank: "centralidade PageRank",
} as const satisfies Record<EnglishTerm, string>;

export const buildSystemLabels = {
  ANT: "Ant",
  GRADLE: "Gradle",
  MAVEN: "Maven",
  NONE: "Nenhum sistema de build",
  UNKNOWN: "Sistema de build não identificado",
} as const satisfies Record<BuildSystem, string>;

export const sourceRootKindLabels = {
  GENERATED: "Código gerado",
  MAIN: "Código principal",
  TEST: "Código de teste",
} as const satisfies Record<SourceRootKind, string>;

export const historyScopeLabels = {
  ALL: "Todo o histórico",
  LAST_N_COMMITS: "Apenas os commits mais recentes",
} as const satisfies Record<HistoryScope, string>;

export const cacheInvalidationReasonLabels = {
  CONFIG_CHANGED: "A configuração da análise mudou",
  CORRUPT: "O cache estava corrompido",
  HEAD_CHANGED: "O HEAD do repositório mudou",
  SCHEMA_VERSION: "O formato do cache mudou nesta versão",
  WORKTREE_DIRTY: "Há alterações não registradas na árvore de trabalho",
} as const satisfies Record<CacheInvalidationReason, string>;

export const cacheUnavailableCauseLabels = {
  LOCATION_INSIDE_REPOSITORY:
    "A pasta onde o Strata guarda os caches fica dentro do repositório escolhido, e gravar nela seria escrever no próprio repositório",
  LOCKED:
    "Outro processo do Strata ainda está usando o cache deste repositório",
  PRAGMA_REFUSED:
    "O banco de dados do cache recusou a configuração de que o Strata precisa",
  STORAGE:
    "O sistema não deixou o Strata gravar o cache: falta espaço em disco, permissão ou o disco falhou",
} as const satisfies Record<CacheUnavailableCause, string>;
