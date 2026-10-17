import { readFileSync } from "node:fs";
import { setTimeout as delay } from "node:timers/promises";
import {
  type Browser,
  type BrowserContextOptions,
  expect,
  type Page,
} from "@playwright/test";
import type { GraphSlice } from "@/protocol/graph";
import { recordGesture } from "./frames";
import { mulberry32 } from "./synthetic-slice";

export const GRAPH_WRAPPER = "[data-graph-state]";

export const GRAPH_CONTAINER = '[data-graph-state] > [role="img"]';

export const READY_TIMEOUT_MS = 60_000;

const CYTOSCAPE_MOUSE_HIT_PX = 2;

export interface Point {
  readonly x: number;
  readonly y: number;
}

export interface SliceOverride {
  readonly method: "graph.slice";

  readonly request: { readonly level: string; readonly parentId?: string };
  readonly result: GraphSlice;
}

const FIXTURES_DIR = new URL("../../protocol/fixtures/", import.meta.url);

export const fixtureNodeIds = (fileName: string): string[] => {
  const response = readFileSync(new URL(fileName, FIXTURES_DIR), "utf8")
    .split("\n")
    .filter((line) => line.trim() !== "")
    .map((line) => JSON.parse(line) as { kind: string; payload: GraphSlice })
    .find((frame) => frame.kind === "response");
  if (response === undefined) {
    throw new Error(`${fileName} não tem frame de resposta`);
  }
  return response.payload.nodes.map((node) => node.id).sort();
};

interface GraphReading {
  readonly nodeIds: readonly string[] | null;
  readonly state: string | null;
}

const readGraph = (page: Page): Promise<GraphReading> =>
  page.evaluate((wrapper) => {
    const element = document.querySelector<HTMLElement>(wrapper);
    return {
      nodeIds: window.__STRATA_GRAPH__?.nodeIds() ?? null,
      state: element?.dataset.graphState ?? null,
    };
  }, GRAPH_WRAPPER);

export const expectGraph = async (
  page: Page,
  nodeIds: readonly string[]
): Promise<void> => {
  await expect
    .poll(() => readGraph(page), { timeout: READY_TIMEOUT_MS })
    .toStrictEqual({ nodeIds, state: "ready" });
};

export const waitForFreshReady = (page: Page, nodes: number): Promise<void> =>
  page
    .locator(`[data-graph-state="ready"][data-node-count="${nodes}"]`)
    .waitFor({ timeout: READY_TIMEOUT_MS });

export const searchOf = (page: Page): Record<string, string> =>
  Object.fromEntries(new URL(page.url()).searchParams);

export const renderedPosition = async (
  page: Page,
  id: string
): Promise<Point> => {
  const position = await page.evaluate(
    (elementId) => window.__STRATA_GRAPH__?.renderedPosition(elementId) ?? null,
    id
  );
  if (position === null) {
    throw new Error(`o canvas não tem o elemento ${id}`);
  }
  return position;
};

export const styleOf = (
  page: Page,
  id: string,
  property: string
): Promise<string | null> =>
  page.evaluate(
    ({ elementId, name }) =>
      window.__STRATA_GRAPH__?.style(elementId, name) ?? null,
    { elementId: id, name: property }
  );

export const countMarks = (page: Page, name: string): Promise<number> =>
  page.evaluate(
    (markName) => performance.getEntriesByName(markName, "mark").length,
    name
  );

export const readPositions = (
  page: Page
): Promise<Record<string, Point | null>> =>
  page.evaluate(() => {
    const graph = window.__STRATA_GRAPH__;
    if (graph === undefined) {
      throw new Error("window.__STRATA_GRAPH__ não está registrado");
    }
    return Object.fromEntries(
      graph.nodeIds().map((id) => [id, graph.renderedPosition(id)])
    );
  });

export const tokenAsCytoscapeRgb = (
  page: Page,
  token: string
): Promise<string> =>
  page.evaluate((name) => {
    const declared = getComputedStyle(document.documentElement)
      .getPropertyValue(name)
      .trim();
    const probe = document.createElement("span");
    probe.style.color = declared;
    document.body.append(probe);
    const resolved = getComputedStyle(probe).color;
    probe.remove();
    return resolved.replaceAll(" ", "");
  }, token);

export const serveSlices = async (
  page: Page,
  overrides: readonly SliceOverride[]
): Promise<void> => {
  await page.addInitScript((served) => {
    window.__STRATA_FAKE_TRANSCRIPTS__ = served;
  }, overrides);
};

export const withFreshPage = async <T>(
  browser: Browser,
  options: BrowserContextOptions,
  run: (page: Page) => Promise<T>
): Promise<T> => {
  const context = await browser.newContext(options);
  try {
    return await run(await context.newPage());
  } finally {
    await context.close();
  }
};

export const settle = async (page: Page, quietMs: number): Promise<void> => {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => resolve());
        });
      })
  );
  await delay(quietMs);
};

export const fitGraph = (page: Page): Promise<number> =>
  page.evaluate(() => {
    const graph = window.__STRATA_GRAPH__;
    if (graph === undefined) {
      throw new Error("window.__STRATA_GRAPH__ não está registrado");
    }
    graph.fit();
    return graph.zoom();
  });

export const readZoom = (page: Page): Promise<number> =>
  page.evaluate(() => {
    const graph = window.__STRATA_GRAPH__;
    if (graph === undefined) {
      throw new Error("window.__STRATA_GRAPH__ não está registrado");
    }
    return graph.zoom();
  });

export const setZoom = (page: Page, level: number): Promise<void> =>
  page.evaluate((zoom) => {
    window.__STRATA_GRAPH__?.setZoom(zoom);
  }, level);

export interface ContainerBox {
  readonly height: number;
  readonly width: number;
  readonly x: number;
  readonly y: number;
}

export const containerBox = async (page: Page): Promise<ContainerBox> => {
  const box = await page.locator(GRAPH_CONTAINER).boundingBox();
  if (box === null) {
    throw new Error("o contêiner do grafo não tem caixa");
  }
  return box;
};

export const centreOf = (box: ContainerBox): Point => ({
  x: box.x + box.width / 2,
  y: box.y + box.height / 2,
});

export interface NodeHitArea {
  readonly id: string;

  readonly reach: number;
  readonly x: number;
  readonly y: number;
}

export const readNodeHitAreas = (page: Page): Promise<NodeHitArea[]> =>
  page.evaluate((hitPx) => {
    const graph = window.__STRATA_GRAPH__;
    if (graph === undefined) {
      throw new Error("window.__STRATA_GRAPH__ não está registrado");
    }
    const zoom = graph.zoom();
    const pixels = (id: string, property: string) =>
      Number.parseFloat(graph.style(id, property) ?? "0");
    return graph.nodeIds().flatMap((id) => {
      const position = graph.renderedPosition(id);
      if (position === null) {
        return [];
      }
      const border = 2 * pixels(id, "border-width");
      const diagonal = Math.hypot(
        pixels(id, "width") + border,
        pixels(id, "height") + border
      );
      return [{ id, reach: (diagonal * zoom) / 2 + hitPx, ...position }];
    });
  }, CYTOSCAPE_MOUSE_HIT_PX);

export interface EmptyPointSearch {
  readonly angles: number;

  readonly marginPx: number;
  readonly maxRadiusPx: number;
  readonly stepPx: number;
}

const distance = (a: Point, b: Point): number =>
  Math.hypot(a.x - b.x, a.y - b.y);

export const findEmptyPoint = (
  areas: readonly NodeHitArea[],
  centre: Point,
  search: EmptyPointSearch
): Point => {
  for (let radius = 0; radius <= search.maxRadiusPx; radius += search.stepPx) {
    const angles = radius === 0 ? 1 : search.angles;
    for (let index = 0; index < angles; index += 1) {
      const angle = (2 * Math.PI * index) / angles;
      const point = {
        x: centre.x + radius * Math.cos(angle),
        y: centre.y + radius * Math.sin(angle),
      };
      if (
        areas.every(
          (area) => distance(area, point) > area.reach + search.marginPx
        )
      ) {
        return point;
      }
    }
  }
  throw new Error(
    `nenhum ponto vazio a até ${search.maxRadiusPx} px de (${centre.x}, ${centre.y})`
  );
};

export const drawSeeded = <T>(
  items: readonly T[],
  count: number,
  seed: number
): T[] => {
  if (items.length < count) {
    throw new RangeError(
      `não dá para sortear ${count} de ${items.length} itens`
    );
  }
  const random = mulberry32(seed);
  const chosen = new Set<number>();
  while (chosen.size < count) {
    chosen.add(Math.floor(random() * items.length));
  }
  return [...chosen].map((index) => items[index]);
};

export interface ClickableSearch {
  readonly clearancePx: number;

  readonly edgeMarginPx: number;
}

export const listClickableNodes = (
  areas: readonly NodeHitArea[],
  box: ContainerBox,
  search: ClickableSearch
): NodeHitArea[] =>
  areas.filter(
    (area) =>
      area.x >= box.x + search.edgeMarginPx &&
      area.x <= box.x + box.width - search.edgeMarginPx &&
      area.y >= box.y + search.edgeMarginPx &&
      area.y <= box.y + box.height - search.edgeMarginPx &&
      areas.every(
        (other) =>
          other === area ||
          distance(other, area) > other.reach + search.clearancePx
      )
  );

export const GESTURE_INPUT = {
  dragAngleStepRad: 1 / 15,
  dragRadiusPx: 150,

  pauseMs: 8,

  trackpadDeltasPx: [27, 33, 29, 31, 35, 25, 30, 28, 34, 26],

  wheelDirectionRun: 25,
} as const;

export interface RecordedGesture {
  readonly inputEvents: number;
  readonly stamps: number[];
}

const repeatFor = async (
  durationMs: number,
  step: (index: number) => Promise<void>
): Promise<number> => {
  const startedAt = performance.now();
  let inputs = 0;
  while (performance.now() - startedAt < durationMs) {
    // biome-ignore lint/performance/noAwaitInLoops: os eventos do gesto precisam sair em ordem
    await step(inputs);
    inputs += 1;
  }
  return inputs;
};

export const dragPan = async (
  page: Page,
  start: Point,
  durationMs: number
): Promise<RecordedGesture> => {
  const { dragAngleStepRad, dragRadiusPx, pauseMs } = GESTURE_INPUT;
  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  let inputEvents = 0;
  try {
    const stamps = await recordGesture(page, async () => {
      inputEvents = await repeatFor(durationMs, async (index) => {
        const angle = index * dragAngleStepRad;
        await page.mouse.move(
          start.x + Math.cos(angle) * dragRadiusPx - dragRadiusPx,
          start.y + Math.sin(angle) * dragRadiusPx
        );
        await delay(pauseMs);
      });
    });
    return { inputEvents, stamps };
  } finally {
    await page.mouse.up();
  }
};

export const trackpadWheel = async (
  page: Page,
  at: Point,
  durationMs: number
): Promise<RecordedGesture> => {
  const { pauseMs, trackpadDeltasPx, wheelDirectionRun } = GESTURE_INPUT;
  await page.mouse.move(at.x, at.y);
  let inputEvents = 0;
  const stamps = await recordGesture(page, async () => {
    inputEvents = await repeatFor(durationMs, async (index) => {
      const direction =
        Math.floor(index / wheelDirectionRun) % 2 === 0 ? -1 : 1;
      await page.mouse.wheel(
        0,
        direction * trackpadDeltasPx[index % trackpadDeltasPx.length]
      );
      await delay(pauseMs);
    });
  });
  return { inputEvents, stamps };
};

type ClickProbeWindow = Window & { __STRATA_CLICK__?: Promise<number> };

export const armSelectionProbe = (
  page: Page,
  elementId: string,
  width: string,
  timeoutMs: number
): Promise<void> =>
  page.evaluate(
    (probe) => {
      const host = window as ClickProbeWindow;
      host.__STRATA_CLICK__ = new Promise<number>((resolve, reject) => {
        const timer = setTimeout(() => {
          reject(
            new Error(
              `${probe.elementId} não chegou a border-width ${probe.width} em ${probe.timeoutMs} ms`
            )
          );
        }, probe.timeoutMs);
        window.addEventListener(
          "pointerdown",
          (event) => {
            const downAt = event.timeStamp;
            const check = (frameAt: number) => {
              const current = window.__STRATA_GRAPH__?.style(
                probe.elementId,
                "border-width"
              );
              if (current === probe.width) {
                clearTimeout(timer);
                resolve(frameAt - downAt);
                return;
              }
              requestAnimationFrame(check);
            };
            requestAnimationFrame(check);
          },
          { capture: true, once: true }
        );
      });
    },
    { elementId, timeoutMs, width }
  );

export const readSelectionLatency = (page: Page): Promise<number> =>
  page.evaluate(() => {
    const pending = (window as ClickProbeWindow).__STRATA_CLICK__;
    if (pending === undefined) {
      throw new Error("nenhuma sonda de clique está armada nesta página");
    }
    return pending;
  });
