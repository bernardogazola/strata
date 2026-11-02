import { render } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import {
  elementDisplayLabel,
  GRAPH_LABEL_MAX_CHARS,
} from "@/features/graph/adapter";
import { ElementLabel } from "@/features/graph/element-label";
import { t } from "@/i18n/messages";

const RIGHT_TO_LEFT_OVERRIDE = String.fromCodePoint(0x20_2e);

describe("elementDisplayLabel", () => {
  test("tira o controle bidi e corta no teto do canvas, com reticências", () => {
    const label = `${RIGHT_TO_LEFT_OVERRIDE}${"A".repeat(GRAPH_LABEL_MAX_CHARS + 20)}`;

    expect(elementDisplayLabel("TYPE", label)).toBe(
      `${"A".repeat(GRAPH_LABEL_MAX_CHARS)}…`
    );
  });

  test("o pacote padrão ganha o nome do catálogo; um tipo sem nome continua vazio", () => {
    expect(elementDisplayLabel("PACKAGE", "")).toBe(t.graph.defaultPackage);
    expect(elementDisplayLabel("TYPE", "")).toBe("");
  });
});

describe("ElementLabel", () => {
  test("o texto do repositório vai em um bdi isolado e fora do tradutor", () => {
    const { container } = render(
      <ElementLabel kind="TYPE" label="CatalogEntry" />
    );
    const bdi = container.querySelector("bdi");

    expect(bdi?.textContent).toBe("CatalogEntry");
    expect(bdi?.getAttribute("lang")).toBe("");
    expect(bdi?.getAttribute("translate")).toBe("no");
  });

  test("o nome do pacote padrão é copy do produto e fica fora do bdi", () => {
    const { container } = render(<ElementLabel kind="PACKAGE" label="" />);

    expect(container.querySelector("bdi")).toBeNull();
    expect(container.textContent).toBe(t.graph.defaultPackage);
  });
});
