import { vi } from "vitest";

const ROW_TAGS: ReadonlySet<string> = new Set(["TD", "TH", "THEAD", "TR"]);

export interface LayoutStub {
  readonly rowHeight: number;
  readonly viewport: { readonly height: number; readonly width: number };
}

export const DEFAULT_LAYOUT: LayoutStub = {
  rowHeight: 40,
  viewport: { height: 400, width: 280 },
};

export const stubTableLayout = (layout: LayoutStub = DEFAULT_LAYOUT): void => {
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockImplementation(
    function (this: HTMLElement) {
      return ROW_TAGS.has(this.tagName)
        ? layout.rowHeight
        : layout.viewport.height;
    }
  );
  vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(
    layout.viewport.width
  );
};
