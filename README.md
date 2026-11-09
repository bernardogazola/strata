<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/app-icon-dark.svg">
    <img src="brand/app-icon.svg" alt="Ícone do Strata" width="128" height="128">
  </picture>
</p>

<h1 align="center">Strata</h1>

<p align="center">Arqueologia de código para repositórios Java legados</p>

O Strata é um aplicativo desktop que analisa um repositório Git local e mostra a arquitetura do
sistema como um grafo navegável de pacotes e tipos, com métricas e as dependências que ligam cada
elemento. A ideia é ajudar quem está chegando em uma base de código desconhecida a entender por
onde começar.

Projeto desenvolvido como Trabalho de Conclusão de Curso de Engenharia de Software na PUC-PR.

## Funcionalidades

- Abre um repositório local pelo seletor de pastas do sistema e valida o conteúdo antes da
  análise.
- Encontra as raízes de código de projetos Maven e Gradle lendo os arquivos de build como texto,
  sem executá-los.
- Analisa o código Java com o Spoon e extrai pacotes, tipos e
  métodos, além das dependências entre eles (imports, herança, implementação, chamadas, tipos de
  campo, parâmetro e retorno, anotações), cada uma com a evidência no código.
- Calcula linhas de código, complexidade ciclomática, PageRank e ciclos de dependência.
- Mostra o grafo em dois níveis, pacotes e tipos, com busca, inspetor de elementos, lista de
  dependências paginada e navegação pelo teclado.
- Mostra o progresso da análise, permite cancelar e guarda o resultado em um banco SQLite local.

O histórico do Git (linha do tempo, co-mudança), os pontos críticos e o roteiro de leitura guiado
ainda estão em desenvolvimento.

## Privacidade e segurança

Toda a análise roda localmente. Em uso normal o app não faz nenhuma requisição de rede: a política
de CSP da janela bloqueia conexões externas. O repositório analisado é tratado como entrada não
confiável, e o Strata nunca executa build, testes, wrappers, scripts ou hooks dele.

A interface não tem acesso direto ao sistema de arquivos. O seletor de pastas roda no processo
Rust, e a interface recebe só um identificador opaco do repositório aberto.

## Arquitetura

O app tem três partes:

| Camada | Tecnologias | Responsabilidade |
|---|---|---|
| Interface | React 19, TypeScript, Vite, Tailwind CSS, shadcn/ui (Base UI), TanStack Router e Query, Zustand, Zod, Cytoscape.js | Telas, grafo e todo o texto em português |
| Shell desktop | Tauri 2, Rust | Janela, seletor de pastas, ciclo de vida do analisador e roteamento das mensagens |
| Analisador | Java 25, Spoon, JGit, JGraphT, Jackson, SQLite (JDBC) | Validação do repositório, análise do código, grafo, métricas e cache |

O analisador roda como processo filho e conversa com o Rust por JSON Lines na entrada e na saída
padrão. No pacote final ele é distribuído com um runtime Java próprio, gerado pelo `jlink`.

## Requisitos

- Node.js 26 e pnpm 11 (a versão do pnpm está fixada em `packageManager`, no `package.json`)
- Rust 1.99 (edição 2024)
- JDK 25
- No Linux, as dependências de sistema do Tauri:
  `libwebkit2gtk-4.1-dev libxdo-dev libssl-dev libayatana-appindicator3-dev librsvg2-dev`

## Como rodar

```bash
pnpm install

# compila o analisador; o app em modo de desenvolvimento usa o jar de analyzer/target
(cd analyzer && ./mvnw package -DskipTests)

pnpm tauri dev
```

Os testes do analisador e do frontend usam pequenos repositórios Git de exemplo, que são gerados e
não versionados. Gere uma vez depois de clonar:

```bash
bash scripts/make-fixtures.sh
```

## Testes e verificação

```bash
pnpm check                 # lint e formatação (Biome via Ultracite)
pnpm typecheck
pnpm test                  # Vitest
pnpm test:coverage         # Vitest com limite mínimo de cobertura
pnpm exec playwright test  # testes de ponta a ponta

(cd analyzer && ./mvnw verify)   # compilação, formatação, JUnit e regras de arquitetura

cargo fmt --check --manifest-path src-tauri/Cargo.toml
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml
```

Os scripts de `scripts/` cuidam do resto: geração e conferência das fixtures, auditoria das
permissões do Tauri, montagem do analisador e testes de fumaça do app empacotado. A maioria aceita
`--help` e `--self-test`.

## Empacotamento

```bash
pnpm package
```

Monta o runtime do analisador e gera o instalador da plataforma atual. O CI empacota para macOS
(arm64), Linux (x86_64, `.deb`) e Windows (x86_64, instalador NSIS).

## Licença

[MIT](LICENSE).
