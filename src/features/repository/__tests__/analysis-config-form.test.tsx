import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { t } from "@/i18n/messages";
import type {
  AnalysisConfiguration,
  RepositoryDescription,
  SourceRoot,
} from "@/protocol/repository";
import { type BackendState, useAppStore } from "@/state/app-store";
import {
  AnalysisConfigForm,
  type AnalysisConfigFormProps,
  configurationFormSchema,
} from "../analysis-config-form";

const initialStore = useAppStore.getState();

afterEach(() => {
  useAppStore.setState(initialStore, true);
});

const UNAVAILABLE_STATES = [
  { phase: "STARTING" },
  { exitCode: 1, phase: "CRASHED" },
  { expected: 1, phase: "INCOMPATIBLE", received: 2 },
] as const satisfies readonly Exclude<BackendState, { phase: "READY" }>[];

const API = "sr_sqbycfms";
const CORE = "sr_3gwc7d6d";
const CORE_TEST = "sr_jc51sw4p";
const GENERATED = "sr_3sscth34";

const ROOTS: readonly SourceRoot[] = [
  { id: API, javaFileCount: 2, kind: "MAIN", label: "api/src/main/java" },
  { id: CORE, javaFileCount: 6, kind: "MAIN", label: "core/src/main/java" },
  {
    id: CORE_TEST,
    javaFileCount: 1,
    kind: "TEST",
    label: "core/src/test/java",
  },
  {
    id: GENERATED,
    javaFileCount: 1,
    kind: "GENERATED",
    label: "core/target/generated-sources/annotations",
  },
];

const describeWith = (
  sourceRoots: readonly SourceRoot[]
): RepositoryDescription => ({
  buildSystem: "MAVEN",
  cachedAnalysisId: null,
  declaredModules: ["core", "api"],
  defaultBranch: "main",
  displayName: "multi-module",
  headCommitId: "fc5c752e7e9610c39de3b7b7caf2a74b7dfe6249",
  isBare: false,
  isGitRepository: true,
  isShallow: false,
  sourceRoots: [...sourceRoots],
  totalFileCount: 14,
});

const DEFAULT_SUBMISSION: AnalysisConfiguration = {
  historyScope: "ALL",
  historyScopeCommits: null,
  includeTests: false,
  rebuildCache: false,
  sourceRootIds: null,
};

const renderForm = (overrides: Partial<AnalysisConfigFormProps> = {}) => {
  const onSubmit = vi.fn<(configuration: AnalysisConfiguration) => void>();
  render(
    <AnalysisConfigForm
      backendReady
      description={describeWith(ROOTS)}
      onSubmit={onSubmit}
      pending={false}
      runInFlight={false}
      {...overrides}
    />
  );
  return { onSubmit };
};

const analyze = () => screen.getByRole("button", { name: t.config.analyze });

const ZERO_FILES = /\b0 arquivo/;

const rootCheckbox = (name: string) => screen.getByRole("checkbox", { name });

const groupToggle = (name: string, group: string) =>
  screen.getByRole("button", { description: group, name });

const submittedWith = async (
  onSubmit: ReturnType<typeof renderForm>["onSubmit"]
): Promise<AnalysisConfiguration | undefined> => {
  fireEvent.click(analyze());
  await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  return onSubmit.mock.calls[0]?.[0];
};

describe("AnalysisConfigForm", () => {
  test("os quatro campos existem, e os dois sem consumidor estão desabilitados com o motivo", () => {
    renderForm();

    const tests = screen.getByRole("switch", {
      name: t.config.includeTests.label,
    });
    expect(tests).not.toBeChecked();
    expect(tests).toHaveAccessibleDescription(
      t.config.includeTests.description
    );

    const roots = screen.getByRole("group", { name: t.config.roots.label });
    expect(within(roots).getAllByRole("checkbox")).toHaveLength(ROOTS.length);

    const history = screen.getByRole("radiogroup", {
      name: t.config.history.label,
    });
    expect(history).toHaveAccessibleDescription(
      `${t.config.history.unavailable} ${t.config.history.description}`
    );
    for (const radio of within(history).getAllByRole("radio")) {
      expect(radio).toHaveAttribute("aria-disabled", "true");
    }
    expect(
      within(history).getByRole("radio", { name: "Todo o histórico" })
    ).toBeChecked();
    const commits = screen.getByRole("textbox", {
      name: t.config.history.commits,
    });
    expect(commits).toBeDisabled();
    expect(commits).toHaveAccessibleDescription(
      `${t.config.history.unavailable} ${t.config.history.description}`
    );

    const rebuild = screen.getByRole("checkbox", {
      name: t.config.rebuild.label,
    });
    expect(rebuild).toHaveAttribute("aria-disabled", "true");
    expect(rebuild).not.toBeChecked();
    expect(rebuild).toHaveAccessibleDescription(
      `${t.config.rebuild.unavailable} ${t.config.rebuild.description}`
    );
  });

  test("a configuração padrão vai com sourceRootIds nulo", async () => {
    const { onSubmit } = renderForm();

    expect(rootCheckbox("api/src/main/java")).toBeChecked();
    expect(
      rootCheckbox("core/target/generated-sources/annotations")
    ).toBeChecked();
    expect(rootCheckbox("core/src/test/java")).not.toBeChecked();
    expect(screen.getByText("9 arquivos Java em 3 raízes")).toBeDefined();

    expect(await submittedWith(onSubmit)).toEqual(DEFAULT_SUBMISSION);
  });

  test("desmarcar uma raiz MAIN manda os ids explícitos, em ordem", async () => {
    const { onSubmit } = renderForm();

    fireEvent.click(rootCheckbox("core/src/main/java"));

    expect(screen.getByText("3 arquivos Java em 2 raízes")).toBeDefined();
    expect(await submittedWith(onSubmit)).toEqual({
      ...DEFAULT_SUBMISSION,
      sourceRootIds: [GENERATED, API],
    });
  });

  test("ligar os testes marca as raízes TEST, e o padrão continua nulo", async () => {
    const { onSubmit } = renderForm();

    fireEvent.click(
      screen.getByRole("switch", { name: t.config.includeTests.label })
    );

    expect(rootCheckbox("core/src/test/java")).toBeChecked();
    expect(screen.getByText("10 arquivos Java em 4 raízes")).toBeDefined();
    expect(await submittedWith(onSubmit)).toEqual({
      ...DEFAULT_SUBMISSION,
      includeTests: true,
    });
  });

  test("desligar os testes de novo tira as raízes TEST da marcação", () => {
    renderForm();
    const tests = screen.getByRole("switch", {
      name: t.config.includeTests.label,
    });

    fireEvent.click(tests);
    fireEvent.click(tests);

    expect(rootCheckbox("core/src/test/java")).not.toBeChecked();
    expect(rootCheckbox("core/src/main/java")).toBeChecked();
  });

  test("uma raiz TEST marcada à mão, com os testes desligados, vai explícita", async () => {
    const { onSubmit } = renderForm();

    fireEvent.click(rootCheckbox("core/src/test/java"));

    expect(await submittedWith(onSubmit)).toEqual({
      ...DEFAULT_SUBMISSION,
      sourceRootIds: [CORE, GENERATED, CORE_TEST, API],
    });
  });

  test("Σ = 0 desabilita Analisar com o motivo, e nada é enviado", () => {
    const { onSubmit } = renderForm();

    fireEvent.click(groupToggle(t.config.roots.clearAll, "Código principal"));
    fireEvent.click(groupToggle(t.config.roots.clearAll, "Código gerado"));

    expect(analyze()).toHaveAttribute("aria-disabled", "true");
    expect(analyze()).toHaveAccessibleDescription(
      t.config.disabled.emptySelection
    );
    fireEvent.click(analyze());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  test("sem raiz marcada, o resumo diz que nenhuma foi marcada, e nunca '0 arquivo'", () => {
    renderForm();

    fireEvent.click(groupToggle(t.config.roots.clearAll, "Código principal"));
    fireEvent.click(groupToggle(t.config.roots.clearAll, "Código gerado"));

    expect(screen.getByText(t.config.summaryNone)).toBeDefined();
    expect(screen.queryByText(ZERO_FILES)).toBeNull();
    expect(screen.getByText(t.config.summaryHint)).toBeDefined();
    expect(analyze()).toHaveAccessibleDescription(
      t.config.disabled.emptySelection
    );
  });

  test("Marcar todas devolve o grupo inteiro", () => {
    renderForm();

    fireEvent.click(groupToggle(t.config.roots.clearAll, "Código principal"));
    expect(rootCheckbox("api/src/main/java")).not.toBeChecked();
    expect(rootCheckbox("core/src/main/java")).not.toBeChecked();

    fireEvent.click(groupToggle(t.config.roots.selectAll, "Código principal"));
    expect(rootCheckbox("api/src/main/java")).toBeChecked();
    expect(rootCheckbox("core/src/main/java")).toBeChecked();
  });

  test("o grupo TEST não tem marcar todas e diz que segue a opção acima", () => {
    renderForm();

    const testGroup = screen.getByRole("group", {
      name: `Código de teste ${t.config.roots.followsTests}`,
    });
    expect(within(testGroup).queryByRole("button")).toBeNull();
    expect(
      screen.getAllByRole("button", { name: t.config.roots.clearAll })
    ).toHaveLength(2);
  });

  test("uma análise em andamento desabilita Analisar com o motivo", () => {
    const { onSubmit } = renderForm({ runInFlight: true });

    expect(analyze()).toHaveAttribute("aria-disabled", "true");
    expect(analyze()).toHaveAccessibleDescription(
      t.config.disabled.runInFlight
    );
    fireEvent.click(analyze());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  test.each(UNAVAILABLE_STATES)(
    "o analisador em $phase desabilita Analisar com o motivo dessa fase",
    (backend) => {
      useAppStore.setState({ backend });
      renderForm({ backendReady: false, runInFlight: true });

      expect(analyze()).toHaveAttribute("aria-disabled", "true");
      expect(analyze()).toHaveAccessibleDescription(
        t.config.backendUnavailable[backend.phase]
      );
    }
  );

  test("o motivo de Analisar acompanha a fase quando ela muda", () => {
    useAppStore.setState({ backend: { phase: "STARTING" } });
    renderForm({ backendReady: false });
    expect(analyze()).toHaveAccessibleDescription(
      t.config.backendUnavailable.STARTING
    );

    act(() => {
      useAppStore.setState({ backend: { exitCode: 1, phase: "CRASHED" } });
    });

    expect(analyze()).toHaveAccessibleDescription(
      t.config.backendUnavailable.CRASHED
    );
  });

  test("o resumo, a dica e o motivo ficam no rodapé fixo, junto de Analisar", () => {
    useAppStore.setState({ backend: { phase: "STARTING" } });
    renderForm({ backendReady: false });

    const reason = screen.getByText(t.config.backendUnavailable.STARTING);
    let footer = analyze().parentElement;
    while (footer !== null && !footer.contains(reason)) {
      footer = footer.parentElement;
    }
    expect(footer).not.toBeNull();
    expect(footer).toHaveClass("sticky", "bottom-0");
    expect(footer).toHaveTextContent("9 arquivos Java em 3 raízes");
    expect(footer).toHaveTextContent(t.config.summaryHint);
    expect(footer?.tagName).not.toBe("FORM");
  });

  test("recriar o cache tem o selo e a opacidade do histórico", () => {
    renderForm();

    const rebuild = screen.getByRole("checkbox", {
      name: t.config.rebuild.label,
    });
    expect(rebuild).toHaveAccessibleDescription(
      `${t.config.rebuild.unavailable} ${t.config.rebuild.description}`
    );
    const badge = screen.getByText(t.config.rebuild.unavailable);
    expect(badge).toHaveAttribute("data-slot", "badge");
    const label = screen.getByText(t.config.rebuild.label);
    expect(rebuild).toHaveClass("opacity-50");
    expect(label).toHaveClass("opacity-50");
    expect(badge.closest(".opacity-50")).toBeNull();
    const historyRadio = screen.getByRole("radio", {
      name: "Todo o histórico",
    });
    expect(historyRadio.closest(".opacity-50")).not.toBeNull();
  });

  test("no escuro, o trilho desligado do switch usa o --input inteiro", () => {
    renderForm();

    const tests = screen.getByRole("switch", {
      name: t.config.includeTests.label,
    });
    expect(tests).toHaveClass("dark:data-unchecked:bg-input");
    expect(tests).not.toHaveClass("dark:data-unchecked:bg-input/80");
  });

  test("o início em voo deixa o botão inerte, com o mesmo rótulo e um spinner", () => {
    const { onSubmit } = renderForm({ pending: true });

    expect(analyze()).toHaveAttribute("aria-disabled", "true");
    expect(analyze()).toHaveAccessibleName(t.config.analyze);
    expect(analyze().querySelector('svg[aria-hidden="true"]')).not.toBeNull();
    fireEvent.click(analyze());
    expect(onSubmit).not.toHaveBeenCalled();
  });

  test('o rótulo "" é a raiz do repositório', () => {
    renderForm({
      description: describeWith([
        { id: "sr_kaakvjgt", javaFileCount: 1, kind: "MAIN", label: "" },
      ]),
    });

    expect(rootCheckbox(t.config.roots.repositoryRoot)).toBeChecked();
    expect(screen.getByText("1 arquivo Java em 1 raiz")).toBeDefined();
  });

  test("o rótulo vindo do repositório é saneado e isolado", () => {
    const override = String.fromCodePoint(0x20_2e);
    renderForm({
      description: describeWith([
        {
          id: "sr_kaakvjgt",
          javaFileCount: 1,
          kind: "MAIN",
          label: `src/${override}main/java`,
        },
      ]),
    });

    const label = screen.getByText("src/main/java");
    expect(label.tagName).toBe("BDI");
    expect(label).toHaveAttribute("lang", "");
    expect(label).toHaveAttribute("translate", "no");
    expect(label.textContent).not.toContain(override);
  });

  test("as raízes nunca são texto livre: nenhuma caixa de texto entre elas", () => {
    renderForm();

    const roots = screen.getByRole("group", { name: t.config.roots.label });
    expect(within(roots).queryAllByRole("textbox")).toHaveLength(0);
    const [commits, ...others] = screen.getAllByRole("textbox");
    expect(others).toHaveLength(0);
    expect(commits).toHaveAccessibleName(t.config.history.commits);
  });

  test("cada raiz descreve quantos arquivos Java tem", () => {
    renderForm();

    expect(rootCheckbox("core/src/main/java")).toHaveAccessibleDescription(
      "6 arquivos Java"
    );
    expect(rootCheckbox("core/src/test/java")).toHaveAccessibleDescription(
      "1 arquivo Java"
    );
  });
});

describe("configurationFormSchema", () => {
  test("a contagem de commits que falta cai no campo, em pt-BR", () => {
    const result = configurationFormSchema.safeParse({
      ...DEFAULT_SUBMISSION,
      historyScope: "LAST_N_COMMITS",
    });

    expect(result.success).toBe(false);
    expect(
      result.error?.issues.map(({ message, path }) => ({ message, path }))
    ).toContainEqual({
      message: t.config.history.invalid,
      path: ["historyScopeCommits"],
    });
  });
});
