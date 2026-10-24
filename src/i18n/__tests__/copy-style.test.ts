import { describe, expect, test } from "vitest";
import { graphNodeKindLabels, stageStatusLabels } from "../labels";
import { t } from "../messages";

const MIDDLE_DOT = String.fromCodePoint(0xb7);
const EM_DASH = String.fromCodePoint(0x20_14);

const CONTRACTIONS = new Set(["num", "numa"]);

const NON_LETTERS = /[^\p{L}]+/u;

interface CatalogueString {
  readonly path: string;
  readonly text: string;
}

const collectStrings = (value: unknown, path: string): CatalogueString[] => {
  if (typeof value === "string") {
    return [{ path, text: value }];
  }
  if (typeof value !== "object" || value === null) {
    return [];
  }
  return Object.entries(value).flatMap(([key, child]) =>
    collectStrings(child, `${path}.${key}`)
  );
};

const BASE_COPY: readonly CatalogueString[] = [
  ...collectStrings(t.analysis, "analysis"),
  ...collectStrings(t.analyzer, "analyzer"),
  ...collectStrings(t.config, "config"),
  ...collectStrings(t.describe, "describe"),
  ...collectStrings(t.error, "error"),
  ...collectStrings(t.header, "header"),
  ...collectStrings(t.repository, "repository"),
  ...collectStrings(t.shell.regions, "shell.regions"),
  ...collectStrings(t.start, "start"),
  ...collectStrings(t.graph.canvas.label, "graph.canvas.label"),
  ...collectStrings(t.graph.legend.expandHint, "graph.legend.expandHint"),
  ...collectStrings(t.graph.legend.kindNames, "graph.legend.kindNames"),
  ...collectStrings(t.graph.legend.selectionName, "graph.legend.selectionName"),
  ...collectStrings(t.inspector.dependsOn, "inspector.dependsOn"),
  ...collectStrings(t.inspector.usedBy, "inspector.usedBy"),
  ...collectStrings(t.outline.keyboardHint, "outline.keyboardHint"),
  ...collectStrings(t.outline.loadMore.TYPE, "outline.loadMore.TYPE"),
  ...collectStrings(t.search.placeholder, "search.placeholder"),
  ...collectStrings(stageStatusLabels, "stageStatusLabels"),
];

const GROUP_COPY: readonly CatalogueString[] = [
  ...collectStrings(t.graph.canvas.groups, "graph.canvas.groups"),
  ...collectStrings(
    t.graph.canvas.labelAggregated,
    "graph.canvas.labelAggregated"
  ),
  ...collectStrings(
    t.graph.legend.kindShapes.PACKAGE_PREFIX,
    "graph.legend.kindShapes.PACKAGE_PREFIX"
  ),
  ...collectStrings(t.graph.notice.aggregated, "graph.notice.aggregated"),
  ...collectStrings(
    t.graph.notice.truncatedEdges,
    "graph.notice.truncatedEdges"
  ),
  ...collectStrings(t.inspector.group, "inspector.group"),
  ...collectStrings(
    t.inspector.notOnMap.aggregated,
    "inspector.notOnMap.aggregated"
  ),
  ...collectStrings(graphNodeKindLabels, "graphNodeKindLabels"),
];

const SCOPED_COPY: readonly CatalogueString[] = [...BASE_COPY, ...GROUP_COPY];

const contractionsIn = (text: string): string[] =>
  text
    .toLowerCase()
    .split(NON_LETTERS)
    .filter((word) => CONTRACTIONS.has(word));

describe("a redação do catálogo", () => {
  test("o escopo alcança as frases que deveria", () => {
    const paths = BASE_COPY.map(({ path }) => path);

    expect(paths).toContain("start.guarantees.0");
    expect(paths).toContain("analysis.running.title.template");
    expect(paths).toContain("graph.canvas.label");
    expect(paths).toContain("stageStatusLabels.COMPLETED");
  });

  test("o escopo alcança a copy do nível 1 agrupado", () => {
    const paths = SCOPED_COPY.map(({ path }) => path);

    expect(paths).toContain("graph.notice.aggregated");
    expect(paths).toContain("graph.notice.truncatedEdges.template");
    expect(paths).toContain("graph.legend.kindNames.PACKAGE_PREFIX");
    expect(paths).toContain("inspector.notOnMap.aggregated");
    expect(paths).toContain("graphNodeKindLabels.PACKAGE_PREFIX");
  });

  test("nenhuma frase tem ponto médio", () => {
    const offending = SCOPED_COPY.filter(({ text }) =>
      text.includes(MIDDLE_DOT)
    );

    expect(offending).toStrictEqual([]);
  });

  test("nenhuma frase tem travessão", () => {
    const offending = SCOPED_COPY.filter(({ text }) => text.includes(EM_DASH));

    expect(offending).toStrictEqual([]);
  });

  test('nenhuma frase contrai "em um" ou "em uma"', () => {
    const offending = SCOPED_COPY.filter(
      ({ text }) => contractionsIn(text).length > 0
    );

    expect(offending).toStrictEqual([]);
  });

  test("a regra das contrações pega as duas formas e poupa as palavras parecidas", () => {
    expect(contractionsIn("Clique duas vezes num pacote")).toStrictEqual([
      "num",
    ]);
    expect(contractionsIn("Numa janela do sistema")).toStrictEqual(["numa"]);
    expect(contractionsIn("Um valor numérico, em um pacote")).toStrictEqual([]);
  });
});
