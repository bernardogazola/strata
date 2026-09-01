import { describe, expect, test } from "vitest";
import { z } from "zod";
import {
  hotspotEvidenceCodeSchema,
  tourReasonTagSchema,
} from "@/protocol/enums";
import { hotspotEvidenceCodeLabels, tourReasonTagLabels } from "../labels";

const UNRENDERED_SCHEMAS = new Set(["errorOriginSchema"]);

const SCHEMA_SUFFIX = /Schema$/;

const TEMPLATE_PLACEHOLDER = /\{[a-zA-Z][a-zA-Z0-9]*\}/;

const protocolModules = import.meta.glob<Record<string, unknown>>(
  ["../../protocol/**/*.ts", "!../../protocol/**/__tests__/**"],
  { eager: true }
);

const i18nModules = import.meta.glob<Record<string, unknown>>("../*.ts", {
  eager: true,
});

const labelExports = i18nModules["../labels.ts"];

interface ProtocolEnum {
  readonly members: readonly string[];
  readonly schemaName: string;
}

const collectProtocolEnums = (): ProtocolEnum[] => {
  const collected: ProtocolEnum[] = [];

  for (const module of Object.values(protocolModules)) {
    for (const [schemaName, value] of Object.entries(module)) {
      if (value instanceof z.ZodEnum) {
        collected.push({
          members: value.options.map((option) => String(option)),
          schemaName,
        });
      }
    }
  }

  return collected.sort((a, b) => a.schemaName.localeCompare(b.schemaName));
};

const PROTOCOL_ENUMS = collectProtocolEnums();

const RENDERED_ENUMS = PROTOCOL_ENUMS.filter(
  (found) => !UNRENDERED_SCHEMAS.has(found.schemaName)
);

const isLabelMap = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const readLabel = (entry: unknown): string => {
  if (typeof entry === "string") {
    return entry;
  }

  if (isLabelMap(entry) && typeof entry.label === "string") {
    return entry.label;
  }

  return "";
};

describe("cobertura dos mapas de rótulos", () => {
  test("a descoberta enxerga os dois módulos", () => {
    expect(PROTOCOL_ENUMS.map((found) => found.schemaName)).toContain(
      "analysisStageSchema"
    );
    expect(isLabelMap(labelExports)).toBe(true);
  });

  test("todo enum do protocolo é exportado com sufixo Schema", () => {
    for (const { schemaName } of PROTOCOL_ENUMS) {
      expect(schemaName).toMatch(SCHEMA_SUFFIX);
    }
  });

  test("todo enum renderizado tem um mapa de rótulos", () => {
    for (const { schemaName } of RENDERED_ENUMS) {
      const mapName = schemaName.replace(SCHEMA_SUFFIX, "Labels");
      expect(
        isLabelMap(labelExports[mapName]),
        `falta ${mapName} em src/i18n/labels.ts, para ${schemaName}`
      ).toBe(true);
    }
  });

  test("todo membro de todo enum renderizado tem rótulo em pt-BR", () => {
    for (const { members, schemaName } of RENDERED_ENUMS) {
      const map = labelExports[schemaName.replace(SCHEMA_SUFFIX, "Labels")];

      for (const member of members) {
        const label = isLabelMap(map) ? readLabel(map[member]) : "";
        expect(label.trim(), `${schemaName}.${member}`).not.toBe("");
      }
    }
  });

  test("a lista de isentos só cita enums que existem", () => {
    const names = PROTOCOL_ENUMS.map((found) => found.schemaName);
    for (const exempt of UNRENDERED_SCHEMAS) {
      expect(names).toContain(exempt);
    }
  });

  test("nenhum rótulo exportado por labels.ts é vazio", () => {
    for (const [mapName, map] of Object.entries(labelExports)) {
      if (!isLabelMap(map)) {
        continue;
      }

      for (const [key, entry] of Object.entries(map)) {
        expect(readLabel(entry).trim(), `${mapName}.${key}`).not.toBe("");
      }
    }
  });
});

describe("tags de motivo e códigos de evidência", () => {
  test("toda tag de motivo tem rótulo e explicação", () => {
    for (const tag of tourReasonTagSchema.options) {
      const entry = tourReasonTagLabels[tag];
      expect(entry.label.trim(), tag).not.toBe("");
      expect(entry.explanation.trim(), tag).not.toBe("");
    }
  });

  test("todo código de evidência tem rótulo, explicação e template", () => {
    for (const code of hotspotEvidenceCodeSchema.options) {
      const entry = hotspotEvidenceCodeLabels[code];
      expect(entry.label.trim(), code).not.toBe("");
      expect(entry.explanation.trim(), code).not.toBe("");
      expect(entry.template, code).toMatch(TEMPLATE_PLACEHOLDER);
    }
  });

  test("nome igual entre os vocabulários não quer dizer texto igual", () => {
    expect(hotspotEvidenceCodeLabels.HIGH_FAN_IN.explanation).not.toBe(
      tourReasonTagLabels.HIGH_FAN_IN.explanation
    );
  });
});
