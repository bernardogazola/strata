import { describe, expect, test } from "vitest";
import { stageStatusLabels } from "../labels";
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

  test("nenhuma frase tem ponto médio", () => {
    const offending = BASE_COPY.filter(({ text }) => text.includes(MIDDLE_DOT));

    expect(offending).toStrictEqual([]);
  });

  test("nenhuma frase tem travessão", () => {
    const offending = BASE_COPY.filter(({ text }) => text.includes(EM_DASH));

    expect(offending).toStrictEqual([]);
  });

  test('nenhuma frase contrai "em um" ou "em uma"', () => {
    const offending = BASE_COPY.filter(
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
