pub mod process;
pub mod protocol;
pub mod request_id;
mod state;
mod threads;

use std::ffi::OsString;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use serde_json::value::RawValue;

use crate::error::{ProtocolError, Response};

pub use request_id::RequestId;
pub use state::{BackendPhase, Diagnostics, ExitReport, StderrTail};

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Clone, Debug)]
pub struct AnalyzerConfig {
    pub handshake_timeout: Duration,
    pub shutdown_grace: Duration,
    pub stdin_close_grace: Duration,
    pub stdin_write_timeout: Duration,
    pub max_in_flight: usize,
    pub malformed_tolerance: u32,
    pub max_frame_bytes: usize,
    pub stderr_max_bytes: usize,
    pub stderr_max_lines: usize,
    pub stderr_max_line_bytes: usize,
}

impl Default for AnalyzerConfig {
    fn default() -> Self {
        Self {
            handshake_timeout: Duration::from_millis(10_000),
            shutdown_grace: Duration::from_millis(3_000),
            stdin_close_grace: Duration::from_millis(500),
            stdin_write_timeout: Duration::from_millis(5_000),
            max_in_flight: 64,
            malformed_tolerance: 5,
            max_frame_bytes: 16_777_216,
            stderr_max_bytes: 256 * 1024,
            stderr_max_lines: 2_000,
            stderr_max_line_bytes: 8_192,
        }
    }
}

#[derive(Clone, Debug)]
pub struct LaunchPlan {
    pub program: PathBuf,
    pub args: Vec<OsString>,
    pub env: Vec<(OsString, OsString)>,
    pub working_dir: PathBuf,
}

#[derive(Clone, Debug)]
pub enum AnalyzerEvent {
    Wire {
        name: String,
        payload: Box<RawValue>,
    },
    State(BackendPhase),
    Fault(ProtocolError),
}

pub trait EventSink: Send + Sync + 'static {
    fn emit(&self, event: AnalyzerEvent);
}

pub struct NullSink;

impl EventSink for NullSink {
    fn emit(&self, _event: AnalyzerEvent) {}
}

pub struct AnalyzerHandle {
    shared: Arc<state::Shared>,
    child: Arc<std::sync::Mutex<Option<std::process::Child>>>,
}

impl AnalyzerHandle {
    pub async fn spawn(
        plan: LaunchPlan,
        config: AnalyzerConfig,
        sink: Arc<dyn EventSink>,
    ) -> Result<Self, ProtocolError> {
        let mut child = process::spawn(&plan)?;

        let stdin = take_pipe(child.stdin.take(), "stdin")?;
        let stdout = take_pipe(child.stdout.take(), "stdout")?;
        let stderr = take_pipe(child.stderr.take(), "stderr")?;

        let (writer_tx, writer_rx) = tokio::sync::mpsc::channel(config.max_in_flight);
        let (handshake_tx, handshake_rx) = tokio::sync::oneshot::channel();
        let (events_tx, events_rx) = std::sync::mpsc::channel();

        let handshake_timeout = config.handshake_timeout;
        let shared = Arc::new(state::Shared::new(
            config,
            writer_tx,
            handshake_tx,
            events_tx,
        ));
        let child = Arc::new(std::sync::Mutex::new(Some(child)));

        threads::spawn_emitter(events_rx, sink);
        threads::spawn_stderr(stderr, Arc::clone(&shared));
        threads::spawn_writer(writer_rx, stdin);
        threads::spawn_reader(stdout, Arc::clone(&shared), Arc::clone(&child));

        let handle = Self { shared, child };
        match tokio::time::timeout(handshake_timeout, handshake_rx).await {
            Ok(Ok(Ok(_))) => Ok(handle),
            Ok(Ok(Err(error))) => Err(error),
            Ok(Err(_)) => Err(ProtocolError::AnalyzerCrashed { exit_code: None }),
            Err(_) => {
                handle.force_terminate();
                Err(ProtocolError::RequestTimeout {
                    method: "protocol.ready".to_owned(),
                    timeout_ms: handshake_timeout.as_millis() as u64,
                })
            }
        }
    }

    pub async fn request(
        &self,
        method: &str,
        payload: &RawValue,
        timeout: Duration,
    ) -> Result<Response, ProtocolError> {
        self.dispatch(method, payload, None, timeout, false).await
    }

    async fn dispatch(
        &self,
        method: &str,
        payload: &RawValue,
        repository_path: Option<&str>,
        timeout: Duration,
        during_shutdown: bool,
    ) -> Result<Response, ProtocolError> {
        let deadline = tokio::time::Instant::now() + timeout;
        let expired = || ProtocolError::RequestTimeout {
            method: method.to_owned(),
            timeout_ms: timeout.as_millis() as u64,
        };

        let admission = self
            .shared
            .config
            .stdin_write_timeout
            .min(remaining(deadline));
        let permit =
            tokio::time::timeout(admission, Arc::clone(&self.shared.permits).acquire_owned())
                .await
                .map_err(|_| expired())?
                .map_err(|_| ProtocolError::ShuttingDown)?;

        let id = RequestId::generate()
            .map_err(|error| ProtocolError::internal(format!("sem CSPRNG: {error}")))?;
        let line = protocol::encode_request(id.as_str(), method, payload, repository_path)?;

        let (reply_tx, reply_rx) = tokio::sync::oneshot::channel();
        let writer = self.shared.register(id, reply_tx, during_shutdown)?;
        let pending = Pending {
            shared: Arc::clone(&self.shared),
            id,
            _permit: permit,
        };

        tokio::time::timeout(
            remaining(deadline).min(self.shared.config.stdin_write_timeout),
            writer.send(line),
        )
        .await
        .map_err(|_| expired())?
        .map_err(|_| ProtocolError::ShuttingDown)?;

        let outcome = tokio::time::timeout(remaining(deadline), reply_rx).await;
        drop(pending);
        match outcome {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(ProtocolError::AnalyzerCrashed { exit_code: None }),
            Err(_) => Err(expired()),
        }
    }

    #[must_use]
    pub fn backend_phase(&self) -> BackendPhase {
        self.shared.phase()
    }

    #[must_use]
    pub fn diagnostics(&self) -> Diagnostics {
        self.shared.diagnostics()
    }

    #[must_use]
    pub fn stderr_tail(&self) -> StderrTail {
        self.shared.stderr_tail()
    }

    #[must_use]
    pub fn in_flight(&self) -> usize {
        self.shared.in_flight()
    }

    pub async fn shutdown(&self) -> Result<ExitReport, ProtocolError> {
        if let Some(terminal) = self.shared.terminal() {
            return Ok(report(&terminal, false));
        }
        if !self.shared.request_shutdown() {
            let terminal = self.shared.terminal();
            return Ok(terminal.as_ref().map_or(
                ExitReport {
                    exit_code: None,
                    forced: false,
                },
                |t| report(t, false),
            ));
        }

        let grace = self.shared.config.shutdown_grace;
        let empty = RawValue::from_string("{}".to_owned())
            .map_err(|error| ProtocolError::internal(format!("payload vazio: {error}")))?;
        let _ = self
            .dispatch("system.shutdown", &empty, None, grace, true)
            .await;

        if let Some(terminal) = self.await_terminal(grace).await {
            return Ok(report(&terminal, false));
        }
        self.shared.close_writer();
        if let Some(terminal) = self
            .await_terminal(self.shared.config.stdin_close_grace)
            .await
        {
            return Ok(report(&terminal, false));
        }

        self.force_terminate();
        let terminal = self.await_terminal(grace).await;
        Ok(terminal.as_ref().map_or(
            ExitReport {
                exit_code: None,
                forced: true,
            },
            |t| ExitReport {
                exit_code: report(t, true).exit_code,
                forced: true,
            },
        ))
    }

    async fn await_terminal(&self, patience: Duration) -> Option<state::Terminal> {
        let deadline = tokio::time::Instant::now() + patience;
        loop {
            if let Some(terminal) = self.shared.terminal() {
                return Some(terminal);
            }
            let waited =
                tokio::time::timeout_at(deadline, self.shared.terminal_set.notified()).await;
            if waited.is_err() {
                return self.shared.terminal();
            }
        }
    }

    fn force_terminate(&self) {
        let mut guard = self
            .child
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(process) = guard.as_mut() {
            let _ = process.kill();
        }
    }
}

impl Drop for AnalyzerHandle {
    fn drop(&mut self) {
        let mut guard = self
            .child
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(process) = guard.as_mut() {
            let _ = process.kill();
            let _ = process.try_wait();
        }
    }
}

struct Pending {
    shared: Arc<state::Shared>,
    id: RequestId,
    _permit: tokio::sync::OwnedSemaphorePermit,
}

impl Drop for Pending {
    fn drop(&mut self) {
        self.shared.forget(self.id);
    }
}

fn remaining(deadline: tokio::time::Instant) -> Duration {
    deadline.saturating_duration_since(tokio::time::Instant::now())
}

const fn report(terminal: &state::Terminal, forced: bool) -> ExitReport {
    let exit_code = match terminal {
        state::Terminal::Exited { exit_code } | state::Terminal::Crashed { exit_code } => {
            *exit_code
        }
        state::Terminal::Incompatible { .. } => None,
    };
    ExitReport { exit_code, forced }
}

fn take_pipe<T>(pipe: Option<T>, name: &str) -> Result<T, ProtocolError> {
    pipe.ok_or_else(|| ProtocolError::internal(format!("o processo filho não tem {name}")))
}
