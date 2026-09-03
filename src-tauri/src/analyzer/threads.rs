use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStderr, ChildStdin, ChildStdout};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio::sync::mpsc;

use crate::error::{ProtocolError, Response};

use super::protocol::{self, Frame};
use super::state::{Shared, Terminal};
use super::{AnalyzerEvent, EventSink, PROTOCOL_VERSION};

const REAP_PATIENCE: Duration = Duration::from_secs(5);

pub(crate) fn spawn_reader(
    stdout: ChildStdout,
    shared: Arc<Shared>,
    child: Arc<Mutex<Option<Child>>>,
) {
    std::thread::spawn(move || {
        let guard = ReaderExit {
            shared: Arc::clone(&shared),
            child,
        };
        let mut reader = BufReader::new(stdout);
        let mut buf = Vec::new();
        loop {
            match protocol::read_frame(&mut reader, shared.config.max_frame_bytes, &mut buf) {
                Ok(Frame::Line) => {
                    if handle_line(&shared, &buf) == Flow::Stop {
                        break;
                    }
                }
                Ok(Frame::Oversized { received_bytes }) => {
                    let fault = ProtocolError::MessageTooLarge {
                        limit_bytes: shared.config.max_frame_bytes as u64,
                        received_bytes,
                    };
                    if let Some(id) = protocol::scan_id(&buf) {
                        shared.resolve(id, Err(fault.clone()));
                    }
                    if shared.note_fault(fault) {
                        break;
                    }
                }
                Ok(Frame::Eof) | Err(_) => break,
            }
        }
        drop(guard);
    });
}

#[derive(PartialEq, Eq)]
enum Flow {
    Continue,
    Stop,
}

fn handle_line(shared: &Arc<Shared>, line: &[u8]) -> Flow {
    let incoming = match protocol::parse(line) {
        Ok(incoming) => incoming,
        Err(fault) => return stop_if_exhausted(shared, fault),
    };

    if incoming.protocol != PROTOCOL_VERSION {
        if shared.phase() == super::BackendPhase::Starting {
            shared.set_terminal(Terminal::Incompatible {
                expected: PROTOCOL_VERSION,
                received: incoming.protocol,
            });
            return Flow::Stop;
        }
        return stop_if_exhausted(
            shared,
            ProtocolError::VersionMismatch {
                expected: PROTOCOL_VERSION,
                received: incoming.protocol,
            },
        );
    }

    match incoming.kind {
        "event" => {
            let Some(name) = incoming.event else {
                return stop_if_exhausted(
                    shared,
                    ProtocolError::MalformedMessage {
                        detail: "o frame de evento não traz o nome do evento".to_owned(),
                    },
                );
            };
            if name == "protocol.ready" {
                shared.set_ready(incoming.protocol);
            }
            let payload = incoming
                .payload
                .map_or_else(empty_raw, serde_json::value::RawValue::to_owned);
            shared.emit(AnalyzerEvent::Wire {
                name: name.to_owned(),
                payload,
            });
            Flow::Continue
        }
        "response" => {
            let Some(id) = incoming.id else {
                return stop_if_exhausted(
                    shared,
                    ProtocolError::MalformedMessage {
                        detail: "o frame de resposta não traz id".to_owned(),
                    },
                );
            };
            let outcome = match incoming.ok {
                Some(true) => Ok(Response::Ok(
                    incoming
                        .payload
                        .map_or_else(empty_raw, serde_json::value::RawValue::to_owned),
                )),
                Some(false) => Ok(Response::Err(
                    incoming
                        .error
                        .map_or_else(empty_raw, serde_json::value::RawValue::to_owned),
                )),
                None => {
                    return stop_if_exhausted(
                        shared,
                        ProtocolError::MalformedMessage {
                            detail: "o frame de resposta não traz ok".to_owned(),
                        },
                    );
                }
            };
            shared.resolve(id, outcome);
            Flow::Continue
        }
        other => stop_if_exhausted(
            shared,
            ProtocolError::MalformedMessage {
                detail: format!("kind inesperado {other}"),
            },
        ),
    }
}

fn stop_if_exhausted(shared: &Arc<Shared>, fault: ProtocolError) -> Flow {
    if shared.note_fault(fault) {
        Flow::Stop
    } else {
        Flow::Continue
    }
}

fn empty_raw() -> Box<serde_json::value::RawValue> {
    serde_json::value::RawValue::from_string("null".to_owned())
        .unwrap_or_else(|_| unreachable!("null é JSON válido"))
}

struct ReaderExit {
    shared: Arc<Shared>,
    child: Arc<Mutex<Option<Child>>>,
}

impl Drop for ReaderExit {
    fn drop(&mut self) {
        let exit_code = reap(&self.child, REAP_PATIENCE);
        let terminal = if self.shared.shutdown_requested() && exit_code == Some(0) {
            Terminal::Exited { exit_code }
        } else {
            Terminal::Crashed { exit_code }
        };
        self.shared.set_terminal(terminal);
    }
}

fn reap(child: &Mutex<Option<Child>>, patience: Duration) -> Option<i32> {
    let deadline = Instant::now() + patience;
    loop {
        {
            let mut guard = child
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            let process = guard.as_mut()?;
            match process.try_wait() {
                Ok(Some(status)) => return status.code(),
                Ok(None) => {}
                Err(_) => return None,
            }
        }
        if Instant::now() >= deadline {
            return None;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

pub(crate) fn spawn_writer(mut rx: mpsc::Receiver<Vec<u8>>, mut stdin: ChildStdin) {
    std::thread::spawn(move || {
        while let Some(line) = rx.blocking_recv() {
            if stdin.write_all(&line).is_err() || stdin.flush().is_err() {
                break;
            }
        }
        drop(stdin);
    });
}

pub(crate) fn spawn_stderr(stderr: ChildStderr, shared: Arc<Shared>) {
    std::thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.split(b'\n') {
            match line {
                Ok(bytes) => shared.push_stderr(String::from_utf8_lossy(&bytes).into_owned()),
                Err(_) => break,
            }
        }
    });
}

pub(crate) fn spawn_emitter(
    rx: std::sync::mpsc::Receiver<AnalyzerEvent>,
    sink: Arc<dyn EventSink>,
) {
    std::thread::spawn(move || {
        while let Ok(event) = rx.recv() {
            let sink = Arc::clone(&sink);
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
                sink.emit(event);
            }));
        }
    });
}
