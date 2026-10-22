import { useMutation, useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { type ReactNode, useCallback, useState } from "react";
import {
  closeRepository,
  openRepository,
  protocolErrorOf,
} from "@/bridge/analyzer";
import { PRIMARY_ACTION_CLASS } from "@/components/action-styles";
import { Button, buttonVariants } from "@/components/ui/button";
import { ErrorSurface } from "@/features/errors/error-surface";
import { AppHeader } from "@/features/shell/app-header";
import {
  GESTURE_STATE,
  useFocusTitleAfterGesture,
} from "@/features/shell/focus-title";
import { startAnalysis } from "@/features/shell/repository-search";
import { useCloseRepository } from "@/features/shell/use-close-repository";
import { t } from "@/i18n/messages";
import { cn } from "@/lib/utils";
import type { ProtocolError } from "@/protocol/errors";
import type { AnalysisConfiguration } from "@/protocol/repository";
import {
  analysisStartMutationOptions,
  repositoryDescribeQueryOptions,
} from "@/queries/options";
import {
  selectAnalyzerUsable,
  selectIsRunInFlight,
  useAppStore,
} from "@/state/app-store";
import {
  AnalysisConfigForm,
  AnalysisConfigFormSkeleton,
} from "./analysis-config-form";
import { DescribePanel, DescribePanelSkeleton } from "./describe-panel";

const routeApi = getRouteApi("/repository/$handle");

interface PrepareLayoutProps {
  readonly facts: ReactNode;
  readonly form: ReactNode;
}

function PrepareLayout({ facts, form }: PrepareLayoutProps) {
  return (
    <main
      aria-label={t.shell.regions.prepare}
      className="flex grow justify-center px-6 pt-12 lg:px-12"
    >
      <div className="flex w-full max-w-[1004px] flex-col items-center gap-12 lg:flex-row lg:items-start lg:gap-16">
        <div className="w-full max-w-[600px] lg:w-[340px] lg:shrink-0">
          {facts}
        </div>
        <div className="w-full min-w-0 max-w-[600px] lg:flex-1">{form}</div>
      </div>
    </main>
  );
}

export interface PrepareScreenProps {
  readonly handle: string;
}

export function PrepareScreen({ handle }: PrepareScreenProps) {
  const navigate = routeApi.useNavigate();
  const backendReady = useAppStore(selectAnalyzerUsable);
  const runInFlight = useAppStore(selectIsRunInFlight);
  const describe = useQuery({
    ...repositoryDescribeQueryOptions(handle),
    enabled: backendReady,
  });
  const {
    error: startError,
    isPending: isStarting,
    mutate: start,
  } = useMutation(analysisStartMutationOptions(handle));
  const [choosing, setChoosing] = useState(false);
  const [chooseFailure, setChooseFailure] = useState<ProtocolError | null>(
    null
  );

  const onClose = useCloseRepository(handle);
  useFocusTitleAfterGesture(describe.data !== undefined || describe.isError);

  const chooseAnother = useCallback(async () => {
    setChoosing(true);
    setChooseFailure(null);
    try {
      await closeRepository(handle).catch(() => undefined);
      const opened = await openRepository();
      await (opened === null
        ? navigate({ state: GESTURE_STATE, to: "/" })
        : navigate({
            params: { handle: opened.handle },
            state: GESTURE_STATE,
            to: "/repository/$handle",
          }));
    } catch (error) {
      setChooseFailure(protocolErrorOf(error));
    } finally {
      setChoosing(false);
    }
  }, [handle, navigate]);

  const onChooseAnother = useCallback(() => {
    chooseAnother().catch(() => undefined);
  }, [chooseAnother]);

  const { refetch } = describe;
  const onRetryDescribe = useCallback(() => {
    refetch().catch(() => undefined);
  }, [refetch]);

  const onSubmit = useCallback(
    (configuration: AnalysisConfiguration) => {
      start(configuration, {
        onSuccess: ({ analysisId }) => {
          navigate({ search: startAnalysis(analysisId) }).catch(
            () => undefined
          );
        },
      });
    },
    [navigate, start]
  );

  const header = (
    <AppHeader
      repository={{ displayName: describe.data?.displayName, onClose }}
    />
  );

  if (describe.isError) {
    return (
      <div className="flex min-h-svh flex-col bg-background">
        {header}
        <main
          aria-label={t.shell.regions.prepare}
          className="flex grow items-center justify-center px-4 pt-8 pb-12"
        >
          <div className="flex w-full max-w-[520px] flex-col gap-6">
            <ErrorSurface
              actions={
                <>
                  <Button
                    className={cn(PRIMARY_ACTION_CLASS, "px-3.5")}
                    disabled={choosing}
                    onClick={onChooseAnother}
                  >
                    {t.repository.chooseAnother}
                  </Button>
                  <Link
                    className={buttonVariants({
                      className: "h-9 px-3.5 text-muted-foreground",
                      variant: "ghost",
                    })}
                    to="/"
                  >
                    {t.repository.backToStart}
                  </Link>
                </>
              }
              error={protocolErrorOf(describe.error)}
              onRetry={onRetryDescribe}
              variant="page"
            />
            {chooseFailure === null ? null : (
              <ErrorSurface error={chooseFailure} variant="inline" />
            )}
          </div>
        </main>
      </div>
    );
  }

  const { data: description } = describe;
  return (
    <div className="flex min-h-svh flex-col bg-background">
      {header}
      {description === undefined ? (
        <PrepareLayout
          facts={<DescribePanelSkeleton />}
          form={<AnalysisConfigFormSkeleton />}
        />
      ) : (
        <PrepareLayout
          facts={<DescribePanel description={description} />}
          form={
            <AnalysisConfigForm
              backendReady={backendReady}
              description={description}
              key={description.sourceRoots.map((root) => root.id).join(" ")}
              notice={
                startError === null ? undefined : (
                  <ErrorSurface
                    error={protocolErrorOf(startError)}
                    variant="inline"
                  />
                )
              }
              onSubmit={onSubmit}
              pending={isStarting}
              runInFlight={runInFlight}
            />
          }
        />
      )}
    </div>
  );
}
