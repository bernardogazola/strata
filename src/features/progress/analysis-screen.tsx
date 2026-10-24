import { useMutation, useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import {
  type ComponentType,
  type ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { cancelAnalysis, protocolErrorOf } from "@/bridge/analyzer";
import {
  PRIMARY_ACTION_CLASS,
  SECONDARY_ACTION_CLASS,
} from "@/components/action-styles";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorSurface } from "@/features/errors/error-surface";
import { filesInRoots } from "@/features/repository/count-phrase";
import {
  AnalysisChip,
  type AnalysisChipState,
  AppHeader,
  type AppHeaderProps,
  type AppHeaderRepository,
} from "@/features/shell/app-header";
import { useFocusTitleOnScreenChange } from "@/features/shell/focus-title";
import { leaveAnalysis } from "@/features/shell/repository-search";
import { useCloseRepository } from "@/features/shell/use-close-repository";
import { formatDuration, interpolate } from "@/i18n/format";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import { analysisStageSchema, isAnalysisPartial } from "@/protocol/analysis";
import type { AnalysisStatusReport } from "@/protocol/repository";
import {
  analysisStatusQueryOptions,
  repositoryDescribeQueryOptions,
} from "@/queries/options";
import { selectAnalyzerUsable, useAppStore } from "@/state/app-store";
import { AnalysisSummary } from "./analysis-summary";
import { StageProgress } from "./stage-progress";
import type { StageTicks } from "./stage-rows";

const routeApi = getRouteApi("/repository/$handle");

const ELAPSED_REFRESH_MS = 1000;

const NO_TICKS: StageTicks = {};

export type AnalysisHeader = Omit<AppHeaderProps, "trail">;

export interface AnalysisWorkspaceProps {
  readonly header: AnalysisHeader;
  readonly report: AnalysisStatusReport;
}

export interface AnalysisScreenProps {
  readonly analysisId: string;
  readonly arrivedByNavigation: boolean;
  readonly handle: string;
  readonly workspace: ComponentType<AnalysisWorkspaceProps>;
}

const useNow = (): number => {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), ELAPSED_REFRESH_MS);
    return () => clearInterval(interval);
  }, []);

  return now;
};

const isMapReady = (report: AnalysisStatusReport): boolean =>
  (report.status === "RUNNING" || report.status === "COMPLETED") &&
  report.stages.some(
    (stage) =>
      stage.stage === "STRUCTURAL_GRAPH" && stage.status === "COMPLETED"
  );

const chipStateOf = (
  report: AnalysisStatusReport | undefined
): AnalysisChipState | null => {
  if (report === undefined) {
    return null;
  }
  switch (report.status) {
    case "RUNNING":
      return "RUNNING";
    case "COMPLETED":
      return isAnalysisPartial(report.completeness) ? "PARTIAL" : null;
    case "CANCELLED":
      return "CANCELLED";
    case "FAILED":
      return "FAILED";
    default:
      return report.status satisfies never;
  }
};

const runningTitle = (
  report: AnalysisStatusReport,
  ticks: StageTicks
): string => {
  const { totalFiles } = report.completeness;
  const files =
    totalFiles > 0 ? totalFiles : (ticks.SOURCE_ANALYSIS?.total ?? null);
  const roots =
    report.stages.find((stage) => stage.stage === "LAYOUT_DISCOVERY")?.counters
      .selectedRoots ?? null;
  if (files === null || roots === null) {
    return t.header.analysis.RUNNING;
  }
  return filesInRoots(t.analysis.running.title, files, roots);
};

interface RunningAnalysisProps {
  readonly analysisId: string;

  readonly interrupted: boolean;
  readonly report: AnalysisStatusReport;
  readonly ticks: StageTicks;
}

function RunningAnalysis({
  analysisId,
  interrupted,
  report,
  ticks,
}: RunningAnalysisProps) {
  const now = useNow();
  const {
    error: cancelError,
    isPending,
    isSuccess,
    mutate,
  } = useMutation({ mutationFn: () => cancelAnalysis(analysisId) });
  const cancelling = isPending || isSuccess;
  const onCancel = useCallback(() => mutate(), [mutate]);
  const elapsed = formatDuration(
    Math.max(0, now - Date.parse(report.startedAt))
  );

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
        <div className="flex min-w-0 flex-col gap-1.5">
          <h1 className="font-medium text-foreground text-xl" tabIndex={-1}>
            {runningTitle(report, ticks)}
          </h1>
          <p className="text-muted-foreground text-xs tabular-nums">
            {interrupted
              ? t.analysis.running.interrupted
              : interpolate(t.analysis.running.meta, { elapsed })}
          </p>
        </div>
        {interrupted ? null : (
          <Button
            className={SECONDARY_ACTION_CLASS}
            disabled={cancelling}
            focusableWhenDisabled
            onClick={onCancel}
            size="sm"
            variant="outline"
          >
            {cancelling ? t.analysis.cancelling : t.analysis.cancel}
          </Button>
        )}
      </div>
      {cancelError === null ? null : (
        <ErrorSurface error={protocolErrorOf(cancelError)} variant="inline" />
      )}
      <StageProgress interrupted={interrupted} report={report} ticks={ticks} />
    </div>
  );
}

function AnalysisSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <Skeleton className="h-6 w-80 motion-reduce:animate-none" />
        <Skeleton className="h-4 w-36 motion-reduce:animate-none" />
      </div>
      <div className="flex flex-col divide-y divide-border rounded-lg border border-card-border bg-card py-1 shadow-card">
        {analysisStageSchema.options.map((stage) => (
          <div className="flex h-10 items-center gap-3 px-4" key={stage}>
            <Skeleton className="size-4 shrink-0 rounded-full motion-reduce:animate-none" />
            <Skeleton className="h-3.5 w-48 motion-reduce:animate-none" />
          </div>
        ))}
      </div>
    </div>
  );
}

type FrameLayout = "column" | "page";

const FRAME_CLASSES = {
  column: {
    inner: "flex w-full min-w-0 max-w-[640px] flex-col",
    main: "flex grow justify-center px-6 pt-12 pb-8 lg:px-12",
  },
  page: {
    inner: "flex w-full max-w-[520px] flex-col gap-6",
    main: "flex grow items-center justify-center px-4 pt-8 pb-12",
  },
} as const satisfies Record<
  FrameLayout,
  { readonly inner: string; readonly main: string }
>;

interface AnalysisFrameProps {
  readonly busy: boolean;
  readonly children: ReactNode;
  readonly header: AnalysisHeader;
  readonly layout: FrameLayout;
}

function AnalysisFrame({ busy, children, header, layout }: AnalysisFrameProps) {
  const classes = FRAME_CLASSES[layout];
  return (
    <div className="flex min-h-svh flex-col bg-background">
      <AppHeader {...header} />
      <main
        aria-busy={busy ? true : undefined}
        aria-label={t.shell.regions.analysis}
        className={classes.main}
      >
        <div className={classes.inner}>{children}</div>
      </main>
    </div>
  );
}

interface AnalysisGoneProps {
  readonly onAnalyzeAgain: () => void;
}

function AnalysisGone({ onAnalyzeAgain }: AnalysisGoneProps) {
  return (
    <div className="flex select-text flex-col gap-5">
      <div className="flex flex-col gap-2">
        <h1 className="font-medium text-foreground text-xl" tabIndex={-1}>
          {t.analysis.notFound.title}
        </h1>
        <p className="text-muted-foreground text-sm/5">
          {t.analysis.notFound.body}
        </p>
      </div>
      <div>
        <Button
          className={cn(PRIMARY_ACTION_CLASS, "px-3.5")}
          onClick={onAnalyzeAgain}
        >
          {t.analysis.again}
        </Button>
      </div>
    </div>
  );
}

type AnalysisView =
  | { readonly kind: "failure"; readonly error: unknown }
  | { readonly kind: "gone" }
  | { readonly kind: "pending" }
  | { readonly kind: "running"; readonly report: AnalysisStatusReport }
  | { readonly kind: "summary"; readonly report: AnalysisStatusReport }
  | { readonly kind: "workspace"; readonly report: AnalysisStatusReport };

const viewOf = (
  report: AnalysisStatusReport | undefined,
  error: unknown
): AnalysisView => {
  if (error !== null && protocolErrorOf(error).code === "ANALYSIS_NOT_FOUND") {
    return { kind: "gone" };
  }
  if (report === undefined) {
    return error === null ? { kind: "pending" } : { error, kind: "failure" };
  }
  if (isMapReady(report)) {
    return { kind: "workspace", report };
  }
  return report.status === "RUNNING"
    ? { kind: "running", report }
    : { kind: "summary", report };
};

const useEndOfRunAnnouncement = (viewKind: AnalysisView["kind"]): string => {
  const [announcement, setAnnouncement] = useState("");
  const shownKind = useRef(viewKind);

  useEffect(() => {
    const wasRunning = shownKind.current === "running";
    shownKind.current = viewKind;
    if (wasRunning && (viewKind === "summary" || viewKind === "workspace")) {
      setAnnouncement(document.querySelector("main h1")?.textContent ?? "");
    }
  }, [viewKind]);

  return announcement;
};

const reportOf = (view: AnalysisView): AnalysisStatusReport | undefined =>
  "report" in view ? view.report : undefined;

export function AnalysisScreen({
  analysisId,
  arrivedByNavigation,
  handle,
  workspace: Workspace,
}: AnalysisScreenProps) {
  const navigate = routeApi.useNavigate();
  const backendReady = useAppStore(selectAnalyzerUsable);
  const status = useQuery({
    ...analysisStatusQueryOptions(analysisId),
    enabled: backendReady,
  });
  const describe = useQuery({
    ...repositoryDescribeQueryOptions(handle),
    enabled: backendReady,
  });
  const progress = useAppStore((state) => state.progress);
  const runningAnalysisId = useAppStore((state) => state.runningAnalysisId);
  const warnings = useAppStore((state) => state.warnings);
  const view = viewOf(status.data, status.error);
  const onClose = useCloseRepository(handle, {
    cancelAnalysisId:
      reportOf(view)?.status === "RUNNING" ? analysisId : undefined,
  });

  const onAnalyzeAgain = useCallback(() => {
    navigate({ search: leaveAnalysis });
  }, [navigate]);
  const { refetch } = status;
  const onRetry = useCallback(() => {
    refetch().catch(() => undefined);
  }, [refetch]);

  useFocusTitleOnScreenChange(
    view.kind === "pending" ? null : view.kind,
    arrivedByNavigation
  );

  const announcement = useEndOfRunAnnouncement(view.kind);

  const interrupted = view.kind === "running" && !backendReady;
  const chip =
    view.kind === "gone" || interrupted ? null : chipStateOf(status.data);
  const repository: AppHeaderRepository = {
    displayName: describe.data?.displayName,
    onClose,
  };
  const header: AnalysisHeader = {
    repository,
    status: chip === null ? undefined : <AnalysisChip state={chip} />,
  };

  const announcer = (
    <p aria-live="polite" className="sr-only">
      {announcement}
    </p>
  );

  if (view.kind === "workspace") {
    const terminal = view.report.status !== "RUNNING";
    const workspaceHeader: AnalysisHeader = {
      ...header,
      repository: terminal ? { ...repository, onAnalyzeAgain } : repository,
    };
    return (
      <>
        <Workspace header={workspaceHeader} report={view.report} />
        {announcer}
      </>
    );
  }

  let content: ReactNode;
  switch (view.kind) {
    case "pending":
      content = <AnalysisSkeleton />;
      break;
    case "gone":
      content = <AnalysisGone onAnalyzeAgain={onAnalyzeAgain} />;
      break;
    case "failure":
      content = (
        <ErrorSurface
          actions={
            <Button
              className={SECONDARY_ACTION_CLASS}
              onClick={onAnalyzeAgain}
              size="sm"
              variant="outline"
            >
              {t.analysis.again}
            </Button>
          }
          error={protocolErrorOf(view.error)}
          onRetry={onRetry}
          variant="page"
        />
      );
      break;
    case "running":
      content = (
        <RunningAnalysis
          analysisId={analysisId}
          interrupted={interrupted}
          report={view.report}
          ticks={
            runningAnalysisId === null || runningAnalysisId === analysisId
              ? progress
              : NO_TICKS
          }
        />
      );
      break;
    case "summary":
      content = (
        <AnalysisSummary
          onAnalyzeAgain={onAnalyzeAgain}
          report={view.report}
          warnings={warnings}
        />
      );
      break;
    default:
      return view satisfies never;
  }

  return (
    <>
      <AnalysisFrame
        busy={view.kind === "pending"}
        header={header}
        layout={
          view.kind === "gone" || view.kind === "failure" ? "page" : "column"
        }
      >
        {content}
      </AnalysisFrame>
      {announcer}
    </>
  );
}
