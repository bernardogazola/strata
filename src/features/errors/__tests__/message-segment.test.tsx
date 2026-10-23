import { render } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { MessageSegments } from "@/features/errors/message-segment";
import { formatProtocolError, type MessageSegment } from "@/i18n/errors";
import { DEFAULT_UNTRUSTED_MAX_CHARS } from "@/lib/untrusted";

const BIDI_CONTROL = /\p{Bidi_Control}/u;
const CONTROL = /\p{Cc}/u;
const PRESERVED_CONTROLS = new Set(["\t", "\n"]);

const hasStrippableControl = (text: string): boolean =>
  Array.from(text).some(
    (character) => CONTROL.test(character) && !PRESERVED_CONTROLS.has(character)
  );

const RLO = String.fromCodePoint(0x20_2e);
const LRI = String.fromCodePoint(0x20_66);
const PDI = String.fromCodePoint(0x20_69);
const RLM = String.fromCodePoint(0x20_0f);
const ALM = String.fromCodePoint(0x06_1c);
const ESC = String.fromCodePoint(0x1b);

const UNTRUSTED_MARKER = "Payment.java";

const chrome = (text: string): MessageSegment => ({ kind: "chrome", text });
const untrusted = (value: string): MessageSegment => ({
  kind: "untrusted",
  value,
});

const renderSegments = (segments: readonly MessageSegment[]) =>
  render(
    <p>
      <MessageSegments segments={segments} />
    </p>
  );

afterEach(() => {
  vi.restoreAllMocks();
});

describe("MessageSegments", () => {
  test('o valor não confiável vai em um <bdi lang="" translate="no">', () => {
    const { container } = renderSegments([
      chrome("O arquivo "),
      untrusted("src/main/java/Payment.java"),
      chrome(" passa do limite."),
    ]);

    const isolated = container.querySelectorAll("bdi");
    expect(isolated).toHaveLength(1);
    const [bdi] = isolated;
    expect(bdi.textContent).toBe("src/main/java/Payment.java");
    expect(bdi.getAttribute("lang")).toBe("");
    expect(bdi.getAttribute("translate")).toBe("no");
    expect(bdi).toHaveClass("font-mono", "text-muted-foreground");
  });

  test("um texto Trojan Source chega ao DOM sem controle de direção", () => {
    const forged = `src/${RLO}avaj.tnemyaP${LRI}Fake${PDI}${RLM}${ALM}${ESC}[31m.java`;
    const { container } = renderSegments([
      chrome("Não foi possível interpretar o arquivo "),
      untrusted(forged),
      chrome(", que ficou de fora."),
    ]);

    expect(BIDI_CONTROL.test(forged)).toBe(true);
    expect(BIDI_CONTROL.test(container.textContent ?? "")).toBe(false);
    expect(hasStrippableControl(forged)).toBe(true);
    expect(hasStrippableControl(container.textContent ?? "")).toBe(false);
    expect(container.querySelector("bdi")?.textContent).toBe(
      "src/avaj.tnemyaPFake[31m.java"
    );
  });

  test("um valor acima do teto é cortado com reticências", () => {
    const long = "a".repeat(DEFAULT_UNTRUSTED_MAX_CHARS + 50);
    const { container } = renderSegments([untrusted(long)]);

    expect(container.querySelector("bdi")?.textContent).toBe(
      `${"a".repeat(DEFAULT_UNTRUSTED_MAX_CHARS)}…`
    );
  });

  test("o valor não confiável nunca aparece dentro de um segmento chrome", () => {
    const { container } = renderSegments([
      chrome("O arquivo "),
      untrusted(`src/${UNTRUSTED_MARKER}`),
      chrome(" declara um elemento que "),
      untrusted(`lib/${UNTRUSTED_MARKER}`),
      chrome(" já declara."),
    ]);

    const chromeSpans = [...container.querySelectorAll("span")];
    expect(chromeSpans.map((span) => span.textContent)).toEqual([
      "O arquivo ",
      " declara um elemento que ",
      " já declara.",
    ]);
    for (const span of chromeSpans) {
      expect(span.textContent).not.toContain(UNTRUSTED_MARKER);
      expect(span.querySelector("bdi")).toBeNull();
    }
    for (const bdi of container.querySelectorAll("bdi")) {
      expect(bdi.closest("span")).toBeNull();
    }
  });

  test("o texto do produto não é isolado em <bdi>", () => {
    const { container } = renderSegments([
      chrome("O Strata encontrou um erro inesperado."),
    ]);

    expect(container.querySelector("bdi")).toBeNull();
    const [span] = container.querySelectorAll("span");
    expect(span.textContent).toBe("O Strata encontrou um erro inesperado.");
    expect(span.closest("bdi")).toBeNull();
  });

  test("marcação dentro do valor é texto, nunca elemento", () => {
    const { container } = renderSegments([
      untrusted('<img src="x" onerror="alert(1)">'),
    ]);

    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("bdi")?.textContent).toBe(
      '<img src="x" onerror="alert(1)">'
    );
  });

  test("segmentos repetidos renderizam todos, sem chave duplicada", () => {
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    const { container } = renderSegments([
      untrusted("A.java"),
      chrome(" e "),
      untrusted("A.java"),
      chrome(" e "),
      untrusted("A.java"),
    ]);

    expect(container.querySelectorAll("bdi")).toHaveLength(3);
    expect(container.querySelectorAll("span")).toHaveLength(2);
    expect(consoleError).not.toHaveBeenCalled();
  });

  test("o corpo montado por formatProtocolError isola o caminho do arquivo", () => {
    const message = formatProtocolError({
      code: "SOURCE_PARSE_FAILED",
      params: { filePath: `src/${UNTRUSTED_MARKER}` },
    });
    const { container } = renderSegments(message.body);

    expect(container.querySelector("bdi")?.textContent).toBe(
      `src/${UNTRUSTED_MARKER}`
    );
    expect(container.textContent).toBe(
      `Não foi possível interpretar o arquivo src/${UNTRUSTED_MARKER}, que ficou de fora da análise estrutural.`
    );
  });
});
