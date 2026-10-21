import type { CacheUnavailableCause } from "@/protocol/enums";
import type {
  ErrorCode,
  ErrorParamsByCode,
  ProtocolError,
} from "@/protocol/errors";
import { errorParamsSchemaByCode, isKnownErrorCode } from "@/protocol/errors";
import { formatDuration, formatInteger } from "./format";
import { cacheUnavailableCauseLabels } from "./labels";

export type MessageSegment =
  | { kind: "chrome"; text: string }
  | { kind: "untrusted"; value: string };

export type ErrorSeverity = "error" | "warning";

export interface ErrorMessage {
  readonly body: readonly MessageSegment[];
  readonly hint?: string;
  readonly retryable: boolean;
  readonly severity: ErrorSeverity;
  readonly title: string;
}

const t = (text: string): MessageSegment => ({ kind: "chrome", text });
const u = (value: string): MessageSegment => ({ kind: "untrusted", value });

interface ErrorEntry<K extends ErrorCode> {
  readonly format: (
    params: ErrorParamsByCode[K]
  ) => Pick<ErrorMessage, "title" | "body" | "hint">;
  readonly retryable: boolean | ((params: ErrorParamsByCode[K]) => boolean);
  readonly severity: ErrorSeverity;
}

type ErrorRegistry = { [K in ErrorCode]: ErrorEntry<K> };

const BYTES_PER_MIB = 1024 * 1024;

const formatMib = (bytes: number): string =>
  `${formatInteger(Math.round(bytes / BYTES_PER_MIB))} MiB`;

const CACHE_UNAVAILABLE_RETRYABLE = {
  LOCATION_INSIDE_REPOSITORY: false,
  LOCKED: true,
  PRAGMA_REFUSED: false,
  STORAGE: true,
} as const satisfies Record<CacheUnavailableCause, boolean>;

const CACHE_UNAVAILABLE_HINT = {
  LOCATION_INSIDE_REPOSITORY:
    "Abra um repositório que não contenha a pasta de dados do Strata.",
  LOCKED:
    "Feche outras janelas do Strata com este repositório aberto e tente de novo em instantes.",
  PRAGMA_REFUSED: "Exporte um pacote de diagnóstico se o problema se repetir.",
  STORAGE: "Libere espaço em disco ou confira as permissões e tente de novo.",
} as const satisfies Record<CacheUnavailableCause, string>;

const errorRegistry: ErrorRegistry = {
  ANALYSIS_ALREADY_RUNNING: {
    format: () => ({
      body: [
        t(
          "Já existe uma análise em andamento, e o Strata faz uma de cada vez."
        ),
      ],
      hint: "Aguarde a conclusão ou cancele a análise atual.",
      title: "Análise em andamento",
    }),
    retryable: false,
    severity: "warning",
  },
  ANALYSIS_CACHE_CORRUPTED: {
    format: () => ({
      body: [
        t(
          "O cache de análise deste repositório se danificou durante a análise. O Strata o recriou, e o que esta análise já tinha gravado se perdeu com ele."
        ),
      ],
      hint: "Analise de novo.",
      title: "Cache danificado durante a análise",
    }),
    retryable: true,
    severity: "error",
  },
  ANALYSIS_NOT_FOUND: {
    format: () => ({
      body: [t("A análise solicitada não existe mais.")],
      hint: "Analise o repositório novamente.",
      title: "Análise não encontrada",
    }),
    retryable: false,
    severity: "error",
  },
  ANALYSIS_TOO_MANY_JAVA_SOURCES: {
    format: (p) => ({
      body: [
        t(
          `A seleção tem ${formatInteger(p.found)} arquivos Java, acima do limite de ${formatInteger(p.limit)} que o Strata analisa com segurança.`
        ),
      ],
      hint: "Deixe os testes de fora ou escolha menos raízes de código.",
      title: "Código demais para uma análise",
    }),
    retryable: false,
    severity: "error",
  },
  CACHE_CORRUPT: {
    format: () => ({
      body: [
        t(
          "O cache de análise estava corrompido e foi recriado. Nenhum resultado foi perdido, mas a próxima análise deste repositório será mais lenta."
        ),
      ],
      title: "Cache recriado",
    }),
    retryable: true,
    severity: "warning",
  },
  CACHE_MIGRATION_FAILED: {
    format: () => ({
      body: [
        t(
          "O cache de análise não pôde ser atualizado para o formato desta versão e foi recriado. Nenhum resultado foi perdido, mas a próxima análise deste repositório será mais lenta."
        ),
      ],
      title: "Cache recriado",
    }),
    retryable: true,
    severity: "warning",
  },
  CACHE_UNAVAILABLE: {
    format: (p) => ({
      body: [
        t("O Strata não conseguiu usar o cache de análise deste repositório. "),
        t(`${cacheUnavailableCauseLabels[p.cause]}.`),
      ],
      hint: CACHE_UNAVAILABLE_HINT[p.cause],
      title: "Cache de análise indisponível",
    }),
    retryable: (p) => CACHE_UNAVAILABLE_RETRYABLE[p.cause],
    severity: "error",
  },
  INTERNAL_UNEXPECTED: {
    format: () => ({
      body: [t("O Strata encontrou um erro inesperado.")],
      hint: "Exporte um pacote de diagnóstico se o problema se repetir.",
      title: "Erro inesperado",
    }),
    retryable: true,
    severity: "error",
  },
  PROC_ANALYZER_CRASHED: {
    format: () => ({
      body: [t("O processo de análise encerrou de forma inesperada.")],
      hint: "Reinicie o analisador para continuar.",
      title: "O analisador parou",
    }),
    retryable: true,
    severity: "error",
  },
  PROC_ANALYZER_NOT_FOUND: {
    format: () => ({
      body: [
        t("Os arquivos do analisador não foram encontrados nesta instalação."),
      ],
      hint: "Reinstale o Strata.",
      title: "Instalação incompleta",
    }),
    retryable: false,
    severity: "error",
  },
  PROC_ANALYZER_NOT_READY: {
    format: () => ({
      body: [
        t(
          "O analisador ainda está iniciando e não pode atender esta operação."
        ),
      ],
      hint: "Tente de novo em instantes.",
      title: "Analisador ainda iniciando",
    }),
    retryable: true,
    severity: "error",
  },
  PROC_ANALYZER_START_FAILED: {
    format: () => ({
      body: [t("Não foi possível iniciar o processo de análise.")],
      hint: "Reinicie o Strata; se persistir, exporte um pacote de diagnóstico.",
      title: "Falha ao iniciar o analisador",
    }),
    retryable: true,
    severity: "error",
  },
  PROC_REQUEST_TIMEOUT: {
    format: (p) => ({
      body: [
        t(
          `O analisador não respondeu em ${formatDuration(p.timeoutMs)} e a operação foi interrompida.`
        ),
      ],
      hint: "Tente de novo; se persistir, reinicie o Strata.",
      title: "O analisador demorou demais",
    }),
    retryable: true,
    severity: "error",
  },
  PROC_SHUTTING_DOWN: {
    format: () => ({
      body: [
        t("A aplicação está sendo encerrada e não inicia novas operações."),
      ],
      title: "Encerrando o Strata",
    }),
    retryable: false,
    severity: "warning",
  },
  PROTO_INVALID_PAYLOAD: {
    format: () => ({
      body: [
        t(
          "Uma requisição foi recusada porque o formato dos dados não confere."
        ),
      ],
      title: "Requisição inválida",
    }),
    retryable: false,
    severity: "error",
  },
  PROTO_MALFORMED_MESSAGE: {
    format: () => ({
      body: [t("O analisador enviou uma mensagem que não pôde ser lida.")],
      title: "Mensagem malformada",
    }),
    retryable: true,
    severity: "error",
  },
  PROTO_MESSAGE_TOO_LARGE: {
    format: (p) => ({
      body: [
        t(
          `O analisador tentou enviar uma resposta de ${formatMib(p.receivedBytes)}, acima do limite de ${formatMib(p.limitBytes)}.`
        ),
      ],
      title: "Resposta grande demais",
    }),
    retryable: false,
    severity: "error",
  },
  PROTO_UNKNOWN_METHOD: {
    format: () => ({
      body: [t("Esta operação não é reconhecida.")],
      hint: "Provavelmente o analisador e a interface estão em versões diferentes.",
      title: "Operação desconhecida",
    }),
    retryable: false,
    severity: "error",
  },
  PROTO_VERSION_MISMATCH: {
    format: (p) => ({
      body: [
        t(
          `A interface fala a versão ${formatInteger(p.expected)} do protocolo e o analisador fala a ${formatInteger(p.received)}.`
        ),
      ],
      hint: "Reinstale o Strata para alinhar os dois componentes.",
      title: "Versões incompatíveis",
    }),
    retryable: false,
    severity: "error",
  },
  REPO_HANDLE_UNKNOWN: {
    format: () => ({
      body: [t("Este repositório não está mais aberto nesta sessão.")],
      hint: "Abra o repositório novamente.",
      title: "Repositório não autorizado",
    }),
    retryable: false,
    severity: "error",
  },
  REPO_IS_BARE: {
    format: () => ({
      body: [
        t(
          "A pasta selecionada é um repositório bare, sem árvore de trabalho para analisar."
        ),
      ],
      hint: "Selecione um clone com os arquivos de trabalho.",
      title: "Repositório sem árvore de trabalho",
    }),
    retryable: false,
    severity: "error",
  },
  REPO_NO_HISTORY: {
    format: () => ({
      body: [
        t(
          "Este repositório ainda não tem commits, então a análise estrutural roda sem as visões de histórico e de evolução."
        ),
      ],
      title: "Repositório sem histórico",
    }),
    retryable: false,
    severity: "warning",
  },
  REPO_NO_JAVA_SOURCES: {
    format: () => ({
      body: [t("Nenhum arquivo .java foi encontrado no repositório.")],
      hint: "Esta versão do Strata analisa apenas repositórios Java.",
      title: "Sem código Java",
    }),
    retryable: false,
    severity: "error",
  },
  REPO_NOT_A_GIT_REPOSITORY: {
    format: () => ({
      body: [
        t(
          "A pasta selecionada não é um repositório Git, ou guarda os objetos do Git de um jeito que o Strata não lê com segurança."
        ),
      ],
      hint: "Selecione a raiz de um repositório clonado. Se ele já é um, compacte os objetos com git gc e tente de novo.",
      title: "Repositório Git não suportado",
    }),
    retryable: false,
    severity: "error",
  },
  REPO_PATH_CHANGED: {
    format: () => ({
      body: [
        t(
          "A pasta aberta deixou de estar no mesmo lugar: ela foi movida, renomeada ou trocada depois de escolhida."
        ),
      ],
      hint: "Abra o repositório novamente.",
      title: "A pasta do repositório mudou",
    }),
    retryable: false,
    severity: "error",
  },
  REPO_PATH_ESCAPES_ROOT: {
    format: (p) => ({
      body: [
        t("O caminho "),
        u(p.filePath),
        t(" aponta para fora do repositório e foi ignorado."),
      ],
      title: "Caminho fora do repositório",
    }),
    retryable: false,
    severity: "warning",
  },
  REPO_ROOT_UNREADABLE: {
    format: () => ({
      body: [
        t(
          "O sistema operacional não deixou o Strata listar a pasta escolhida."
        ),
      ],
      hint: "Confira as permissões da pasta. No macOS, veja Ajustes do Sistema › Privacidade e Segurança › Arquivos e Pastas.",
      title: "Sem permissão para ler a pasta",
    }),
    retryable: true,
    severity: "error",
  },
  REPO_TOO_MANY_FILES: {
    format: (p) => ({
      body: [
        t(
          `O repositório tem mais de ${formatInteger(p.limit)} arquivos e pastas, o limite que o Strata percorre com segurança.`
        ),
      ],
      title: "Repositório grande demais",
    }),
    retryable: false,
    severity: "error",
  },
  REPO_UNREADABLE: {
    format: (p) => ({
      body: [
        t("Não foi possível ler "),
        u(p.filePath),
        t(", que ficou de fora da análise."),
      ],
      title: "Caminho inacessível",
    }),
    retryable: false,
    severity: "warning",
  },
  SOURCE_DUPLICATE_ELEMENT: {
    format: (p) => ({
      body: [
        t("O arquivo "),
        u(p.filePath),
        t(" declara um elemento que "),
        u(p.firstFilePath),
        t(
          " já declara, e só a primeira declaração entrou na análise estrutural."
        ),
      ],
      title: "Declaração repetida",
    }),
    retryable: false,
    severity: "warning",
  },
  SOURCE_ELEMENT_ID_TOO_LONG: {
    format: (p) => ({
      body: [
        t("Um elemento declarado em "),
        u(p.filePath),
        t(
          ` tem um identificador acima de ${formatInteger(p.limitBytes)} bytes e ficou de fora da análise estrutural.`
        ),
      ],
      title: "Elemento ignorado",
    }),
    retryable: false,
    severity: "warning",
  },
  SOURCE_FILE_TOO_LARGE: {
    format: (p) => ({
      body: [
        t("O arquivo "),
        u(p.filePath),
        t(
          ` passa do limite de ${formatMib(p.limitBytes)} e ficou de fora da análise.`
        ),
      ],
      title: "Arquivo ignorado",
    }),
    retryable: false,
    severity: "warning",
  },
  SOURCE_PARSE_FAILED: {
    format: (p) => ({
      body: [
        t("Não foi possível interpretar o arquivo "),
        u(p.filePath),
        t(", que ficou de fora da análise estrutural."),
      ],
      title: "Falha ao ler um arquivo",
    }),
    retryable: false,
    severity: "warning",
  },
};

const unknownCodeMessage = (code: string): ErrorMessage => ({
  body: [
    t("O analisador relatou uma condição que esta versão não reconhece ("),
    u(code),
    t(")."),
  ],
  hint: "Provavelmente o analisador e a interface estão em versões diferentes.",
  retryable: false,
  severity: "error",
  title: "Erro não reconhecido",
});

export const formatProtocolError = (error: ProtocolError): ErrorMessage => {
  if (!isKnownErrorCode(error.code)) {
    return unknownCodeMessage(error.code);
  }

  const parsed = errorParamsSchemaByCode[error.code].safeParse(
    error.params ?? {}
  );

  if (!parsed.success) {
    return unknownCodeMessage(error.code);
  }

  const entry = errorRegistry[error.code] as ErrorEntry<ErrorCode>;
  const params = parsed.data as ErrorParamsByCode[ErrorCode];
  const { title, body, hint } = entry.format(params);

  return {
    body,
    hint,
    retryable:
      typeof entry.retryable === "function"
        ? entry.retryable(params)
        : entry.retryable,
    severity: entry.severity,
    title,
  };
};
