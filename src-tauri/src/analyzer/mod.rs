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
    _config: AnalyzerConfig,
    _sink: Arc<dyn EventSink>,
}

impl AnalyzerHandle {
    pub async fn spawn(
        _plan: LaunchPlan,
        _config: AnalyzerConfig,
        _sink: Arc<dyn EventSink>,
    ) -> Result<Self, ProtocolError> {
        Err(ProtocolError::internal("spawn não implementado"))
    }

    pub async fn request(
        &self,
        _method: &str,
        _payload: &RawValue,
        _timeout: Duration,
    ) -> Result<Response, ProtocolError> {
        Err(ProtocolError::internal("request não implementado"))
    }

    pub fn backend_phase(&self) -> BackendPhase {
        BackendPhase::Starting
    }

    pub fn diagnostics(&self) -> Diagnostics {
        Diagnostics::default()
    }

    pub fn stderr_tail(&self) -> StderrTail {
        StderrTail::default()
    }

    pub fn in_flight(&self) -> usize {
        0
    }

    pub async fn shutdown(&self) -> Result<ExitReport, ProtocolError> {
        Err(ProtocolError::internal("shutdown não implementado"))
    }
}
