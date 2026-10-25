import { render, screen, within } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import { MethodList } from "@/features/inspector/method-list";
import { interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { type ElementDetail, elementDetailSchema } from "@/protocol/graph";
import { DESCRIBED_IDS, detailOf } from "@/test/fixture-graph";

const ELEMENT_METHODS_MAX = 1000;

const DECLARED = 1204;

const typeIdOf = (label: string): string => {
  const id = DESCRIBED_IDS.find(
    (candidate) =>
      candidate.startsWith("type:") && detailOf(candidate).label === label
  );
  if (id === undefined) {
    throw new Error(`nenhum tipo descrito tem o rótulo ${label}`);
  }
  return id;
};

const cutDetail = (): ElementDetail => {
  const catalog = detailOf(typeIdOf("Catalog"));
  const methods = Array.from({ length: ELEMENT_METHODS_MAX }, (_, n) => ({
    cc: 1,
    elementId: `method:${catalog.qualifiedName}#m${n}()`,
    lines: { end: n + 2, start: n + 1 },
    signature: `m${n}()`,
  }));
  return elementDetailSchema.parse({
    ...catalog,
    methods,
    methodTotal: DECLARED,
  });
};

describe("o corte da lista de métodos", () => {
  test("acima do teto, a frase diz os dois números e a lista tem as linhas do fio", () => {
    const detail = cutDetail();

    const { container } = render(
      <MethodList methods={detail.methods} methodTotal={detail.methodTotal} />
    );

    expect(
      screen.getByText(
        interpolate(t.inspector.methods.cut, {
          shown: ELEMENT_METHODS_MAX,
          total: DECLARED,
        })
      )
    ).toBeDefined();
    expect(
      screen.getByText(
        interpolate(t.inspector.methods.title, { count: DECLARED })
      )
    ).toBeDefined();
    const list = container.querySelector("ol");
    expect(list).not.toBeNull();
    expect(within(list as HTMLElement).getAllByRole("listitem")).toHaveLength(
      ELEMENT_METHODS_MAX
    );
  });

  test("sem corte, nenhuma frase de corte", () => {
    const catalog = detailOf(typeIdOf("Catalog"));
    expect(catalog.methodTotal).toBe(catalog.methods.length);
    const cutStart = t.inspector.methods.cut.split("{")[0] ?? "";

    const { container } = render(
      <MethodList methods={catalog.methods} methodTotal={catalog.methodTotal} />
    );

    expect(container.textContent ?? "").not.toContain(cutStart);
  });
});

describe("as linhas de um método", () => {
  test("um método de uma linha só diz 'linha 8', nunca 'linhas 8 a 8'", () => {
    const store = detailOf(typeIdOf("Store"));
    const [save] = store.methods;
    expect(save?.lines?.start).toBe(save?.lines?.end);
    const line = save?.lines?.start ?? 0;

    render(
      <MethodList methods={store.methods} methodTotal={store.methodTotal} />
    );

    expect(
      screen.getByText(interpolate(t.inspector.header.line, { line }))
    ).toBeDefined();
    expect(
      screen.queryByText(
        interpolate(t.inspector.header.lines, { end: line, start: line })
      )
    ).toBeNull();
  });

  test("um tipo sem método diz que não declara nenhum, e não abre a coluna da complexidade", () => {
    const audited = detailOf(typeIdOf("Audited"));
    expect(audited.methodTotal).toBe(0);

    render(
      <MethodList methods={audited.methods} methodTotal={audited.methodTotal} />
    );

    expect(screen.getByText(t.inspector.methods.none)).toBeDefined();
    expect(screen.queryByText(t.inspector.methods.ccHeader)).toBeNull();
    expect(screen.queryByText(t.inspector.methods.ccNote)).toBeNull();
  });
});
