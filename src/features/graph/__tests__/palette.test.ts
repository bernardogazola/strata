import { describe, expect, test } from "vitest";
import {
  assertCytoscapeColor,
  GRAPH_TOKENS,
  UnsupportedGraphColorError,
} from "../palette";

const MENTIONS_STRUCTURAL_EDGE_TOKEN = /--graph-edge-structural/;

describe("assertCytoscapeColor", () => {
  test("aceita os formatos que o parser do Cytoscape entende", () => {
    expect(assertCytoscapeColor("--t", "#d4d4d4")).toBe("#d4d4d4");
    expect(assertCytoscapeColor("--t", "#fff")).toBe("#fff");
    expect(assertCytoscapeColor("--t", "rgb(212, 212, 212)")).toBe(
      "rgb(212, 212, 212)"
    );
    expect(assertCytoscapeColor("--t", "hsl(0 0% 83%)")).toBe("hsl(0 0% 83%)");
    expect(assertCytoscapeColor("--t", "tomato")).toBe("tomato");
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
    const css = await import("node:fs/promises").then((fs) =>
      fs.readFile(new URL("../../../global.css", import.meta.url), "utf8")
    );

    const lightBlock = css.slice(css.indexOf(":root"), css.indexOf("@theme"));
    const darkBlock = css.slice(css.indexOf(".dark {"));

    for (const token of GRAPH_TOKENS) {
      expect(lightBlock, `${token} no tema claro`).toContain(`${token}:`);
      expect(darkBlock, `${token} no tema escuro`).toContain(`${token}:`);
    }
  });

  test("nenhum token do grafo está declarado em oklch", async () => {
    const css = await import("node:fs/promises").then((fs) =>
      fs.readFile(new URL("../../../global.css", import.meta.url), "utf8")
    );

    const graphLines = css
      .split("\n")
      .filter((line) => line.trimStart().startsWith("--graph-"));

    expect(graphLines.length).toBe(GRAPH_TOKENS.length * 2);
    for (const line of graphLines) {
      expect(line, line.trim()).not.toContain("oklch");
    }
  });
});
