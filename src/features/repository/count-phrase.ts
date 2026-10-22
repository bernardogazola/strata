import { interpolate, plural } from "@/i18n/format";

export interface CountForms {
  readonly one: string;
  readonly other: string;
  readonly zero?: string;
}

export const countPhrase = (count: number, forms: CountForms): string =>
  interpolate(plural(count, forms), { count });

export interface FilesInRootsTemplate {
  readonly files: CountForms;
  readonly roots: CountForms;
  readonly template: string;
}

export const filesInRoots = (
  phrase: FilesInRootsTemplate,
  files: number,
  roots: number
): string =>
  interpolate(phrase.template, {
    files: countPhrase(files, phrase.files),
    roots: countPhrase(roots, phrase.roots),
  });

export interface TypesInFilesTemplate {
  readonly files: CountForms;
  readonly template: string;
  readonly types: CountForms;
}

export const typesInFiles = (
  phrase: TypesInFilesTemplate,
  types: number,
  files: number
): string =>
  interpolate(phrase.template, {
    files: countPhrase(files, phrase.files),
    types: countPhrase(types, phrase.types),
  });
