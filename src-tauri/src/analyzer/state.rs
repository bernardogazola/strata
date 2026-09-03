use std::collections::HashMap;
use std::sync::Mutex;

use tokio::sync::{Notify, Semaphore, mpsc, oneshot};

use crate::error::{ProtocolError, Response};
use crate::ring::Ring;

use super::request_id::RequestId;
use super::{AnalyzerConfig, AnalyzerEvent};

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(
    tag = "phase",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase"
)]
pub enum BackendPhase {
    Starting,
    Ready { protocol_version: u32 },
    Crashed { exit_code: Option<i32> },
    Incompatible { expected: u32, received: u32 },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Terminal {
    Exited { exit_code: Option<i32> },
    Crashed { exit_code: Option<i32> },
    Incompatible { expected: u32, received: u32 },
}

#[derive(Clone, Debug, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnostics {
    pub malformed_count: u32,
    pub last_fault: Option<ProtocolError>,
}

#[derive(Clone, Debug, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StderrTail {
    pub lines: Vec<String>,
    pub truncated: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExitReport {
    pub exit_code: Option<i32>,
    pub forced: bool,
}

pub(crate) struct Inner {
    ready: Option<u32>,
    terminal: Option<Terminal>,
    shutdown_requested: bool,
    pending: HashMap<RequestId, oneshot::Sender<Result<Response, ProtocolError>>>,
    handshake: Option<oneshot::Sender<Result<u32, ProtocolError>>>,
    writer: Option<mpsc::Sender<Vec<u8>>>,
    diagnostics: Diagnostics,
}

pub(crate) struct Shared {
    inner: Mutex<Inner>,
    stderr: Mutex<Ring>,
    events: std::sync::mpsc::Sender<AnalyzerEvent>,
    pub(crate) permits: std::sync::Arc<Semaphore>,
    pub(crate) terminal_set: Notify,
    pub(crate) config: AnalyzerConfig,
}

impl Shared {
    pub(crate) fn new(
        config: AnalyzerConfig,
        writer: mpsc::Sender<Vec<u8>>,
        handshake: oneshot::Sender<Result<u32, ProtocolError>>,
        events: std::sync::mpsc::Sender<AnalyzerEvent>,
    ) -> Self {
        let permits = std::sync::Arc::new(Semaphore::new(config.max_in_flight));
        let stderr = Ring::new(
            config.stderr_max_bytes,
            config.stderr_max_lines,
            config.stderr_max_line_bytes,
        );
        Self {
            inner: Mutex::new(Inner {
                ready: None,
                terminal: None,
                shutdown_requested: false,
                pending: HashMap::new(),
                handshake: Some(handshake),
                writer: Some(writer),
                diagnostics: Diagnostics::default(),
            }),
            stderr: Mutex::new(stderr),
            events,
            permits,
            terminal_set: Notify::new(),
            config,
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    pub(crate) fn phase(&self) -> BackendPhase {
        let inner = self.lock();
        match &inner.terminal {
            Some(Terminal::Incompatible { expected, received }) => BackendPhase::Incompatible {
                expected: *expected,
                received: *received,
            },
            Some(Terminal::Exited { exit_code } | Terminal::Crashed { exit_code }) => {
                BackendPhase::Crashed {
                    exit_code: *exit_code,
                }
            }
            None => match inner.ready {
                Some(protocol_version) => BackendPhase::Ready { protocol_version },
                None => BackendPhase::Starting,
            },
        }
    }

    pub(crate) fn diagnostics(&self) -> Diagnostics {
        self.lock().diagnostics.clone()
    }

    pub(crate) fn in_flight(&self) -> usize {
        self.config
            .max_in_flight
            .saturating_sub(self.permits.available_permits())
    }

    pub(crate) fn emit(&self, event: AnalyzerEvent) {
        let _ = self.events.send(event);
    }

    pub(crate) fn request_error(&self, inner: &Inner) -> Option<ProtocolError> {
        if let Some(terminal) = &inner.terminal {
            return Some(match terminal {
                Terminal::Exited { .. } => ProtocolError::ShuttingDown,
                Terminal::Crashed { exit_code } => ProtocolError::AnalyzerCrashed {
                    exit_code: *exit_code,
                },
                Terminal::Incompatible { expected, received } => ProtocolError::VersionMismatch {
                    expected: *expected,
                    received: *received,
                },
            });
        }
        if inner.shutdown_requested {
            return Some(ProtocolError::ShuttingDown);
        }
        if inner.ready.is_none() {
            return Some(ProtocolError::AnalyzerNotReady);
        }
        None
    }

    pub(crate) fn register(
        &self,
        id: RequestId,
        reply: oneshot::Sender<Result<Response, ProtocolError>>,
        during_shutdown: bool,
    ) -> Result<mpsc::Sender<Vec<u8>>, ProtocolError> {
        let mut inner = self.lock();
        if let Some(error) = self.request_error(&inner)
            && !(during_shutdown && matches!(error, ProtocolError::ShuttingDown))
        {
            return Err(error);
        }
        let writer = inner
            .writer
            .clone()
            .ok_or_else(|| ProtocolError::internal("o writer já terminou"))?;
        inner.pending.insert(id, reply);
        Ok(writer)
    }

    pub(crate) fn forget(&self, id: RequestId) {
        // Ligado com let para o Sender removido ser solto depois do guard: soltar um Sender
        // acorda o receptor, e isso não pode acontecer com o lock em mãos.
        let late = self.lock().pending.remove(&id);
        drop(late);
    }

    pub(crate) fn resolve(&self, id: &str, outcome: Result<Response, ProtocolError>) {
        let sender = {
            let mut inner = self.lock();
            inner
                .pending
                .iter()
                .find(|(key, _)| key.as_str() == id)
                .map(|(key, _)| *key)
                .and_then(|key| inner.pending.remove(&key))
        };
        if let Some(sender) = sender {
            let _ = sender.send(outcome);
        }
    }

    pub(crate) fn set_ready(&self, protocol_version: u32) {
        let handshake = {
            let mut inner = self.lock();
            if inner.terminal.is_some() {
                return;
            }
            inner.ready = Some(protocol_version);
            inner.handshake.take()
        };
        if let Some(handshake) = handshake {
            let _ = handshake.send(Ok(protocol_version));
        }
        self.emit(AnalyzerEvent::State(BackendPhase::Ready {
            protocol_version,
        }));
    }

    pub(crate) fn set_terminal(&self, terminal: Terminal) -> bool {
        let (accepted, handshake, pending) = {
            let mut inner = self.lock();
            if inner.terminal.is_some() {
                (false, None, Vec::new())
            } else {
                inner.terminal = Some(terminal.clone());
                inner.writer = None;
                let pending: Vec<_> = inner.pending.drain().map(|(_, tx)| tx).collect();
                (true, inner.handshake.take(), pending)
            }
        };
        if !accepted {
            return false;
        }

        let error = {
            let inner = self.lock();
            self.request_error(&inner)
                .unwrap_or_else(|| ProtocolError::internal("estado terminal sem erro"))
        };
        if let Some(handshake) = handshake {
            let _ = handshake.send(Err(error.clone()));
        }
        for sender in pending {
            let _ = sender.send(Err(error.clone()));
        }

        match &terminal {
            Terminal::Exited { .. } => {}
            Terminal::Crashed { exit_code } => {
                self.emit(AnalyzerEvent::State(BackendPhase::Crashed {
                    exit_code: *exit_code,
                }));
            }
            Terminal::Incompatible { expected, received } => {
                self.emit(AnalyzerEvent::State(BackendPhase::Incompatible {
                    expected: *expected,
                    received: *received,
                }));
            }
        }
        self.terminal_set.notify_waiters();
        true
    }

    pub(crate) fn terminal(&self) -> Option<Terminal> {
        self.lock().terminal.clone()
    }

    pub(crate) fn request_shutdown(&self) -> bool {
        let mut inner = self.lock();
        if inner.terminal.is_some() {
            return false;
        }
        inner.shutdown_requested = true;
        true
    }

    pub(crate) fn close_writer(&self) {
        let writer = self.lock().writer.take();
        drop(writer);
    }

    pub(crate) fn shutdown_requested(&self) -> bool {
        self.lock().shutdown_requested
    }

    pub(crate) fn is_stopping(&self) -> bool {
        let inner = self.lock();
        inner.shutdown_requested || matches!(inner.terminal, Some(Terminal::Exited { .. }))
    }

    pub(crate) fn note_fault(&self, fault: ProtocolError) -> bool {
        let exhausted = {
            let mut inner = self.lock();
            inner.diagnostics.malformed_count += 1;
            inner.diagnostics.last_fault = Some(fault.clone());
            inner.diagnostics.malformed_count > self.config.malformed_tolerance
        };
        self.emit(AnalyzerEvent::Fault(fault));
        exhausted
    }

    pub(crate) fn push_stderr(&self, line: String) {
        self.stderr
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .push(line);
    }

    pub(crate) fn stderr_tail(&self) -> StderrTail {
        let snapshot = self
            .stderr
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .snapshot();
        StderrTail {
            lines: snapshot.0,
            truncated: snapshot.1,
        }
    }
}
