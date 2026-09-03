use std::sync::Arc;

use serde::Serialize;
use serde_json::value::RawValue;
use tauri::{AppHandle, Emitter};

use crate::analyzer::{AnalyzerEvent, BackendPhase, EventSink};
use crate::log::ShellLog;
use crate::runtime::HandshakeCell;

pub const ANALYZER_STATE: &str = "strata://analyzer-state";
pub const ANALYSIS_PROGRESS: &str = "strata://analysis-progress";
pub const ANALYSIS_STAGE: &str = "strata://analysis-stage";
pub const ANALYSIS_LIFECYCLE: &str = "strata://analysis-lifecycle";
pub const ANALYSIS_WARNING: &str = "strata://analysis-warning";
pub const CACHE_INVALIDATED: &str = "strata://cache-invalidated";

static ROUTES: &[(&str, &str)] = &[
    ("analysis.progress", ANALYSIS_PROGRESS),
    ("analysis.stage.completed", ANALYSIS_STAGE),
    ("analysis.started", ANALYSIS_LIFECYCLE),
    ("analysis.completed", ANALYSIS_LIFECYCLE),
    ("analysis.failed", ANALYSIS_LIFECYCLE),
    ("analysis.cancelled", ANALYSIS_LIFECYCLE),
    ("analysis.warning", ANALYSIS_WARNING),
    ("cache.invalidated", CACHE_INVALIDATED),
];

#[derive(Debug, PartialEq, Eq)]
pub enum Routed {
    Channel(&'static str),
    Handshake,
    Unknown,
}

#[must_use]
pub fn route(name: &str) -> Routed {
    if name == "protocol.ready" {
        return Routed::Handshake;
    }
    ROUTES
        .iter()
        .find(|(event, _)| *event == name)
        .map_or(Routed::Unknown, |(_, channel)| Routed::Channel(channel))
}

#[derive(Clone, Serialize)]
struct WireEvent<'a> {
    event: &'a str,
    payload: &'a RawValue,
}

pub struct TauriSink {
    app: AppHandle,
    log: Arc<ShellLog>,
    handshake: Arc<HandshakeCell>,
}

impl TauriSink {
    #[must_use]
    pub const fn new(app: AppHandle, log: Arc<ShellLog>, handshake: Arc<HandshakeCell>) -> Self {
        Self {
            app,
            log,
            handshake,
        }
    }

    fn emit_state(&self, phase: &BackendPhase) {
        if let Err(error) = self.app.emit(ANALYZER_STATE, phase) {
            self.log
                .warn(format!("falha ao emitir {ANALYZER_STATE}: {error}"));
        }
    }
}

impl EventSink for TauriSink {
    fn emit(&self, event: AnalyzerEvent) {
        match event {
            AnalyzerEvent::State(phase) => self.emit_state(&phase),
            AnalyzerEvent::Wire { name, payload } => match route(&name) {
                Routed::Channel(channel) => {
                    let frame = WireEvent {
                        event: &name,
                        payload: &payload,
                    };
                    if let Err(error) = self.app.emit(channel, frame) {
                        self.log.warn(format!("falha ao emitir {channel}: {error}"));
                    }
                }
                Routed::Handshake => self.handshake.remember(payload),
                Routed::Unknown => self.log.warn(format!("analyzer: evento sem rota {name}")),
            },
            AnalyzerEvent::Fault(fault) => self.log.fault(&fault),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{ANALYSIS_LIFECYCLE, Routed, route};

    #[test]
    fn the_four_lifecycle_events_share_one_channel() {
        for name in [
            "analysis.started",
            "analysis.completed",
            "analysis.failed",
            "analysis.cancelled",
        ] {
            assert_eq!(
                route(name),
                Routed::Channel(ANALYSIS_LIFECYCLE),
                "{name} deve chegar ao canal de ciclo de vida"
            );
        }
    }

    #[test]
    fn the_handshake_is_consumed_and_an_unknown_name_is_dropped() {
        assert_eq!(route("protocol.ready"), Routed::Handshake);
        assert_eq!(route("analysis.invented"), Routed::Unknown);
        assert_eq!(route(""), Routed::Unknown);
    }
}
