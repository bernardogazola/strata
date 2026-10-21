import { render, screen, within } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import { t } from "@/i18n/messages";
import type { RepositoryDescription } from "@/protocol/repository";
import { DescribePanel, DescribePanelSkeleton } from "../describe-panel";

const OK: RepositoryDescription = {
  buildSystem: "MAVEN",
  cachedAnalysisId: null,
  declaredModules: ["core", "api"],
  defaultBranch: "main",
  displayName: "multi-module",
  headCommitId: "fc5c752e7e9610c39de3b7b7caf2a74b7dfe6249",
  isBare: false,
  isGitRepository: true,
  isShallow: false,
  sourceRoots: [
    {
      id: "sr_sqbycfms",
      javaFileCount: 2,
      kind: "MAIN",
      label: "api/src/main/java",
    },
    {
      id: "sr_3gwc7d6d",
      javaFileCount: 6,
      kind: "MAIN",
      label: "core/src/main/java",
    },
    {
      id: "sr_jc51sw4p",
      javaFileCount: 1,
      kind: "TEST",
      label: "core/src/test/java",
    },
    {
      id: "sr_3sscth34",
      javaFileCount: 1,
      kind: "GENERATED",
      label: "core/target/generated-sources/annotations",
    },
  ],
  totalFileCount: 14,
};

const factValue = (label: string): HTMLElement => {
  const term = screen.getByText(label, { selector: "dt" });
  const value = term.nextElementSibling;
  if (!(value instanceof HTMLElement)) {
    throw new Error(`sem valor para ${label}`);
  }
  return value;
};

const isolationOf = (element: HTMLElement) => ({
  lang: element.getAttribute("lang"),
  tag: element.tagName,
  translate: element.getAttribute("translate"),
});

const ISOLATED = { lang: "", tag: "BDI", translate: "no" };

describe("DescribePanel", () => {
  test("mostra os seis fatos do describe", () => {
    render(<DescribePanel description={OK} />);

    expect(
      screen.getByRole("heading", { level: 1, name: "multi-module" })
    ).toBeDefined();
    expect(factValue(t.describe.facts.buildSystem).textContent).toBe("Maven");
    expect(factValue(t.describe.facts.modules).textContent).toBe("core, api");
    expect(factValue(t.describe.facts.branch).textContent).toBe("main");
    expect(factValue(t.describe.facts.commit).textContent).toBe("fc5c752");
    expect(factValue(t.describe.facts.files).textContent).toBe(
      "14 arquivos no total"
    );
    expect(factValue(t.describe.facts.java).textContent).toBe(
      "10 arquivos em 4 raízes"
    );
    expect(screen.queryByText(t.analysis.partial.shallowClone)).toBeNull();
  });

  test("todo valor do repositório vai em um <bdi> próprio, um por módulo", () => {
    render(<DescribePanel description={OK} />);

    expect(isolationOf(screen.getByText("multi-module"))).toEqual(ISOLATED);
    const modules = within(factValue(t.describe.facts.modules));
    expect(isolationOf(modules.getByText("core"))).toEqual(ISOLATED);
    expect(isolationOf(modules.getByText("api"))).toEqual(ISOLATED);
    expect(isolationOf(screen.getByText("main"))).toEqual(ISOLATED);
    expect(isolationOf(screen.getByText("fc5c752"))).toEqual(ISOLATED);
  });

  test("um controle de direção no nome não chega ao DOM", () => {
    const override = String.fromCodePoint(0x20_2e);
    render(
      <DescribePanel
        description={{ ...OK, displayName: `orders${override}ecivres` }}
      />
    );

    const title = screen.getByRole("heading", { level: 1 });
    expect(title.textContent).toBe("ordersecivres");
  });

  test("HEAD destacado, sem commit, sem módulos e sem arquivo têm frase própria", () => {
    render(
      <DescribePanel
        description={{
          ...OK,
          declaredModules: [],
          defaultBranch: null,
          headCommitId: null,
          totalFileCount: 0,
        }}
      />
    );

    expect(factValue(t.describe.facts.branch).textContent).toBe(
      t.describe.facts.detached
    );
    expect(factValue(t.describe.facts.commit).textContent).toBe(
      t.describe.facts.unborn
    );
    expect(factValue(t.describe.facts.modules).textContent).toBe(
      t.describe.facts.noModules
    );
    expect(factValue(t.describe.facts.files).textContent).toBe(
      "Nenhum arquivo"
    );
  });

  test("o mesmo nome de módulo duas vezes aparece duas vezes", () => {
    render(
      <DescribePanel
        description={{ ...OK, declaredModules: ["core", "core"] }}
      />
    );

    expect(factValue(t.describe.facts.modules).textContent).toBe("core, core");
  });

  test("um clone raso ganha a ressalva quieta", () => {
    render(<DescribePanel description={{ ...OK, isShallow: true }} />);

    expect(screen.getByText(t.analysis.partial.shallowClone)).toBeDefined();
  });

  test("o singular de uma raiz com um arquivo", () => {
    render(
      <DescribePanel
        description={{
          ...OK,
          sourceRoots: [
            { id: "sr_kaakvjgt", javaFileCount: 1, kind: "MAIN", label: "" },
          ],
          totalFileCount: 1,
        }}
      />
    );

    expect(factValue(t.describe.facts.java).textContent).toBe(
      "1 arquivo em 1 raiz"
    );
    expect(factValue(t.describe.facts.files).textContent).toBe(
      "1 arquivo no total"
    );
  });
});

describe("DescribePanelSkeleton", () => {
  test("anuncia a leitura só para o leitor de tela", () => {
    render(<DescribePanelSkeleton />);

    expect(screen.getByRole("status")).toHaveTextContent(t.describe.loading);
  });
});
