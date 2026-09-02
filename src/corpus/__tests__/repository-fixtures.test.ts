import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";
import {
  CANONICAL_KEY_ORDER,
  CLAIMABLE_SECTIONS,
  DEPENDENCY_TYPE_ORDER,
  DEPENDENCY_TYPE_WEIGHT,
  type Expected,
  expectedSchema,
  RESOLUTION_STRENGTH,
  renderExpected,
} from "../expected";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "../../..");
const CORPUS_DIR = path.resolve(REPO_ROOT, "fixtures/repositories");

const EXPECTED_FIXTURES = [
  "broken-source",
  "cyclic",
  "inheritance",
  "method-calls",
  "missing-classpath",
  "multi-module",
  "simple-packages",
] as const;

const LINE_BUDGET: Record<string, number> = {
  inheritance: 45,
  "method-calls": 45,
  "missing-classpath": 50,
  "simple-packages": 60,
};

const SCORE = /score/i;
const UNICODE_ESCAPE = /\\u[0-9a-fA-F]{4}/;
const ABSOLUTE_POSIX = /^\//;
const ABSOLUTE_WINDOWS = /^[A-Za-z]:[\\/]/;
const ABSOLUTE_UNC = /^\\\\/;
const HOME_DIRECTORY = /^\/(Users|home)\//;
const METHOD_ID = /^method:(.+)#[^#]*\([^()]*\)$/;
const JAVA_FILE = /\.java$/;

interface Fixture {
  readonly dir: string;
  readonly name: string;
  readonly text: string;
  readonly value: Expected;
}

const readCorpus = (): Fixture[] => {
  if (!existsSync(CORPUS_DIR)) {
    return [];
  }
  const found: Fixture[] = [];
  for (const name of readdirSync(CORPUS_DIR).sort()) {
    const dir = path.join(CORPUS_DIR, name);
    if (name.startsWith(".") || !statSync(dir).isDirectory()) {
      continue;
    }
    const golden = path.join(dir, "expected.json");
    const text = existsSync(golden) ? readFileSync(golden, "utf8") : "";
    found.push({ dir, name, text, value: JSON.parse(text || "null") });
  }
  return found;
};

const corpus = readCorpus();

const javaFilesUnder = (root: string, base: string): string[] => {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === ".git") {
        continue;
      }
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (JAVA_FILE.test(entry.name)) {
        out.push(path.relative(base, full).split(path.sep).join("/"));
      }
    }
  };
  if (existsSync(root)) {
    walk(root);
  }
  return out.sort();
};

const collectStrings = (value: unknown, into: string[]): string[] => {
  if (typeof value === "string") {
    into.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) {
      collectStrings(item, into);
    }
  } else if (value !== null && typeof value === "object") {
    for (const [key, nested] of Object.entries(value)) {
      into.push(key);
      collectStrings(nested, into);
    }
  }
  return into;
};

const collectKeys = (value: unknown, into: string[]): string[] => {
  if (Array.isArray(value)) {
    for (const item of value) {
      collectKeys(item, into);
    }
  } else if (value !== null && typeof value === "object") {
    for (const [key, nested] of Object.entries(value)) {
      into.push(key);
      collectKeys(nested, into);
    }
  }
  return into;
};

const objectSites = (
  value: unknown,
  prefix: readonly (number | string)[],
  into: (number | string)[][]
): (number | string)[][] => {
  if (Array.isArray(value)) {
    value.forEach((item, index) => {
      objectSites(item, [...prefix, index], into);
    });
  } else if (value !== null && typeof value === "object") {
    into.push([...prefix]);
    for (const [key, nested] of Object.entries(value)) {
      objectSites(nested, [...prefix, key], into);
    }
  }
  return into;
};

const injectAt = (
  value: unknown,
  at: readonly (number | string)[]
): unknown => {
  const clone = structuredClone(value) as Record<string, unknown>;
  let cursor: unknown = clone;
  for (const step of at) {
    cursor = (cursor as Record<number | string, unknown>)[step];
  }
  (cursor as Record<string, unknown>).__unknown__ = 1;
  return clone;
};

const isAbsolutePathLike = (value: string): boolean =>
  ABSOLUTE_POSIX.test(value) ||
  ABSOLUTE_WINDOWS.test(value) ||
  ABSOLUTE_UNC.test(value) ||
  HOME_DIRECTORY.test(value);

const declaringTypeOf = (methodId: string): null | string => {
  const match = METHOD_ID.exec(methodId);
  return match ? `type:${match[1]}` : null;
};

const packageOf = (typeId: string): string => {
  const qualified = typeId.slice("type:".length).split("$")[0] ?? "";
  const dot = qualified.lastIndexOf(".");
  return dot === -1 ? "pkg:" : `pkg:${qualified.slice(0, dot)}`;
};

describe("o corpus existe", () => {
  test("as sete fixtures estruturais estão presentes, exatamente", () => {
    expect(corpus.map((fixture) => fixture.name)).toStrictEqual([
      ...EXPECTED_FIXTURES,
    ]);
  });

  test("cada fixture traz expected.json e README.md não vazios", () => {
    expect(corpus).toHaveLength(EXPECTED_FIXTURES.length);
    for (const fixture of corpus) {
      expect(fixture.text.length).toBeGreaterThan(0);
      const readme = path.join(fixture.dir, "README.md");
      expect(existsSync(readme)).toBe(true);
      expect(readFileSync(readme, "utf8").trim().length).toBeGreaterThan(0);
    }
  });

  test("cada fixture é um repositório git gerado, não clonado", () => {
    expect(corpus).toHaveLength(EXPECTED_FIXTURES.length);
    for (const fixture of corpus) {
      expect(existsSync(path.join(fixture.dir, ".git"))).toBe(true);
    }
  });
});

describe("forma do golden", () => {
  test("todo expected.json satisfaz o schema", () => {
    expect(corpus.length).toBeGreaterThan(0);
    for (const fixture of corpus) {
      const parsed = expectedSchema.safeParse(fixture.value);
      expect(
        parsed.success ? null : `${fixture.name}: ${parsed.error.message}`
      ).toBeNull();
    }
  });

  test("a ordem canônica de chaves cobre o schema, sem sobra e sem falta", () => {
    expect([...CANONICAL_KEY_ORDER].sort()).toStrictEqual(
      Object.keys(expectedSchema.def.shape).sort()
    );
  });

  test("o nome da fixture é o nome do diretório", () => {
    expect(corpus.length).toBeGreaterThan(0);
    for (const fixture of corpus) {
      expect(fixture.value.fixture).toBe(fixture.name);
    }
  });

  test("o texto é idêntico à forma canônica", () => {
    expect(corpus.length).toBeGreaterThan(0);
    for (const fixture of corpus) {
      expect(renderExpected(JSON.parse(fixture.text))).toBe(fixture.text);
    }
  });

  test("uma chave desconhecida em qualquer objeto reprova", () => {
    let mutated = 0;
    for (const fixture of corpus) {
      for (const site of objectSites(fixture.value, [], [])) {
        if (site.at(-1) === "types" && site.length > 1) {
          continue;
        }
        if (site.at(-1) === "internalCohesion") {
          continue;
        }
        mutated += 1;
        expect(
          expectedSchema.safeParse(injectAt(fixture.value, site)).success
        ).toBe(false);
      }
    }
    expect(mutated).toBeGreaterThan(0);
  });

  test("nenhuma chave casa /score/i", () => {
    let checked = 0;
    for (const fixture of corpus) {
      for (const key of collectKeys(fixture.value, [])) {
        checked += 1;
        expect(SCORE.test(key)).toBe(false);
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("nenhuma string é caminho absoluto; o texto é NFC, sem CR e sem escape", () => {
    let checked = 0;
    for (const fixture of corpus) {
      for (const value of collectStrings(fixture.value, [])) {
        checked += 1;
        expect(isAbsolutePathLike(value)).toBe(false);
      }
      expect(fixture.text.normalize("NFC")).toBe(fixture.text);
      expect(UNICODE_ESCAPE.test(fixture.text)).toBe(false);
      expect(fixture.text.endsWith("\n")).toBe(true);
      expect(fixture.text).not.toContain("\r");
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("todo id cabe em 1024 bytes, não em 1024 unidades UTF-16", () => {
    let checked = 0;
    for (const fixture of corpus) {
      for (const value of collectStrings(fixture.value, [])) {
        if (!(value.startsWith("type:") || value.startsWith("method:"))) {
          continue;
        }
        checked += 1;
        expect(Buffer.byteLength(value, "utf8")).toBeLessThanOrEqual(1024);
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("o golden cabe no orçamento de leitura do seu consumidor", () => {
    let checked = 0;
    for (const fixture of corpus) {
      const budget = LINE_BUDGET[fixture.name];
      if (budget === undefined) {
        continue;
      }
      checked += 1;
      const lines = fixture.text.trimEnd().split("\n").length;
      expect([fixture.name, lines <= budget, lines]).toStrictEqual([
        fixture.name,
        true,
        lines,
      ]);
    }
    expect(checked).toBe(Object.keys(LINE_BUDGET).length);
  });
});

describe("claims", () => {
  test("uma seção reivindicada está presente e não é vazia", () => {
    let checked = 0;
    for (const fixture of corpus) {
      for (const claim of fixture.value.claims) {
        checked += 1;
        const section = (fixture.value as Record<string, unknown>)[claim];
        expect([fixture.name, claim, section === undefined]).toStrictEqual([
          fixture.name,
          claim,
          false,
        ]);
        if (Array.isArray(section)) {
          expect(section.length).toBeGreaterThan(0);
        }
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("uma seção presente é reivindicada, e nada afirma por acidente", () => {
    expect(corpus.length).toBeGreaterThan(0);
    for (const fixture of corpus) {
      const present = CLAIMABLE_SECTIONS.filter(
        (section) =>
          (fixture.value as Record<string, unknown>)[section] !== undefined
      );
      expect([fixture.name, present]).toStrictEqual([
        fixture.name,
        [...fixture.value.claims].sort(),
      ]);
    }
  });

  test("claims está em ordem e sem repetição", () => {
    expect(corpus.length).toBeGreaterThan(0);
    for (const fixture of corpus) {
      const claims = [...fixture.value.claims];
      expect(claims).toStrictEqual([...new Set(claims)].sort());
    }
  });
});

describe("os ids", () => {
  test("cada lista está em ordem de code point e sem repetição", () => {
    let checked = 0;
    for (const fixture of corpus) {
      const lists: [string, string[]][] = [
        ["packages", fixture.value.packages ?? []],
        ["methods", fixture.value.methods ?? []],
        ["files", fixture.value.files ?? []],
        ["types", (fixture.value.types ?? []).map((row) => row.id)],
        ["parseFailures", fixture.value.parseFailures ?? []],
      ];
      for (const [label, ids] of lists) {
        if (ids.length === 0) {
          continue;
        }
        checked += 1;
        expect([fixture.name, label, ids]).toStrictEqual([
          fixture.name,
          label,
          [...new Set(ids)].sort(),
        ]);
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("todo type: tem o seu pkg:, e todo method: o seu type:", () => {
    let checked = 0;
    for (const fixture of corpus) {
      const packages = new Set(fixture.value.packages ?? []);
      const types = new Set((fixture.value.types ?? []).map((row) => row.id));
      if (fixture.value.packages) {
        for (const row of fixture.value.types ?? []) {
          checked += 1;
          expect([
            fixture.name,
            row.id,
            packages.has(packageOf(row.id)),
          ]).toStrictEqual([fixture.name, row.id, true]);
        }
      }
      if (fixture.value.methods && fixture.value.types) {
        for (const method of fixture.value.methods) {
          checked += 1;
          const owner = declaringTypeOf(method);
          expect([
            fixture.name,
            method,
            owner !== null && types.has(owner),
          ]).toStrictEqual([fixture.name, method, true]);
        }
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("um alvo de dependência é externo sse não aparece em types", () => {
    let checked = 0;
    for (const fixture of corpus) {
      if (!(fixture.value.types && fixture.value.dependencies)) {
        continue;
      }
      const types = new Set(fixture.value.types.map((row) => row.id));
      for (const row of fixture.value.dependencies) {
        checked += 1;
        expect([fixture.name, row.from, types.has(row.from)]).toStrictEqual([
          fixture.name,
          row.from,
          true,
        ]);
        expect([fixture.name, row.to, row.external === true]).toStrictEqual([
          fixture.name,
          row.to,
          !types.has(row.to),
        ]);
      }
    }
    expect(checked).toBeGreaterThan(0);
  });
});

describe("as dependências", () => {
  test("as linhas estão em ordem (from, to, ordem de declaração do enum)", () => {
    let checked = 0;
    for (const fixture of corpus) {
      const rows = fixture.value.dependencies;
      if (!rows) {
        continue;
      }
      checked += 1;
      const key = (row: (typeof rows)[number]) =>
        [
          row.from,
          row.to,
          String(DEPENDENCY_TYPE_ORDER.indexOf(row.type)).padStart(2, "0"),
        ].join(" ");
      expect([fixture.name, rows.map(key)]).toStrictEqual([
        fixture.name,
        [...rows.map(key)].sort(),
      ]);
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("uma linha canônica é única por (from, to, type)", () => {
    expect(corpus.length).toBeGreaterThan(0);
    for (const fixture of corpus) {
      const rows = fixture.value.dependencies ?? [];
      const keys = rows.map((row) => `${row.from}|${row.to}|${row.type}`);
      expect([fixture.name, keys.length]).toStrictEqual([
        fixture.name,
        new Set(keys).size,
      ]);
    }
  });

  test("edges é o que as linhas produzem, e o peso é aritmética conferida", () => {
    let checked = 0;
    for (const fixture of corpus) {
      if (!fixture.value.edges) {
        continue;
      }
      checked += 1;
      const rows = fixture.value.dependencies ?? [];
      const byPair = new Map<string, typeof rows>();
      for (const row of rows) {
        const pair = `${row.from} ${row.to}`;
        byPair.set(pair, [...(byPair.get(pair) ?? []), row]);
      }
      const recomputed = [...byPair.entries()]
        .sort(([left], [right]) => (left < right ? -1 : 1))
        .map(([pair, group]) => {
          const [from, to] = pair.split(" ");
          const ordered = [...group].sort(
            (left, right) =>
              DEPENDENCY_TYPE_ORDER.indexOf(left.type) -
              DEPENDENCY_TYPE_ORDER.indexOf(right.type)
          );
          const types: Record<string, number> = {};
          for (const row of ordered) {
            types[row.type] = row.occurrences;
          }
          return {
            distinctTypeCount: ordered.length,
            from,
            occurrenceTotal: ordered.reduce(
              (sum, row) => sum + row.occurrences,
              0
            ),
            quality: ordered.reduce(
              (weakest, row) =>
                RESOLUTION_STRENGTH[row.resolution] <
                RESOLUTION_STRENGTH[weakest]
                  ? row.resolution
                  : weakest,
              ordered[0]?.resolution ?? "RESOLVED"
            ),
            to,
            types,
            weight: ordered.reduce(
              (sum, row) =>
                sum + DEPENDENCY_TYPE_WEIGHT[row.type] * row.occurrences,
              0
            ),
          };
        });
      expect([fixture.name, fixture.value.edges]).toStrictEqual([
        fixture.name,
        recomputed,
      ]);
    }
    expect(checked).toBeGreaterThan(0);
  });
});

describe("conferência cruzada com o disco", () => {
  test("todo file: aponta para um arquivo que existe", () => {
    let checked = 0;
    for (const fixture of corpus) {
      for (const id of fixture.value.files ?? []) {
        checked += 1;
        const relative = id.slice("file:".length);
        expect([
          fixture.name,
          id,
          existsSync(path.join(fixture.dir, relative)),
        ]).toStrictEqual([fixture.name, id, true]);
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("javaFileCount é o número de *.java sob a raiz, recursivo", () => {
    let checked = 0;
    for (const fixture of corpus) {
      for (const root of fixture.value.sourceRoots ?? []) {
        checked += 1;
        const onDisk = javaFilesUnder(
          path.join(fixture.dir, root.path),
          fixture.dir
        );
        expect([fixture.name, root.path, root.javaFileCount]).toStrictEqual([
          fixture.name,
          root.path,
          onDisk.length,
        ]);
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("quando files é reivindicado, ele é exatamente o que está no disco", () => {
    let checked = 0;
    for (const fixture of corpus) {
      if (!(fixture.value.files && fixture.value.sourceRoots)) {
        continue;
      }
      checked += 1;
      const onDisk = fixture.value.sourceRoots
        .flatMap((root) =>
          javaFilesUnder(path.join(fixture.dir, root.path), fixture.dir)
        )
        .map((relative) => `file:${relative}`)
        .sort();
      expect([fixture.name, fixture.value.files]).toStrictEqual([
        fixture.name,
        onDisk,
      ]);
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("totalFiles é o número de fontes Java sob as raízes MAIN", () => {
    let checked = 0;
    for (const fixture of corpus) {
      if (!(fixture.value.completeness && fixture.value.sourceRoots)) {
        continue;
      }
      checked += 1;
      const considered = fixture.value.sourceRoots
        .filter((root) => root.kind === "MAIN")
        .reduce((sum, root) => sum + root.javaFileCount, 0);
      expect([
        fixture.name,
        fixture.value.completeness.totalFiles,
      ]).toStrictEqual([fixture.name, considered]);
    }
    expect(checked).toBeGreaterThan(0);
  });

  test("cada âncora de evidência aparece na linha declarada", () => {
    let checked = 0;
    for (const fixture of corpus) {
      for (const row of fixture.value.evidence ?? []) {
        checked += 1;
        const lines = readFileSync(
          path.join(fixture.dir, row.file),
          "utf8"
        ).split("\n");
        expect([fixture.name, row.file, row.line]).toStrictEqual([
          fixture.name,
          row.file,
          row.line,
        ]);
        expect(lines[row.line - 1] ?? "").toContain(row.anchor);
      }
    }
    expect(checked).toBeGreaterThan(0);
  });
});
