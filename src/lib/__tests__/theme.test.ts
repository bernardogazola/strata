import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";
import {
  BACKGROUND_COLORS,
  notifyResolvedThemeApplied,
  onResolvedThemeApplied,
  type ResolvedTheme,
} from "@/lib/theme";

const REPOSITORY_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../.."
);

const readRepositoryFile = (relative: string): Promise<string> =>
  readFile(path.join(REPOSITORY_ROOT, relative), "utf8");

const ROOT_BACKGROUND = /^:root \{[^}]*?background-color: (#[0-9a-f]{6});/im;
const HTML_BACKGROUND = /^\s*html \{\s*background-color: (#[0-9a-f]{6});/im;
const HTML_DARK_BACKGROUND =
  /^\s*html\.dark \{\s*background-color: (#[0-9a-f]{6});/im;
const RUST_COLOR =
  /const BG_(LIGHT|DARK): Color = Color\((\d+), (\d+), (\d+), 255\);/g;

const captured = (pattern: RegExp, text: string): string | undefined => {
  const match = text.match(pattern);
  return match === null ? undefined : match[1].toLowerCase();
};

const toHex = (channels: readonly string[]): string =>
  `#${channels.map((channel) => Number(channel).toString(16).padStart(2, "0")).join("")}`;

describe("o registro de quem reage ao tema aplicado", () => {
  test("todo inscrito recebe o tema, na ordem de inscrição", () => {
    const calls: string[] = [];
    const stopFirst = onResolvedThemeApplied((resolved) =>
      calls.push(`first:${resolved}`)
    );
    const stopSecond = onResolvedThemeApplied((resolved) =>
      calls.push(`second:${resolved}`)
    );

    notifyResolvedThemeApplied("dark");
    stopFirst();
    stopSecond();

    expect(calls).toStrictEqual(["first:dark", "second:dark"]);
  });

  test("desinscrever cala o ouvinte, e desinscrever duas vezes é inofensivo", () => {
    const calls: ResolvedTheme[] = [];
    const stop = onResolvedThemeApplied((resolved) => calls.push(resolved));

    notifyResolvedThemeApplied("light");
    stop();
    stop();
    notifyResolvedThemeApplied("dark");

    expect(calls).toStrictEqual(["light"]);
  });

  test("avisar sem inscrito nenhum não faz nada", () => {
    expect(() => notifyResolvedThemeApplied("dark")).not.toThrow();
  });
});

describe("BACKGROUND_COLORS", () => {
  test("bate com global.css, com o <style> do index.html e com lib.rs", async () => {
    const [globalCss, indexHtml, libRs] = await Promise.all([
      readRepositoryFile("src/global.css"),
      readRepositoryFile("index.html"),
      readRepositoryFile("src-tauri/src/lib.rs"),
    ]);
    const rust = Object.fromEntries(
      [...libRs.matchAll(RUST_COLOR)].map(([, name, ...channels]) => [
        name,
        toHex(channels),
      ])
    );

    expect({
      css: captured(ROOT_BACKGROUND, globalCss),
      htmlDark: captured(HTML_DARK_BACKGROUND, indexHtml),
      htmlLight: captured(HTML_BACKGROUND, indexHtml),
      rust,
    }).toStrictEqual({
      css: BACKGROUND_COLORS.light.toLowerCase(),
      htmlDark: BACKGROUND_COLORS.dark.toLowerCase(),
      htmlLight: BACKGROUND_COLORS.light.toLowerCase(),
      rust: {
        DARK: BACKGROUND_COLORS.dark.toLowerCase(),
        LIGHT: BACKGROUND_COLORS.light.toLowerCase(),
      },
    });
  });
});
