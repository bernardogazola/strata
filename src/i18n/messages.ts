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

  graph: {
    canvas: {
      edges: {
        one: "{count} dependência",
        other: "{count} dependências",
        zero: "nenhuma dependência",
      },
      label: "Mapa da arquitetura, {level}: {nodes} e {edges}.",
      nodes: {
        PACKAGE: {
          one: "{count} pacote",
          other: "{count} pacotes",
          zero: "nenhum pacote",
        },
        TYPE: {
          one: "{count} tipo",
          other: "{count} tipos",
          zero: "nenhum tipo",
        },
        TYPE_NEIGHBORHOOD: {
          one: "{count} tipo",
          other: "{count} tipos",
          zero: "nenhum tipo",
        },
      },
    },
    defaultPackage: "(pacote padrão)",
    empty: {
      PACKAGE: "Esta análise não encontrou nenhum pacote.",
      TYPE: "Este pacote não tem tipos para mostrar.",
      TYPE_NEIGHBORHOOD: "Este tipo não tem vizinhos para mostrar.",
    },
    legend: {
      expandHint: "Clique duas vezes em um pacote para ver os tipos dele.",
      keyboardHint:
        "Pelo teclado, use a estrutura da arquitetura: as setas percorrem, Enter seleciona e a seta para a direita abre um pacote.",
      kind: "Forma do elemento",
      kindShapes: {
        PACKAGE: "Retângulo: pacote",
        TYPE: "Círculo: tipo",
      },
      resolution: "Qualidade de resolução",
      selection: "Borda larga: elemento selecionado",
      title: "Legenda",
    },
    loading: "Carregando o mapa…",
    noAnalysis: {
      action: "Voltar ao início",
      body: "Abra um repositório e analise-o para ver o mapa da arquitetura.",
      title: "Nenhuma análise aberta",
    },
    notice: {
      aggregated:
        "Este repositório tem {total} pacotes, mais do que o primeiro nível comporta. Eles aparecem agrupados pelos prefixos de pacote de topo.",
      truncated:
        "Este nível tem mais elementos do que o mapa mostra: {nodesShown} de {nodes} e {edgesShown} de {edges}. O limite de exibição do nível deixou o restante de fora.",
    },
    retry: "Tentar de novo",
  },

  inspector: {
    empty: "Selecione um elemento no mapa para ver os detalhes.",
    expand: "Ver os tipos deste pacote",
    kind: "Elemento",
    notOnMap:
      "Este elemento não aparece no mapa: o limite de exibição do nível o deixou de fora.",
    typeCount: "tipos neste pacote",
  },

  outline: {
    failed: "Não foi possível carregar os tipos deste pacote. Tentar de novo",
    loading: "Carregando a estrutura…",
    loadingTypes: "Carregando os tipos…",
    loadMore: {
      PACKAGE: "Carregar mais pacotes",
      TYPE: "Carregar mais tipos ({shown} de {total})",
    },
    title: "Estrutura da arquitetura",
  },

  repository: {
    open: "Abrir repositório",
    restart: "Reiniciar",
  },

  search: {
    capped:
      "A busca mostra até {limit} resultados. Refine o termo para encontrar outros.",
    count: {
      one: "{count} resultado",
      other: "{count} resultados",
      zero: "Nenhum pacote ou tipo encontrado",
    },
    label: "Buscar pacotes e tipos por nome",
    placeholder: "Buscar…",
    results: "Resultados da busca",
  },

  shell: {
    breadcrumb: {
      label: "Nível do mapa",
      root: "Todos os pacotes",
      selectedPackage: "Pacote selecionado",
    },
    regions: {
      canvas: "Mapa da arquitetura",
      inspector: "Inspetor",
      sidebar: "Estrutura",
    },
    skipToOutline: "Pular para a estrutura da arquitetura",
  },

  theme: {
    label: "Alternar tema",
    options: {
      dark: "Escuro",
      light: "Claro",
      system: "Sistema",
    },
  },

  tour: {
    noEntryPoint:
      "Não encontramos um ponto de entrada claro neste repositório. O roteiro começa pelos tipos mais centrais.",
  },
} as const;

export type Messages = typeof ptBR;

export const t: Messages = ptBR;
