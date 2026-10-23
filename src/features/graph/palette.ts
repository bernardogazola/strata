export const GRAPH_TOKENS = [
  "--graph-canvas",
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

const NUMBER = String.raw`[-+]?(?:\d+|\d*\.\d+)(?:[eE][-+]?\d+)?`;
const HEX_COLOR = /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i;
const RGB_COLOR = new RegExp(
  String.raw`^rgba?\(${NUMBER}%?\s*,\s*${NUMBER}%?\s*,\s*${NUMBER}%?(?:\s*,\s*${NUMBER})?\)$`
);
const HSL_COLOR = new RegExp(
  String.raw`^hsla?\(${NUMBER}\s*,\s*${NUMBER}%\s*,\s*${NUMBER}%(?:\s*,\s*${NUMBER})?\)$`
);
const WHITESPACE = /\s+/;

const MAX_RGB_CHANNEL = 255;
const MAX_PERCENT = 100;

export const CYTOSCAPE_COLOR_NAMES: ReadonlySet<string> = new Set(
  `aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue
  blueviolet brown burlywood cadetblue chartreuse chocolate coral cornflowerblue cornsilk
  crimson cyan darkblue darkcyan darkgoldenrod darkgray darkgreen darkgrey darkkhaki
  darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen
  darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue
  dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite
  gold goldenrod gray grey green greenyellow honeydew hotpink indianred indigo ivory khaki
  lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan
  lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen
  lightskyblue lightslategray lightslategrey lightsteelblue lightyellow lime limegreen linen
  magenta maroon mediumaquamarine mediumblue mediumorchid mediumpurple mediumseagreen
  mediumslateblue mediumspringgreen mediumturquoise mediumvioletred midnightblue mintcream
  mistyrose moccasin navajowhite navy oldlace olive olivedrab orange orangered orchid
  palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru pink plum
  powderblue purple red rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell
  sienna silver skyblue slateblue slategray slategrey snow springgreen steelblue tan teal
  thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen`
    .trim()
    .split(WHITESPACE)
);

const isOpaque = (alpha: string | undefined): boolean =>
  alpha === undefined || Number.parseFloat(alpha) === 1;

const isWithin = (value: number, max: number): boolean =>
  value >= 0 && value <= max;

const argumentsOf = (value: string): string[] =>
  value
    .slice(value.indexOf("(") + 1, -1)
    .split(",")
    .map((argument) => argument.trim());

const isRgbColor = (value: string): boolean => {
  if (!RGB_COLOR.test(value)) {
    return false;
  }

  const [red, green, blue, alpha] = argumentsOf(value);
  const channels = [red, green, blue];
  const percentCount = channels.filter((channel) =>
    channel.endsWith("%")
  ).length;
  if (percentCount !== 0 && percentCount !== channels.length) {
    return false;
  }

  const scale = percentCount === 0 ? 1 : MAX_RGB_CHANNEL / MAX_PERCENT;
  return (
    channels.every((channel) =>
      isWithin(Number.parseFloat(channel) * scale, MAX_RGB_CHANNEL)
    ) && isOpaque(alpha)
  );
};

const isHslColor = (value: string): boolean => {
  if (!HSL_COLOR.test(value)) {
    return false;
  }

  const [, saturation, lightness, alpha] = argumentsOf(value);
  return (
    isWithin(Number.parseFloat(saturation), MAX_PERCENT) &&
    isWithin(Number.parseFloat(lightness), MAX_PERCENT) &&
    isOpaque(alpha)
  );
};

const isCytoscapeColor = (value: string): boolean =>
  HEX_COLOR.test(value) ||
  CYTOSCAPE_COLOR_NAMES.has(value.toLowerCase()) ||
  isRgbColor(value) ||
  isHslColor(value);

export class UnsupportedGraphColorError extends Error {
  constructor(token: string, value: string) {
    super(
      `O token ${token} do grafo tem "${value}", que o parser de cor do Cytoscape recusa ou desenha diferente. Declare como #rgb, #rrggbb, rgb()/hsl() separado por vírgulas e sem alfa, ou um nome de cor do CSS3.`
    );
    this.name = "UnsupportedGraphColorError";
  }
}

export const assertCytoscapeColor = (token: string, value: string): string => {
  const normalised = value.trim();

  if (!isCytoscapeColor(normalised)) {
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
