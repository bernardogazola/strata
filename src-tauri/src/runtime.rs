use std::sync::{Arc, Mutex};

use serde_json::value::RawValue;
use tokio::sync::RwLock;

use std::path::PathBuf;

use crate::analyzer::{
    AnalyzerConfig, AnalyzerEvent, AnalyzerHandle, BackendPhase, Diagnostics, EventSink,
    LaunchPlan, PROTOCOL_VERSION, StderrTail, process,
};
use crate::error::ProtocolError;
use crate::log::ShellLog;
use crate::repository::RepositoryRegistry;

pub enum AnalyzerSlot {
    Pending,
    Live(Arc<AnalyzerHandle>),
    Failed(ProtocolError),
}

pub struct RetiredAnalyzer {
    pub stderr: StderrTail,
    pub diagnostics: Diagnostics,
}

#[derive(Default)]
pub struct HandshakeCell {
    payload: Mutex<Option<Box<RawValue>>>,
}

impl HandshakeCell {
    pub fn remember(&self, payload: Box<RawValue>) {
        *self.lock() = Some(payload);
    }

    #[must_use]
    pub fn payload(&self) -> Option<Box<RawValue>> {
        self.lock().clone()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Option<Box<RawValue>>> {
        self.payload
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

pub enum PlanSource {
    Discovered {
        home: Option<PathBuf>,
        resource_dir: Option<PathBuf>,
        data_dir: PathBuf,
        max_heap: Option<String>,
    },
    Fixed(LaunchPlan),
}

impl PlanSource {
    fn plan(&self) -> Result<LaunchPlan, ProtocolError> {
        match self {
            Self::Fixed(plan) => Ok(plan.clone()),
            Self::Discovered {
                home,
                resource_dir,
                data_dir,
                max_heap,
            } => {
                std::fs::create_dir_all(data_dir).map_err(|error| {
                    ProtocolError::internal(format!("{}: {error}", data_dir.display()))
                })?;
                let layout = process::resolve(
                    home.as_deref(),
                    resource_dir.as_deref(),
                    &process::RealProbe,
                )?;
                Ok(LaunchPlan {
                    args: process::analyzer_args(&layout, data_dir, max_heap.as_deref()),
                    program: layout.java.clone(),
                    env: process::child_env(data_dir),
                    working_dir: data_dir.clone(),
                })
            }
        }
    }
}

pub struct AnalyzerRuntime {
    slot: RwLock<AnalyzerSlot>,
    previous: Mutex<Option<RetiredAnalyzer>>,
    plan: PlanSource,
    config: AnalyzerConfig,
    sink: Arc<dyn EventSink>,
    pub repositories: RepositoryRegistry,
    pub log: Arc<ShellLog>,
    pub handshake: Arc<HandshakeCell>,
}

impl AnalyzerRuntime {
    pub fn new(
        plan: PlanSource,
        config: AnalyzerConfig,
        sink: Arc<dyn EventSink>,
        log: Arc<ShellLog>,
        handshake: Arc<HandshakeCell>,
    ) -> Self {
        Self {
            slot: RwLock::new(AnalyzerSlot::Pending),
            previous: Mutex::new(None),
            plan,
            config,
            sink,
            repositories: RepositoryRegistry::default(),
            log,
            handshake,
        }
    }

    pub async fn boot(&self) {
        let mut slot = self.slot.write().await;
        self.install(&mut slot).await;
    }

    async fn install(&self, slot: &mut AnalyzerSlot) {
        let plan = match self.plan.plan() {
            Ok(plan) => plan,
            Err(error) => {
                self.log.warn(format!(
                    "analyzer: não foi possível resolver o runtime: {error}"
                ));
                self.log.fault(&error);
                *slot = AnalyzerSlot::Failed(error);
                self.sink.emit(AnalyzerEvent::State(BackendPhase::Crashed {
                    exit_code: None,
                }));
                return;
            }
        };
        self.log.info("analyzer: iniciando o processo");
        match AnalyzerHandle::spawn(plan, self.config.clone(), Arc::clone(&self.sink)).await {
            Ok(handle) => {
                self.log.info("analyzer: handshake concluído");
                *slot = AnalyzerSlot::Live(Arc::new(handle));
            }
            Err(error) => {
                self.log
                    .warn(format!("analyzer: falha ao iniciar o processo: {error}"));
                self.log.fault(&error);
                *slot = AnalyzerSlot::Failed(error);
                self.sink.emit(AnalyzerEvent::State(BackendPhase::Crashed {
                    exit_code: None,
                }));
            }
        }
    }

    pub async fn analyzer(&self) -> Result<Arc<AnalyzerHandle>, ProtocolError> {
        match &*self.slot.read().await {
            AnalyzerSlot::Live(handle) => Ok(Arc::clone(handle)),
            AnalyzerSlot::Pending => Err(ProtocolError::AnalyzerNotReady),
            AnalyzerSlot::Failed(error) => Err(error.clone()),
        }
    }

    pub async fn phase(&self) -> BackendPhase {
        match &*self.slot.read().await {
            AnalyzerSlot::Live(handle) => handle.backend_phase(),
            AnalyzerSlot::Pending => BackendPhase::Starting,
            AnalyzerSlot::Failed(_) => BackendPhase::Crashed { exit_code: None },
        }
    }

    pub async fn restart(&self) -> Result<BackendPhase, ProtocolError> {
        let mut slot = self.slot.write().await;

        if let AnalyzerSlot::Live(handle) = &*slot {
            if handle.is_stopping() {
                return Err(ProtocolError::ShuttingDown);
            }
            match handle.backend_phase() {
                BackendPhase::Starting => return Err(ProtocolError::AnalyzerNotReady),
                phase @ BackendPhase::Ready { .. } => return Ok(phase),
                BackendPhase::Incompatible { expected, received } => {
                    return Err(ProtocolError::VersionMismatch { expected, received });
                }
                BackendPhase::Crashed { .. } => {}
            }
            self.retire(handle);
        }

        self.log.info("analyzer: reinício solicitado");
        self.install(&mut slot).await;
        Ok(match &*slot {
            AnalyzerSlot::Live(handle) => handle.backend_phase(),
            AnalyzerSlot::Pending => BackendPhase::Starting,
            AnalyzerSlot::Failed(_) => BackendPhase::Crashed { exit_code: None },
        })
    }

    fn retire(&self, handle: &Arc<AnalyzerHandle>) {
        *self.previous_lock() = Some(RetiredAnalyzer {
            stderr: handle.stderr_tail(),
            diagnostics: handle.diagnostics(),
        });
    }

    #[must_use]
    pub fn retired_stderr(&self) -> Option<StderrTail> {
        self.previous_lock()
            .as_ref()
            .map(|retired| retired.stderr.clone())
    }

    #[must_use]
    pub fn retired_diagnostics(&self) -> Option<Diagnostics> {
        self.previous_lock()
            .as_ref()
            .map(|retired| retired.diagnostics.clone())
    }

    fn previous_lock(&self) -> std::sync::MutexGuard<'_, Option<RetiredAnalyzer>> {
        self.previous
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    #[must_use]
    pub const fn protocol_version(&self) -> u32 {
        PROTOCOL_VERSION
    }

    pub fn shutdown_blocking(&self) {
        tauri::async_runtime::block_on(async {
            let mut slot = self.slot.write().await;
            if let AnalyzerSlot::Live(handle) = std::mem::replace(&mut *slot, AnalyzerSlot::Pending)
            {
                self.log.info("analyzer: encerrando");
                let _ = handle.shutdown().await;
            }
        });
    }
}
