const CONTROL_CHARACTERS = /\p{Cc}/gu;

const BIDI_CONTROLS = /\p{Bidi_Control}/gu;

const PRESERVED_CONTROL_CHARACTERS = new Set(["\t", "\n"]);

export const DEFAULT_UNTRUSTED_MAX_CHARS = 200;

export const sanitizeUntrusted = (value: string): string =>
  value
    .replace(CONTROL_CHARACTERS, (character) =>
      PRESERVED_CONTROL_CHARACTERS.has(character) ? character : ""
    )
    .replace(BIDI_CONTROLS, "");

export const clampText = (
  value: string,
  maxChars: number = DEFAULT_UNTRUSTED_MAX_CHARS
): string => {
  const points = Array.from(value);
  if (points.length <= maxChars) {
    return value;
  }

  return `${points.slice(0, maxChars).join("")}…`;
};
