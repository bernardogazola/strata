const JAVA_FILE_COUNT = {
  one: "{count} arquivo Java",
  other: "{count} arquivos Java",
} as const;

const SOURCE_ROOT_COUNT = {
  one: "{count} raiz",
  other: "{count} raízes",
} as const;

const ptBR = {
  analysis: {
    again: "Analisar de novo",
    cancel: "Cancelar análise",
    cancelled: {
      headline: "Análise cancelada",
      meta: "Cancelada depois de {duration}",
    },
    cancelling: "Cancelando…",
    completed: {
      headline: {
        one: "{total} arquivo Java analisado",
        other: "{total} arquivos Java analisados",
      },
      headlinePartial: "{analyzed} de {total} arquivos Java analisados",
      meta: "Análise concluída em {duration}",
    },
    failed: {
      headline: "A análise não terminou",
    },
    map: {
      body: {
        files: {
          one: "{count} arquivo",
          other: "{count} arquivos",
          zero: "nenhum arquivo",
        },
        template:
          "A estrutura do código já foi extraída e salva: {files} e {types}. O mapa aparece aqui quando a etapa do grafo estrutural passar a ser executada.",
        types: {
          one: "{count} tipo",
          other: "{count} tipos",
          zero: "nenhum tipo",
        },
      },
      bodyNoCounts:
        "A estrutura do código já foi extraída e salva. O mapa aparece aqui quando a etapa do grafo estrutural passar a ser executada.",
      pending:
        "O mapa aparece aqui quando a etapa do grafo estrutural terminar.",
      title: "O mapa da arquitetura ainda não é montado nesta versão",
    },
    notFound: {
      body: "O analisador foi reiniciado, e as análises feitas antes disso não podem ser reabertas nesta sessão. Analise o repositório de novo.",
      title: "Esta análise não está mais disponível",
    },
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
    running: {
      interrupted: "O analisador parou, e esta análise não vai terminar.",
      meta: "Em andamento há {elapsed}",
      title: {
        files: JAVA_FILE_COUNT,
        roots: SOURCE_ROOT_COUNT,
        template: "Analisando {files} em {roots}",
      },
    },
    stage: {
      announceEnd: "Etapa {status}: {stage}",
      announceStart: "Etapa iniciada: {stage}",
      duration: "em {duration}",
      extracted: {
        files: JAVA_FILE_COUNT,
        template: "{types} de {files}",
        types: {
          one: "{count} tipo extraído",
          other: "{count} tipos extraídos",
          zero: "nenhum tipo extraído",
        },
      },
      found: "{completed} {unit} encontrados",
      layout: {
        one: "{count} raiz escolhida",
        other: "{count} raízes escolhidas",
      },
      notRun: "não executada",
      skippedNote:
        "Etapas marcadas como puladas ainda não fazem parte desta versão do Strata.",
      units: "{completed} de {total} {unit}",
      validated: {
        one: "{count} arquivo Java encontrado",
        other: "{count} arquivos Java encontrados",
      },
    },
    stages: {
      summary: {
        completed: {
          one: "{count} concluída",
          other: "{count} concluídas",
          zero: "nenhuma concluída",
        },
        skipped: {
          one: "{count} pulada",
          other: "{count} puladas",
          zero: "nenhuma pulada",
        },
        template: "{completed}, {skipped}",
      },
      toggle: "Etapas da análise",
    },
    warnings: {
      count: {
        one: "{count} aviso",
        other: "{count} avisos",
      },
      label: "Avisos desta análise",
      sample: "Mostrando {shown} de {total} avisos.",
      unavailable:
        "Os detalhes destes avisos não estão mais disponíveis. Analise de novo para revê-los.",
    },
  },

  analyzer: {
    crashed: {
      body: "O processo de análise encerrou com o código de saída {code}. Ele não é reiniciado sozinho, porque reabrir o mesmo repositório pode repetir a falha.",
      bodyNoCode:
        "O processo de análise encerrou sem um código de saída. Ele não é reiniciado sozinho, porque reabrir o mesmo repositório pode repetir a falha.",
      title: "O analisador parou",
    },
    exitCode: "código de saída {code}",
    exportDiagnostics: "Exportar diagnóstico…",
    exportHint:
      "O diagnóstico sai sem caminhos de pastas e sem nomes de autores.",
    incompatible: {
      title: "Versões incompatíveis",
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

  config: {
    analyze: "Analisar",
    backendUnavailable: {
      CRASHED: "Reinicie o analisador para analisar.",
      INCOMPATIBLE:
        "Reinstale o Strata para alinhar a interface e o analisador.",
      STARTING: "O analisador ainda está iniciando.",
    },
    description:
      "Escolha o que entra na análise. Os limites de segurança são fixos e não aparecem aqui.",
    disabled: {
      emptySelection: "Escolha ao menos uma raiz de código para analisar.",
      runInFlight:
        "Já existe uma análise em andamento. Aguarde o fim dela para começar outra.",
    },
    history: {
      commits: "commits",
      description:
        "Quanto do histórico Git entra nas visões de evolução. Esta versão analisa só a estrutura do código.",
      invalid: "Informe quantos commits, a partir de 1.",
      label: "Histórico",
      unavailable: "ainda não lido nesta versão",
    },
    includeTests: {
      description:
        "Marca as raízes de teste. Os testes são lidos como código, nunca executados.",
      label: "Incluir o código de teste",
    },
    rebuild: {
      description:
        "Nesta versão toda análise já começa do zero, então não há resultado guardado para descartar.",
      label: "Recriar o cache antes de analisar",
      unavailable: "ainda não usado nesta versão",
    },
    roots: {
      clearAll: "Desmarcar todas",
      count: JAVA_FILE_COUNT,
      description:
        "As pastas que o Strata encontrou no projeto. Nenhum caminho é digitado.",
      followsTests: "(segue a opção acima)",
      label: "Raízes de código",
      repositoryRoot: "(raiz do repositório)",
      selectAll: "Marcar todas",
    },
    summary: {
      files: JAVA_FILE_COUNT,
      roots: SOURCE_ROOT_COUNT,
      template: "{files} em {roots}",
    },
    summaryHint:
      "A análise roda localmente e pode ser cancelada a qualquer momento.",
    summaryNone: "Nenhuma raiz marcada",
    title: "Preparar a análise",
  },

  describe: {
    facts: {
      branch: "Branch atual",
      buildSystem: "Sistema de build",
      commit: "Commit atual",
      detached: "HEAD destacado",
      files: "Arquivos",
      filesValue: {
        one: "{count} arquivo no total",
        other: "{count} arquivos no total",
        zero: "Nenhum arquivo",
      },
      java: "Código Java",
      javaValue: {
        files: {
          one: "{count} arquivo",
          other: "{count} arquivos",
        },
        roots: SOURCE_ROOT_COUNT,
        template: "{files} em {roots}",
      },
      modules: "Módulos declarados",
      noModules: "Nenhum módulo declarado",
      unborn: "Sem commits ainda",
    },
    loading: "Lendo o repositório…",
  },

  error: {
    code: "Código: {code}",
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
      kind: "Forma do elemento",
      kindNames: {
        PACKAGE: "Pacote",
        TYPE: "Tipo",
      },
      kindShapes: {
        PACKAGE: "Retângulo: pacote",
        TYPE: "Círculo: tipo",
      },
      resolution: "Qualidade de resolução",
      selection: "Borda larga: elemento selecionado",
      selectionName: "Selecionado",
      title: "Legenda",
    },
    loading: "Carregando o mapa…",
    notice: {
      aggregated:
        "Este repositório tem {total} pacotes, mais do que o primeiro nível comporta. Eles aparecem agrupados pelos prefixos de pacote de topo.",
      truncated:
        "Este nível tem mais elementos do que o mapa mostra: {nodesShown} de {nodes} e {edgesShown} de {edges}. O limite de exibição do nível deixou o restante de fora.",
    },
    retry: "Tentar de novo",
  },

  header: {
    analysis: {
      CANCELLED: "Análise cancelada",
      FAILED: "Análise falhou",
      PARTIAL: "Análise parcial",
      RUNNING: "Analisando",
    },
    close: "Fechar repositório",
    window: {
      close: "Fechar janela",
      maximize: "Maximizar",
      minimize: "Minimizar",
      restore: "Restaurar",
    },
  },

  inspector: {
    dependsOn: "Depende de",
    empty: "Selecione um elemento no mapa para ver os detalhes.",
    expand: "Ver os tipos deste pacote",
    kind: "Elemento",
    notOnMap: {
      aggregated:
        "Este pacote não aparece sozinho no mapa: o primeiro nível agrupa os pacotes pelos prefixos de topo.",
      truncated:
        "Este elemento não aparece no mapa: o limite de exibição do nível o deixou de fora.",
    },
    typeCount: "tipos neste pacote",
    usedBy: "Usado por",
  },

  outline: {
    failed: "Não foi possível carregar os tipos deste pacote. Tentar de novo",
    failedRefresh: "Não foi possível atualizar os pacotes. Tentar de novo",
    failedRoot: "Não foi possível carregar mais pacotes. Tentar de novo",
    keyboardHint:
      "As setas percorrem, Enter seleciona e a seta para a direita abre um pacote.",
    loading: "Carregando a estrutura…",
    loadingTypes: "Carregando os tipos…",
    loadMore: {
      PACKAGE: "Carregar mais pacotes",
      TYPE: "Carregar mais tipos ({shown} de {total})",
    },
    title: "Estrutura da arquitetura",
  },

  repository: {
    backToStart: "Voltar ao início",
    chooseAnother: "Escolher outra pasta…",
    open: "Abrir repositório…",
    restart: "Reiniciar",
  },

  search: {
    capped:
      "{count}. A busca mostra até {limit} resultados. Refine o termo para encontrar outros.",
    count: {
      one: "{count} resultado",
      other: "{count} resultados",
      zero: "Nenhum pacote ou tipo encontrado",
    },
    label: "Buscar pacotes e tipos por nome",
    placeholder: "Buscar pacotes e tipos",
    results: "Resultados da busca",
  },

  shell: {
    breadcrumb: {
      label: "Nível do mapa",
      root: "Todos os pacotes",
      selectedPackage: "Pacote selecionado",
    },
    regions: {
      analysis: "Análise",
      canvas: "Mapa da arquitetura",
      inspector: "Inspetor",
      prepare: "Preparação da análise",
      repository: "Repositório",
      sidebar: "Estrutura",
      start: "Início",
    },
    skipToOutline: "Pular para a estrutura da arquitetura",
  },

  start: {
    body: "O Strata lê o código Java de uma pasta desta máquina e mostra como o sistema está organizado, sem compilar nem executar nada do repositório.",
    guarantees: [
      "Tudo roda nesta máquina. Nada é enviado pela rede.",
      "Arquivos de build são lidos como texto. Nenhum build ou teste é executado.",
      "O repositório não é alterado. Os resultados ficam em um cache local do Strata.",
    ],
    pickerHint: "A pasta é escolhida em uma janela do sistema.",
    title: "Abra um repositório Java para começar",
    unavailable: {
      CRASHED: "Reinicie o analisador para abrir um repositório.",
      INCOMPATIBLE:
        "Reinstale o Strata para alinhar a interface e o analisador.",
      STARTING: "O analisador ainda está iniciando.",
    },
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
