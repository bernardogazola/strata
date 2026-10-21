import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import cytoscape from "cytoscape";
import { afterEach, describe, expect, test, vi } from "vitest";
import {
  assertCytoscapeColor,
  CYTOSCAPE_COLOR_NAMES,
  GRAPH_TOKENS,
  readGraphPalette,
  UnsupportedGraphColorError,
} from "../palette";

const MENTIONS_STRUCTURAL_EDGE_TOKEN = /--graph-edge-structural/;
const DROPPED_BY_CYTOSCAPE = /is invalid/;

const GLOBAL_CSS = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../global.css"
);

const readGlobalCss = (): Promise<string> => readFile(GLOBAL_CSS, "utf8");

const tokenBlock = (css: string, selector: string): string => {
  const start = css.indexOf(`\n${selector} {`);
  return css.slice(start, css.indexOf("\n}", start) + 2);
};

const ACCEPTED: ReadonlyArray<readonly [value: string, parsed: string]> = [
  ["#d4d4d4", "rgb(212,212,212)"],
  ["#fff", "rgb(255,255,255)"],
  ["#FFF", "rgb(255,255,255)"],
  ["rgb(212, 212, 212)", "rgb(212,212,212)"],
  ["rgb(212,212,212)", "rgb(212,212,212)"],
  ["rgb(83%, 83%, 83%)", "rgb(211,211,211)"],
  ["rgba(212, 212, 212, 1)", "rgb(212,212,212)"],
  ["hsl(0, 0%, 83%)", "rgb(212,212,212)"],
  ["hsla(0, 0%, 83%, 1)", "rgb(212,212,212)"],
  ["tomato", "rgb(255,99,71)"],
  ["Tomato", "rgb(255,99,71)"],
];

const DROPPED = [
  "#abcd",
  "#aabbccdd",
  "#abcde",
  "rgb(212 212 212)",
  "rgb(212 212 212 / 50%)",
  "rgba(212, 212, 212, 50%)",
  "RGB(212, 212, 212)",
  "rgb(50%, 0, 0)",
  "rgb(300, 0, 0)",
  "hsl(0 0% 83%)",
  "hsl(0, 0, 83)",
  "hsl(0deg, 0%, 83%)",
  "hsl(0, 120%, 50%)",
  "rebeccapurple",
  "currentcolor",
  "notacolor",
  "oklch(0.7 0.1 250)",
  "var(--graph-node)",
];

const PAINTED_WRONG = [
  "rgba(212, 212, 212, 0.5)",
  "hsla(0, 0%, 83%, 0.5)",
  "transparent",
];

const parseThroughCytoscape = (values: readonly string[]) => {
  const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
  const cy = cytoscape({
    elements: values.map((_, index) => ({ data: { id: `n${index}` } })),
    headless: true,
    style: values.map((value, index) => ({
      selector: `#n${index}`,
      style: { "background-color": value },
    })),
    styleEnabled: true,
  });
  const parsed = values.map((_, index) =>
    String(cy.getElementById(`n${index}`).style("background-color"))
  );
  const warnings = warn.mock.calls.map((call) => String(call[0]));
  cy.destroy();
  return { parsed, warnings };
};

afterEach(() => {
  vi.restoreAllMocks();
  for (const style of document.head.querySelectorAll("style")) {
    style.remove();
  }
});

describe("assertCytoscapeColor", () => {
  test("aceita os formatos que o parser do Cytoscape entende", () => {
    for (const [value] of ACCEPTED) {
      expect(assertCytoscapeColor("--t", value)).toBe(value);
    }
  });

  test("cada formato aceito sobrevive ao parser real, com a cor declarada", () => {
    const { parsed, warnings } = parseThroughCytoscape(
      ACCEPTED.map(([value]) => value)
    );

    expect(warnings).toEqual([]);
    expect(parsed).toEqual(ACCEPTED.map(([, color]) => color));
  });

  test("cada nome da tabela local é um nome que o Cytoscape conhece", () => {
    const names = [...CYTOSCAPE_COLOR_NAMES];

    const { warnings } = parseThroughCytoscape(names);

    expect(names).toHaveLength(147);
    expect(warnings).toEqual([]);
  });

  test("recusa tudo o que o Cytoscape descartaria, e ele de fato descarta", () => {
    for (const value of DROPPED) {
      expect(() => assertCytoscapeColor("--t", value), value).toThrow(
        UnsupportedGraphColorError
      );
    }

    const { warnings } = parseThroughCytoscape(DROPPED);

    expect(warnings.filter((w) => DROPPED_BY_CYTOSCAPE.test(w))).toHaveLength(
      DROPPED.length
    );
  });

  test("recusa alfa menor que 1 e transparent, que o Cytoscape aceita e pinta errado", () => {
    for (const value of PAINTED_WRONG) {
      expect(() => assertCytoscapeColor("--t", value), value).toThrow(
        UnsupportedGraphColorError
      );
    }
  });

  test("remove espaços de borda, que getPropertyValue costuma trazer", () => {
    expect(assertCytoscapeColor("--t", "  #d4d4d4  ")).toBe("#d4d4d4");
  });

  test("recusa oklch, que é o formato do resto do tema", () => {
    expect(() =>
      assertCytoscapeColor("--graph-node", "oklch(0.7 0.1 250)")
    ).toThrow(UnsupportedGraphColorError);
  });

  test("recusa os demais formatos de cor moderna", () => {
    for (const value of [
      "oklab(0.7 0.1 0.1)",
      "lab(70% 20 30)",
      "lch(70% 30 250)",
      "color(display-p3 0.8 0.8 0.8)",
    ]) {
      expect(() => assertCytoscapeColor("--t", value)).toThrow(
        UnsupportedGraphColorError
      );
    }
  });

  test("recusa token ausente, que chega como string vazia", () => {
    expect(() => assertCytoscapeColor("--graph-node", "")).toThrow(
      UnsupportedGraphColorError
    );
  });

  test("a mensagem diz qual token está errado", () => {
    expect(() =>
      assertCytoscapeColor("--graph-edge-structural", "oklch(0.5 0 0)")
    ).toThrow(MENTIONS_STRUCTURAL_EDGE_TOKEN);
  });
});

describe("GRAPH_TOKENS", () => {
  test("todo token declarado existe em global.css, nos dois temas", async () => {
    const css = await readGlobalCss();

    const lightBlock = css.slice(css.indexOf(":root"), css.indexOf("@theme"));
    const darkBlock = css.slice(css.indexOf(".dark {"));

    for (const token of GRAPH_TOKENS) {
      expect(lightBlock, `${token} no tema claro`).toContain(`${token}:`);
      expect(darkBlock, `${token} no tema escuro`).toContain(`${token}:`);
    }
  });

  test("nenhum token do grafo está declarado em oklch", async () => {
    const css = await readGlobalCss();

    const graphLines = css
      .split("\n")
      .filter((line: string) => line.trimStart().startsWith("--graph-"));

    expect(graphLines.length).toBe(GRAPH_TOKENS.length * 2);
    for (const line of graphLines) {
      expect(line, line.trim()).not.toContain("oklch");
    }
  });
});

describe("readGraphPalette", () => {
  test("lê os 12 tokens de cada tema de global.css, todos na gramática real", async () => {
    const css = await readGlobalCss();
    const style = document.createElement("style");
    style.textContent = `${tokenBlock(css, ":root")}\n${tokenBlock(css, ".dark")}`;
    document.head.append(style);

    const light = readGraphPalette();
    document.documentElement.classList.add("dark");
    const dark = readGraphPalette();

    expect(Object.keys(light)).toEqual([...GRAPH_TOKENS]);
    expect(Object.keys(dark)).toEqual([...GRAPH_TOKENS]);
    expect(light["--graph-node"]).toBe("#e3e3e3");
    expect(dark["--graph-node"]).toBe("#404040");
  });

  test("um token ausente no documento lança, em vez de virar cinza padrão", () => {
    expect(() => readGraphPalette()).toThrow(UnsupportedGraphColorError);
  });
});
