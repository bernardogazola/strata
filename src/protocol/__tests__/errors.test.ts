import { describe, expect, test } from "vitest";
import { ERROR_CODES, isKnownErrorCode } from "../errors";

describe("isKnownErrorCode", () => {
  test("reconhece os códigos do catálogo", () => {
    expect(isKnownErrorCode("REPO_NOT_A_GIT_REPOSITORY")).toBe(true);
  });

  test("não confunde membros herdados de Object.prototype com códigos", () => {
    expect(isKnownErrorCode("toString")).toBe(false);
    expect(isKnownErrorCode("constructor")).toBe(false);
    expect(isKnownErrorCode("hasOwnProperty")).toBe(false);
    expect(isKnownErrorCode("__proto__")).toBe(false);
  });
});

describe("ERROR_CODES", () => {
  test("o catálogo tem os 31 códigos", () => {
    expect(ERROR_CODES).toHaveLength(31);
  });
});
