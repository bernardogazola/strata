import { describe, expect, test } from "vitest";

const SOURCES: Record<string, string> = import.meta.glob(
  [
    "../../**/*.{ts,tsx}",
    "!../../**/__tests__/**",
    "!../../test/**",
    "!../../bridge/fake/**",
  ],
  { eager: true, import: "default", query: "?raw" }
);

const GLOB_BASE = "src/bridge/__tests__";
const SOURCE_EXTENSION = /\.tsx?$/;
const INDEX_SUFFIX = /\/index$/;

const FAKE_MODULE = "src/bridge/fake";

const STATIC_FROM =
  /^\s*(?:import|export)\s+(?!type\b)[^;]*?\bfrom\s*["']([^"']+)["']/gm;
const SIDE_EFFECT = /^\s*import\s*["']([^"']+)["']/gm;
const DYNAMIC = /(?<!typeof\s*)\bimport\(\s*["'`]([^"'`]+)["'`]\s*\)/g;

const dirnameOf = (path: string): string =>
  path.slice(0, path.lastIndexOf("/"));

const collapse = (path: string): string => {
  const segments: string[] = [];
  for (const segment of path.split("/")) {
    if (segment === "..") {
      segments.pop();
    } else if (segment !== "" && segment !== ".") {
      segments.push(segment);
    }
  }
  return segments.join("/");
};

const moduleOf = (path: string): string =>
  collapse(path).replace(SOURCE_EXTENSION, "").replace(INDEX_SUFFIX, "");

const resolveSpecifier = (
  file: string,
  specifier: string
): string | undefined => {
  if (specifier.startsWith("@/")) {
    return moduleOf(`src/${specifier.slice(2)}`);
  }
  if (specifier.startsWith(".")) {
    return moduleOf(`${dirnameOf(file)}/${specifier}`);
  }
  return undefined;
};

const specifiersOf = (source: string, pattern: RegExp): string[] =>
  [...source.matchAll(pattern)].map((match) => match[1] ?? "");

const namesTheFake = (file: string, specifier: string): boolean =>
  resolveSpecifier(file, specifier) === FAKE_MODULE;

const staticFakeImports = (
  sources: Readonly<Record<string, string>>
): string[] =>
  Object.entries(sources).flatMap(([file, source]) =>
    [...specifiersOf(source, STATIC_FROM), ...specifiersOf(source, SIDE_EFFECT)]
      .filter((specifier) => namesTheFake(file, specifier))
      .map((specifier) => `${file} -> ${specifier}`)
  );

const dynamicFakeImporters = (
  sources: Readonly<Record<string, string>>
): string[] =>
  Object.entries(sources)
    .filter(([file, source]) =>
      specifiersOf(source, DYNAMIC).some((specifier) =>
        namesTheFake(file, specifier)
      )
    )
    .map(([file]) => file);

const PRODUCTION_SOURCES: Readonly<Record<string, string>> = Object.fromEntries(
  Object.entries(SOURCES).map(([key, source]) => [
    collapse(`${GLOB_BASE}/${key}`),
    source,
  ])
);

describe("o varredor de imports", () => {
  test("lê o src/ de verdade, e não um mapa vazio", () => {
    const files = Object.keys(PRODUCTION_SOURCES);

    expect(files).toContain("src/bridge/analyzer.ts");
    expect(files).toContain("src/bridge/transport.ts");
    expect(files).toContain("src/main.tsx");
    expect(files.filter((file) => file.includes("__tests__"))).toStrictEqual(
      []
    );
    expect(
      files.filter((file) => file.startsWith("src/bridge/fake/"))
    ).toStrictEqual([]);
  });

  test("acha o import estático do bridge falso em toda grafia, e só ele", () => {
    const flagged = {
      "src/bridge/a.ts": 'import { FakeBridgeError } from "./fake";',
      "src/bridge/b.ts": 'import {\n  fakeRequest,\n} from "./fake/index";',
      "src/bridge/sub/c.ts": 'import * as fake from "../fake/index.ts";',
      "src/features/d.ts": 'export { fakeEmit } from "@/bridge/fake";',
      "src/features/e.ts": 'import "@/bridge/fake";',
    };
    const allowed = {
      "src/bridge/f.ts": 'import type { FakeEvent } from "./fake";',
      "src/bridge/g.ts": 'import { FakeBridgeError } from "./fake/error";',
      "src/bridge/h.ts": 'import { fakeOther } from "./fakery";',
      "src/features/i.ts": 'import { x } from "fake";',
    };

    expect(staticFakeImports({ ...flagged, ...allowed })).toStrictEqual([
      "src/bridge/a.ts -> ./fake",
      "src/bridge/b.ts -> ./fake/index",
      "src/bridge/sub/c.ts -> ../fake/index.ts",
      "src/features/d.ts -> @/bridge/fake",
      "src/features/e.ts -> @/bridge/fake",
    ]);
  });
});

describe("o bridge falso fora do arranque", () => {
  test("nenhum arquivo de produção fora de src/bridge/fake/ o importa estaticamente", () => {
    expect(staticFakeImports(PRODUCTION_SOURCES)).toStrictEqual([]);
  });

  test("só transport.ts chega a ele, e por import()", () => {
    expect(dynamicFakeImporters(PRODUCTION_SOURCES)).toStrictEqual([
      "src/bridge/transport.ts",
    ]);
  });
});
