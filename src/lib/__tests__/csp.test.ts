import { afterEach, describe, expect, test } from "vitest";
import { readCspNonce } from "@/lib/csp";

const appended: Element[] = [];

const appendToHead = (
  tag: "script" | "style",
  attributes: Readonly<Record<string, string>>
): void => {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    element.setAttribute(name, value);
  }
  document.head.append(element);
  appended.push(element);
};

afterEach(() => {
  for (const element of appended.splice(0)) {
    element.remove();
  }
});

describe("readCspNonce", () => {
  test("lê o nonce do <style> pela propriedade IDL, que é onde o Tauri o põe", () => {
    appendToHead("style", { nonce: "8143225806390744839" });
    expect(readCspNonce()).toBe("8143225806390744839");
  });

  test("um nonce de estilo vence o de script", () => {
    appendToHead("script", { nonce: "from-script" });
    appendToHead("style", { nonce: "from-style" });
    expect(readCspNonce()).toBe("from-style");
  });

  test("nonce vazio é ausência, e não um nonce", () => {
    appendToHead("style", { nonce: "" });
    expect(readCspNonce()).toBeUndefined();
  });

  test("sem elemento nenhum com nonce, devolve undefined", () => {
    expect(readCspNonce()).toBeUndefined();
  });

  test("sem <style> com nonce, cai no nonce de script", () => {
    appendToHead("script", { nonce: "from-script" });
    expect(readCspNonce()).toBe("from-script");
  });

  test("e por último no data-nonce de script", () => {
    appendToHead("script", { "data-nonce": "from-dataset" });
    expect(readCspNonce()).toBe("from-dataset");
  });
});
