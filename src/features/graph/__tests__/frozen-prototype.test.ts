import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { describe, expect, test } from "vitest";

const require = createRequire(import.meta.url);

const CYTOSCAPE_ESM = pathToFileURL(
  require
    .resolve("cytoscape")
    .replace(/cytoscape\.cjs\.js$/, "cytoscape.esm.mjs")
).href;

const SCRIPT = `
Object.freeze(Object.prototype);
const { default: cytoscape } = await import(${JSON.stringify(CYTOSCAPE_ESM)});
const cy = cytoscape({
  headless: true,
  styleEnabled: true,
  style: [
    { selector: "node", style: { label: "data(label)", "text-outline-width": 2 } },
    { selector: "node.selected", style: { "border-width": 4 } },
  ],
});
cy.add([
  { data: { id: "a", label: "orders" } },
  { data: { id: "b", label: "billing" } },
  { data: { id: "e", source: "a", target: "b" } },
]);
cy.layout({ name: "concentric", animate: false, avoidOverlap: true }).run();
cy.$("#a").addClass("selected");
process.stdout.write(JSON.stringify({
  borderWidth: cy.$("#a").style("border-width"),
  selected: cy.$("node.selected").length,
}));
cy.destroy();
`;

describe("o Cytoscape com o Object.prototype congelado", () => {
  test("avalia, aplica a folha de estilo, roda o layout e responde a um seletor", () => {
    const output = execFileSync(
      process.execPath,
      ["--input-type=module", "--eval", SCRIPT],
      { encoding: "utf8" }
    );

    expect(JSON.parse(output)).toStrictEqual({
      borderWidth: "4px",
      selected: 1,
    });
  });
});
