import cytoscape, {
  type CollectionReturnValue,
  type Core,
  type ElementsDefinition,
  type EventObjectNode,
} from "cytoscape";
import { useEffect, useEffectEvent, useRef } from "react";
import { onResolvedThemeApplied } from "@/lib/theme";
import type { ElementKind } from "@/protocol/enums";
import type { GraphSlice } from "@/protocol/graph";
import { useAppStore } from "@/state/app-store";
import { concentricLayout, FIT_PADDING_PX, MAX_FIT_ZOOM } from "./layout";
import { readGraphPalette } from "./palette";
import { GRAPH_PERF } from "./perf";
import {
  ACTIVE_CLASS,
  buildStylesheet,
  graphRuntimeForSlice,
  SELECTED_CLASS,
  setGraphRuntime,
} from "./stylesheet";

export interface ArchitectureGraphProps {
  readonly elements: ElementsDefinition | null;
  readonly label: string;
  readonly onExpand: (elementId: string) => void;
  readonly onRenderError: (error: Error) => void;
  readonly onSelect: (elementId: string | null) => void;
  readonly selectedElementId: string | null;
  readonly slice: GraphSlice | null;
}

type GraphState = "idle" | "ready" | "rendering";

const CYTOSCAPE_STYLESHEET_ID = "__________cytoscape_stylesheet";

const PIXEL_RATIO = 1;
const WHEEL_SENSITIVITY = 0.2;
const EXPANDABLE_KIND: ElementKind = "PACKAGE";

const CONTAINER_STYLE = { padding: 0 } as const;

// O index.d.ts do cytoscape 3.34.1 não declara `renderer`; espalhada de uma constante, a
// chave escapa do TS2353 sem precisar de `as`.
const CANVAS_RENDERER = { renderer: { name: "canvas" } } as const;

const ensureCytoscapeStylesheetPlaceholder = (): void => {
  if (document.getElementById(CYTOSCAPE_STYLESHEET_ID) !== null) {
    return;
  }
  const placeholder = document.createElement("meta");
  placeholder.id = CYTOSCAPE_STYLESHEET_ID;
  placeholder.name = "cytoscape-stylesheet";
  placeholder.content = "global";
  document.head.append(placeholder);
};

const inBatch = <T,>(cy: Core, mutate: () => T): T => {
  cy.startBatch();
  try {
    return mutate();
  } finally {
    cy.endBatch();
  }
};

const afterTwoFrames = (callback: () => void): void => {
  requestAnimationFrame(() => {
    requestAnimationFrame(callback);
  });
};

const hasSize = (container: HTMLElement): boolean =>
  container.clientWidth > 0 && container.clientHeight > 0;

const settleViewport = (cy: Core): void => {
  if (cy.zoom() > MAX_FIT_ZOOM) {
    cy.zoom(MAX_FIT_ZOOM);
    cy.center();
  }
  setGraphRuntime(cy, { dashScale: 1 / cy.zoom() });
};

const markRenderReady = (): void => {
  if (!import.meta.env.DEV) {
    return;
  }
  const previousReady = performance
    .getEntriesByName(GRAPH_PERF.ready, "mark")
    .at(-1);
  const ready = performance.mark(GRAPH_PERF.ready);
  const request = performance
    .getEntriesByName(GRAPH_PERF.request, "mark")
    .at(-1);
  if (
    request !== undefined &&
    request.startTime > (previousReady?.startTime ?? Number.NEGATIVE_INFINITY)
  ) {
    performance.measure(GRAPH_PERF.render, {
      end: ready.startTime,
      start: request.startTime,
    });
  }
};

const createGraphHandle = (
  cy: Core,
  container: HTMLElement
): StrataGraphHandle => ({
  edgeIds: () =>
    cy
      .edges()
      .map((edge) => edge.id())
      .sort(),
  fit: () => {
    cy.fit(undefined, FIT_PADDING_PX);
    settleViewport(cy);
  },
  nodeIds: () =>
    cy
      .nodes()
      .map((node) => node.id())
      .sort(),
  renderedPosition: (id) => {
    const element = cy.getElementById(id);
    if (element.empty()) {
      return null;
    }
    const position =
      element.group() === "nodes"
        ? element.renderedPosition()
        : element.renderedMidpoint();
    const box = container.getBoundingClientRect();
    return { x: box.left + position.x, y: box.top + position.y };
  },
  setZoom: (level) => {
    cy.zoom({
      level,
      renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 },
    });
  },
  style: (id, property) => {
    const element = cy.getElementById(id);
    return element.empty() ? null : String(element.style(property));
  },
  zoom: () => cy.zoom(),
});

const exposeGraphHandle = (cy: Core, container: HTMLElement): (() => void) => {
  if (!import.meta.env.DEV) {
    return () => undefined;
  }
  const handle = createGraphHandle(cy, container);
  window.__STRATA_GRAPH__ = handle;
  return () => {
    if (window.__STRATA_GRAPH__ === handle) {
      Reflect.deleteProperty(window, "__STRATA_GRAPH__");
    }
  };
};

const observeSize = (
  container: HTMLElement,
  onResize: () => void
): (() => void) => {
  if (typeof ResizeObserver === "undefined") {
    return () => undefined;
  }
  const observer = new ResizeObserver(onResize);
  observer.observe(container);
  return () => {
    observer.disconnect();
  };
};

interface GraphCallbacks {
  readonly onExpand: (elementId: string) => void;
  readonly onRenderError: (error: Error) => void;
  readonly onSelect: (elementId: string | null) => void;
}

const bindIntent = (cy: Core, callbacks: GraphCallbacks): void => {
  cy.on("tap", "node", (event: EventObjectNode) => {
    callbacks.onSelect(event.target.id());
  });
  cy.on("tap", (event) => {
    if (event.target === cy) {
      callbacks.onSelect(null);
    }
  });
  cy.on(
    "dbltap",
    `node[kind = "${EXPANDABLE_KIND}"]`,
    (event: EventObjectNode) => {
      callbacks.onExpand(event.target.id());
    }
  );
  const { setActiveElementId } = useAppStore.getState();
  cy.on("mouseover", "node", (event: EventObjectNode) => {
    setActiveElementId(event.target.id());
  });
  cy.on("mouseout", "node", () => {
    setActiveElementId(null);
  });
};

class GraphCanvas {
  readonly #callbacks: GraphCallbacks;
  readonly #container: HTMLElement;
  readonly #cy: Core;
  readonly #teardown: readonly (() => void)[];
  readonly #wrapper: HTMLElement;
  #activeId: string | null = null;
  #generation = 0;
  #pendingLayout: GraphSlice | null = null;
  #selectedId: string | null = null;

  constructor(
    wrapper: HTMLElement,
    container: HTMLElement,
    callbacks: GraphCallbacks
  ) {
    this.#wrapper = wrapper;
    this.#container = container;
    this.#callbacks = callbacks;
    ensureCytoscapeStylesheetPlaceholder();
    this.#cy = cytoscape({
      container,
      ...CANVAS_RENDERER,
      autoungrabify: true,
      autounselectify: true,
      boxSelectionEnabled: false,
      layout: { name: "preset" },
      pixelRatio: PIXEL_RATIO,
      style: buildStylesheet(readGraphPalette()),
      wheelSensitivity: WHEEL_SENSITIVITY,
    });
    bindIntent(this.#cy, callbacks);
    this.#writeState("idle");
    this.#highlight(useAppStore.getState().activeElementId);
    this.#teardown = [
      useAppStore.subscribe((state, previous) => {
        if (state.activeElementId !== previous.activeElementId) {
          this.#highlight(state.activeElementId);
        }
      }),
      onResolvedThemeApplied(() => {
        this.#restyle();
      }),
      observeSize(container, () => {
        this.#runPendingLayout();
      }),
      exposeGraphHandle(this.#cy, container),
    ];
  }

  destroy(): void {
    for (const stop of this.#teardown) {
      stop();
    }
    this.#cy.destroy();
  }

  render(elements: ElementsDefinition | null, slice: GraphSlice | null): void {
    this.#generation += 1;
    this.#pendingLayout = null;
    if (elements === null || slice === null) {
      this.#clear();
      return;
    }
    this.#writeState("rendering");
    const failure = this.#replaceElements(elements);
    if (failure !== null) {
      this.#callbacks.onRenderError(failure);
      return;
    }
    setGraphRuntime(this.#cy, graphRuntimeForSlice(slice));
    if (hasSize(this.#container)) {
      this.#runLayout(slice);
    } else {
      this.#pendingLayout = slice;
    }
  }

  select(elementId: string | null): void {
    inBatch(this.#cy, () => {
      this.#elementOf(this.#selectedId).removeClass(SELECTED_CLASS);
      this.#selectedId = elementId;
      this.#elementOf(this.#selectedId).addClass(SELECTED_CLASS);
    });
  }

  #highlight(elementId: string | null): void {
    inBatch(this.#cy, () => {
      this.#withEdges(this.#activeId).removeClass(ACTIVE_CLASS);
      this.#activeId = elementId;
      this.#withEdges(this.#activeId).addClass(ACTIVE_CLASS);
    });
  }

  #restyle(): void {
    try {
      this.#cy.style(buildStylesheet(readGraphPalette()));
    } catch (error) {
      this.#callbacks.onRenderError(
        new Error("o tema resolvido não conseguiu reestilizar o canvas", {
          cause: error,
        })
      );
    }
  }

  #elementOf(id: string | null): CollectionReturnValue {
    return id === null ? this.#cy.collection() : this.#cy.getElementById(id);
  }

  #withEdges(id: string | null): CollectionReturnValue {
    const element = this.#elementOf(id);
    return element.union(element.connectedEdges());
  }

  #clear(): void {
    inBatch(this.#cy, () => this.#cy.elements().remove());
    this.#writeState("idle");
  }

  #replaceElements(elements: ElementsDefinition): Error | null {
    const expected = elements.nodes.length + elements.edges.length;
    try {
      const added = inBatch(this.#cy, () => {
        this.#cy.elements().remove();
        const kept = this.#cy.add(elements);
        this.#elementOf(this.#selectedId).addClass(SELECTED_CLASS);
        this.#withEdges(this.#activeId).addClass(ACTIVE_CLASS);
        return kept.length;
      });
      if (added === expected) {
        return null;
      }
      this.#clear();
      return new Error(`o cy.add manteve ${added} de ${expected} elementos`);
    } catch (error) {
      this.#clear();
      return new Error("o cy.add lançou um erro ao trocar a fatia", {
        cause: error,
      });
    }
  }

  #runPendingLayout(): void {
    const slice = this.#pendingLayout;
    if (slice === null || !hasSize(this.#container)) {
      return;
    }
    this.#pendingLayout = null;
    this.#runLayout(slice);
  }

  #runLayout(slice: GraphSlice): void {
    const cy = this.#cy;
    // O resize() do Cytoscape tem debounce de 100 ms; sem a chamada explícita, o layout leria
    // o tamanho antigo.
    cy.resize();
    const layout = cy.layout(concentricLayout(slice));
    layout.one(
      "layoutstop",
      this.#whenCurrent(() => {
        settleViewport(cy);
        afterTwoFrames(
          this.#whenCurrent(() => {
            this.#writeState("ready", {
              edges: cy.edges().length,
              nodes: cy.nodes().length,
            });
            markRenderReady();
          })
        );
      })
    );
    layout.run();
  }

  #whenCurrent(continuation: () => void): () => void {
    const generation = this.#generation;
    return () => {
      if (!this.#cy.destroyed() && this.#generation === generation) {
        continuation();
      }
    };
  }

  #writeState(
    state: GraphState,
    counts?: { readonly edges: number; readonly nodes: number }
  ): void {
    this.#wrapper.dataset.graphState = state;
    if (counts === undefined) {
      this.#wrapper.removeAttribute("data-node-count");
      this.#wrapper.removeAttribute("data-edge-count");
      return;
    }
    this.#wrapper.dataset.nodeCount = String(counts.nodes);
    this.#wrapper.dataset.edgeCount = String(counts.edges);
  }
}

export function ArchitectureGraph({
  elements,
  label,
  onExpand,
  onRenderError,
  onSelect,
  selectedElementId,
  slice,
}: ArchitectureGraphProps) {
  const wrapperRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<GraphCanvas | null>(null);

  const expandElement = useEffectEvent((elementId: string) => {
    onExpand(elementId);
  });
  const reportRenderError = useEffectEvent((error: Error) => {
    onRenderError(error);
  });
  const selectElement = useEffectEvent((elementId: string | null) => {
    onSelect(elementId);
  });

  useEffect(() => {
    const wrapper = wrapperRef.current;
    const container = containerRef.current;
    if (wrapper === null || container === null) {
      return;
    }
    const graph = new GraphCanvas(wrapper, container, {
      onExpand: expandElement,
      onRenderError: reportRenderError,
      onSelect: selectElement,
    });
    graphRef.current = graph;
    return () => {
      graph.destroy();
      graphRef.current = null;
    };
  }, []);

  useEffect(() => {
    graphRef.current?.render(elements, slice);
  }, [elements, slice]);

  useEffect(() => {
    graphRef.current?.select(selectedElementId);
  }, [selectedElementId]);

  return (
    <div className="relative h-full w-full" ref={wrapperRef}>
      <div
        aria-label={label}
        className="absolute inset-0"
        ref={containerRef}
        role="img"
        style={CONTAINER_STYLE}
      />
    </div>
  );
}
