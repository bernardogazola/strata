import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import type { Browser, FullProject, Page } from "@playwright/test";
import { GRAPH_SLICE_BUDGETS } from "@/protocol/graph";

export const BENCHMARK_OUT_ENV = "STRATA_BENCHMARK_OUT";

export const TYPE_NODES_ENV = "STRATA_BENCHMARK_TYPE_NODES";

const TYPE_EDGES_PER_NODE = 5;
const POSITIVE_INTEGER = /^[1-9]\d*$/;

export interface BenchmarkTypeBudget {
  readonly edges: number;
  readonly nodes: number;

  readonly source: string;
}

export const benchmarkTypeBudget = (
  environment: NodeJS.ProcessEnv = process.env
): BenchmarkTypeBudget => {
  const configured = environment[TYPE_NODES_ENV];
  if (configured === undefined || configured === "") {
    return { ...GRAPH_SLICE_BUDGETS.TYPE, source: "GRAPH_SLICE_BUDGETS.TYPE" };
  }
  if (!POSITIVE_INTEGER.test(configured)) {
    throw new RangeError(
      `${TYPE_NODES_ENV} precisa ser um inteiro positivo, e não "${configured}"`
    );
  }
  const nodes = Number(configured);
  return {
    edges: nodes * TYPE_EDGES_PER_NODE,
    nodes,
    source: `${TYPE_NODES_ENV}=${nodes}`,
  };
};

export const GATING_PROJECT = "chromium";

const BENCHMARK_SCHEMA = 1;
const MILLISECONDS_SUFFIX = /\.\d{3}Z$/;
const MS_PER_SECOND = 1000;

export interface BrowserEnvironment {
  readonly channel: string | null;

  readonly executablePath: string | null;
  readonly name: string;

  readonly project: string;

  readonly version: string;

  readonly webglRenderer: string | null;
}

export interface BenchmarkEnvironment {
  readonly arch: string;

  readonly browser: BrowserEnvironment;
  readonly cpu: string;
  readonly devicePixelRatio: number;

  readonly displayRefreshHz: number;

  readonly loadAverage: readonly number[];
  readonly logicalCores: number;
  readonly os: string;
  readonly ramBytes: number;
  readonly viewport: { readonly height: number; readonly width: number } | null;
}

export interface CaptureEnvironmentOptions {
  readonly baselineMs: number;
  readonly browser: Browser;

  readonly page: Page;

  readonly project: FullProject;
}

export interface BenchmarkVerdict {
  readonly outcome: "FAIL" | "PASS";
  readonly [detail: string]: unknown;
}

export interface BenchmarkRecordInput<Results> {
  readonly environment: BenchmarkEnvironment;
  readonly generator: Readonly<Record<string, unknown>>;
  readonly measure: string;
  readonly results: Results;
  readonly rule: Readonly<Record<string, unknown>>;
  readonly verdict: BenchmarkVerdict;
}

export interface BenchmarkRecord<Results>
  extends BenchmarkRecordInput<Results> {
  readonly baseCommit: string;

  readonly measuredAt: string;

  readonly schema: typeof BENCHMARK_SCHEMA;
}

const RECORD_KEY_ORDER = [
  "schema",
  "measuredAt",
  "baseCommit",
  "measure",
  "rule",
  "verdict",
  "environment",
  "generator",
  "results",
] as const satisfies readonly (keyof BenchmarkRecord<unknown>)[];

const describeOs = (): string => {
  if (process.platform !== "darwin") {
    return `${os.type()} ${os.release()}`;
  }
  const version = execFileSync("sw_vers", ["-productVersion"], {
    encoding: "utf8",
  }).trim();
  return `macOS ${version}`;
};

const readChromiumExecutable = async (
  browser: Browser
): Promise<string | null> => {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.goto("chrome://version");
    return (
      (await page.locator("#executable_path").textContent())?.trim() ?? null
    );
  } catch {
    return null;
  } finally {
    await context.close();
  }
};

export const readWebglRenderer = (page: Page): Promise<string | null> =>
  page.evaluate(() => {
    const gl = document.createElement("canvas").getContext("webgl");
    if (gl === null) {
      return null;
    }
    const debug = gl.getExtension("WEBGL_debug_renderer_info");
    return String(
      gl.getParameter(
        debug === null ? gl.RENDERER : debug.UNMASKED_RENDERER_WEBGL
      )
    );
  });

const redactHome = (executablePath: string | null): string | null => {
  const home = os.homedir();
  return executablePath?.startsWith(home)
    ? `~${executablePath.slice(home.length)}`
    : executablePath;
};

export const captureEnvironment = async ({
  baselineMs,
  browser,
  page,
  project,
}: CaptureEnvironmentOptions): Promise<BenchmarkEnvironment> => {
  const name = browser.browserType().name();
  const [executablePath, webglRenderer, devicePixelRatio] = await Promise.all([
    name === "chromium"
      ? readChromiumExecutable(browser)
      : Promise.resolve(browser.browserType().executablePath()),
    readWebglRenderer(page),
    page.evaluate(() => window.devicePixelRatio),
  ]);
  return {
    arch: os.arch(),
    browser: {
      channel: project.use.channel ?? null,
      executablePath: redactHome(executablePath),
      name,
      project: project.name,
      version: browser.version(),
      webglRenderer,
    },
    cpu: os.cpus()[0]?.model ?? "unknown",
    devicePixelRatio,
    displayRefreshHz: MS_PER_SECOND / baselineMs,
    loadAverage: os.loadavg(),
    logicalCores: os.availableParallelism(),
    os: describeOs(),
    ramBytes: os.totalmem(),
    viewport: page.viewportSize(),
  };
};

export const createBenchmarkRecord = <Results>(
  input: BenchmarkRecordInput<Results>,
  measuredAt: Date = new Date()
): BenchmarkRecord<Results> => ({
  ...input,
  baseCommit: execFileSync("git", ["rev-parse", "--short", "HEAD"], {
    encoding: "utf8",
  }).trim(),
  measuredAt: measuredAt.toISOString().replace(MILLISECONDS_SUFFIX, "Z"),
  schema: BENCHMARK_SCHEMA,
});

export const benchmarkOutputPath = (
  projectName: string,
  environment: NodeJS.ProcessEnv = process.env
): string | null => {
  const configured = environment[BENCHMARK_OUT_ENV];
  if (configured === undefined || configured === "") {
    return null;
  }
  if (projectName === GATING_PROJECT) {
    return configured;
  }
  const { dir, ext, name } = path.parse(configured);
  return path.join(dir, `${name}.${projectName}${ext}`);
};

export const writeBenchmarkRecord = <Results>(
  record: BenchmarkRecord<Results>,
  projectName: string
): string | null => {
  const target = benchmarkOutputPath(projectName);
  if (target === null) {
    return null;
  }
  const ordered = Object.fromEntries(
    RECORD_KEY_ORDER.map((key) => [key, record[key]])
  );
  mkdirSync(path.dirname(target), { recursive: true });
  writeFileSync(target, `${JSON.stringify(ordered, null, 2)}\n`);
  return target;
};
