export const GRAPH_TOKENS = [
  "--graph-node",
  "--graph-node-border",
  "--graph-node-label",
  "--graph-node-package",
  "--graph-node-hotspot",
  "--graph-node-hotspot-strong",
  "--graph-node-selected",
  "--graph-node-dimmed",
  "--graph-edge-structural",
  "--graph-edge-evolutionary",
  "--graph-edge-highlighted",
  "--graph-edge-dimmed",
] as const;

export type GraphToken = (typeof GRAPH_TOKENS)[number];

export type GraphPalette = Readonly<Record<GraphToken, string>>;

const SRGB_COLOR = /^(#[0-9a-f]{3,8}|rgba?\(|hsla?\(|[a-z]+$)/i;

const UNSUPPORTED_COLOR = /^(oklch|oklab|lab|lch|color)\(/i;

export class UnsupportedGraphColorError extends Error {
  constructor(token: string, value: string) {
    super(
      `O token ${token} está em um formato que o Cytoscape não interpreta: "${value}". A paleta do grafo precisa ser declarada em sRGB.`
    );
    this.name = "UnsupportedGraphColorError";
  }
}

export const assertCytoscapeColor = (token: string, value: string): string => {
  const normalised = value.trim();

  if (UNSUPPORTED_COLOR.test(normalised) || !SRGB_COLOR.test(normalised)) {
    throw new UnsupportedGraphColorError(token, normalised);
  }

  return normalised;
};

export const readGraphPalette = (
  root: HTMLElement = document.documentElement
): GraphPalette => {
  const computed = getComputedStyle(root);
  const entries = GRAPH_TOKENS.map((token) => [
    token,
    assertCytoscapeColor(token, computed.getPropertyValue(token)),
  ]);

  return Object.fromEntries(entries) as GraphPalette;
};
