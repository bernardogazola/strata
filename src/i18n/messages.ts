const ptBR = {
  analysis: {
    partial: {
      files: {
        one: "Análise parcial: {skipped} de {total} arquivos ficou de fora.",
        other:
          "Análise parcial: {skipped} de {total} arquivos ficaram de fora.",
      },
      percentiles: "Os percentis abaixo consideram apenas o que foi analisado.",
      shallowClone:
        "Clone raso (shallow): o histórico disponível está incompleto.",
      truncatedHistory: "Histórico truncado em {commits} commits.",
    },
  },

  app: {
    name: "Strata",
  },

  backend: {
    CRASHED: "Analisador parado",
    INCOMPATIBLE: "Analisador incompatível",
    READY: "Analisador pronto",
    STARTING: "Iniciando o analisador…",
  },

  evolution: {
    coChangeDisclaimer:
      "Relação evolutiva inferida do histórico de alterações. Não é uma dependência no código.",
  },

  repository: {
    open: "Abrir repositório",
    restart: "Reiniciar",
  },

  tour: {
    noEntryPoint:
      "Não encontramos um ponto de entrada claro neste repositório. O roteiro começa pelos tipos mais centrais.",
  },
} as const;

export type Messages = typeof ptBR;

export const t: Messages = ptBR;
