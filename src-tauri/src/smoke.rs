use std::ffi::OsString;
use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;

use crate::analyzer::{AnalyzerEvent, BackendPhase, EventSink};
use crate::log::ShellLog;

pub const SMOKE_REPORT_VAR: &str = "STRATA_SMOKE_REPORT";

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ReportLine<'a> {
    #[serde(flatten)]
    phase: &'a BackendPhase,
    elapsed_ms: u64,
    unix_ms: u64,
}

fn report_line(phase: &BackendPhase, elapsed_ms: u64, unix_ms: u64) -> serde_json::Result<String> {
    let mut line = serde_json::to_string(&ReportLine {
        phase,
        elapsed_ms,
        unix_ms,
    })?;
    line.push('\n');
    Ok(line)
}

fn millis(duration: std::time::Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

struct SmokeReport {
    file: Mutex<File>,
    started: Instant,
}

impl SmokeReport {
    fn create(path: &Path, started: Instant) -> io::Result<Self> {
        let file = OpenOptions::new()
            .append(true)
            .create_new(true)
            .open(path)?;
        Ok(Self {
            file: Mutex::new(file),
            started,
        })
    }

    fn record(&self, phase: &BackendPhase) -> io::Result<()> {
        let unix_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(millis)
            .unwrap_or_default();
        let line = report_line(phase, millis(self.started.elapsed()), unix_ms)?;
        let mut file = self
            .file
            .lock()
            .map_err(|_| io::Error::other("lock do relatório de smoke envenenado"))?;
        file.write_all(line.as_bytes())
    }
}

struct SmokeSink {
    inner: Arc<dyn EventSink>,
    report: SmokeReport,
    log: Arc<ShellLog>,
}

impl EventSink for SmokeSink {
    fn emit(&self, event: AnalyzerEvent) {
        let phase = match &event {
            AnalyzerEvent::State(phase) => Some(phase.clone()),
            AnalyzerEvent::Wire { .. } | AnalyzerEvent::Fault(_) => None,
        };
        self.inner.emit(event);
        if let Some(phase) = phase
            && let Err(error) = self.report.record(&phase)
        {
            self.log
                .warn(format!("relatório de smoke: falha ao gravar: {error}"));
        }
    }
}

pub fn wrap(
    inner: Arc<dyn EventSink>,
    requested: Option<OsString>,
    started: Instant,
    log: &Arc<ShellLog>,
) -> Arc<dyn EventSink> {
    let Some(path) = requested else {
        return inner;
    };
    match SmokeReport::create(Path::new(&path), started) {
        Ok(report) => {
            log.info("relatório de smoke: gravando as fases do ciclo de vida");
            Arc::new(SmokeSink {
                inner,
                report,
                log: Arc::clone(log),
            })
        }
        Err(error) => {
            log.warn(format!(
                "relatório de smoke: não foi possível criar {}: {error}",
                Path::new(&path).display()
            ));
            inner
        }
    }
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU32, Ordering};

    use serde_json::Value;
    use serde_json::value::RawValue;

    use super::{BackendPhase, report_line, wrap};
    use crate::analyzer::{AnalyzerEvent, EventSink};
    use crate::error::ProtocolError;
    use crate::log::ShellLog;
    use std::sync::{Arc, Mutex};
    use std::time::Instant;

    #[derive(Default)]
    struct Recorder {
        seen: Mutex<Vec<&'static str>>,
    }

    impl EventSink for Recorder {
        fn emit(&self, event: AnalyzerEvent) {
            let tag = match event {
                AnalyzerEvent::State(_) => "state",
                AnalyzerEvent::Wire { .. } => "wire",
                AnalyzerEvent::Fault(_) => "fault",
            };
            self.seen.lock().expect("lock do gravador").push(tag);
        }
    }

    fn scratch(name: &str) -> PathBuf {
        static NEXT: AtomicU32 = AtomicU32::new(0);
        std::env::temp_dir().join(format!(
            "strata-smoke-{}-{}-{name}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ))
    }

    fn wire() -> AnalyzerEvent {
        AnalyzerEvent::Wire {
            name: "analysis.progress".to_owned(),
            payload: RawValue::from_string("{}".to_owned()).expect("json bruto"),
        }
    }

    fn lines(path: &PathBuf) -> Vec<Value> {
        std::fs::read_to_string(path)
            .expect("o relatório existe")
            .lines()
            .map(|line| serde_json::from_str(line).expect("cada linha é json"))
            .collect()
    }

    #[test]
    fn a_line_is_the_webview_shape_plus_two_timestamps() {
        let ready = report_line(
            &BackendPhase::Ready {
                protocol_version: 1,
            },
            410,
            1_700,
        )
        .expect("serializa");
        assert_eq!(
            ready,
            "{\"phase\":\"READY\",\"protocolVersion\":1,\"elapsedMs\":410,\"unixMs\":1700}\n"
        );

        let crashed =
            report_line(&BackendPhase::Crashed { exit_code: None }, 9, 2).expect("serializa");
        assert_eq!(
            crashed,
            "{\"phase\":\"CRASHED\",\"exitCode\":null,\"elapsedMs\":9,\"unixMs\":2}\n"
        );
    }

    #[test]
    fn every_event_is_forwarded_and_only_phases_are_recorded_in_order() {
        let path = scratch("order");
        let recorder = Arc::new(Recorder::default());
        let log = Arc::new(ShellLog::default());
        let sink = wrap(
            Arc::clone(&recorder) as Arc<dyn EventSink>,
            Some(path.clone().into_os_string()),
            Instant::now(),
            &log,
        );

        sink.emit(AnalyzerEvent::State(BackendPhase::Starting));
        sink.emit(wire());
        sink.emit(AnalyzerEvent::State(BackendPhase::Ready {
            protocol_version: 1,
        }));
        sink.emit(AnalyzerEvent::Fault(ProtocolError::AnalyzerCrashed {
            exit_code: Some(70),
        }));
        sink.emit(AnalyzerEvent::State(BackendPhase::Crashed {
            exit_code: Some(70),
        }));

        assert_eq!(
            *recorder.seen.lock().expect("lock do gravador"),
            ["state", "wire", "state", "fault", "state"],
            "o decorador deve repassar tudo, intacto e em ordem"
        );
        let report = lines(&path);
        let phases: Vec<_> = report.iter().map(|line| line["phase"].clone()).collect();
        assert_eq!(phases, ["STARTING", "READY", "CRASHED"]);
        assert_eq!(report[2]["exitCode"], 70);
        assert!(report.iter().all(|line| line["unixMs"].as_u64().is_some()));
        std::fs::remove_file(&path).expect("limpeza");
    }

    #[test]
    fn an_existing_file_is_never_appended_to_and_the_sink_still_works() {
        let path = scratch("existing");
        std::fs::write(&path, "somebody else's file\n").expect("arquivo prévio gravado");
        let recorder = Arc::new(Recorder::default());
        let log = Arc::new(ShellLog::default());
        let sink = wrap(
            Arc::clone(&recorder) as Arc<dyn EventSink>,
            Some(path.clone().into_os_string()),
            Instant::now(),
            &log,
        );

        sink.emit(AnalyzerEvent::State(BackendPhase::Starting));

        assert_eq!(
            std::fs::read_to_string(&path).expect("ainda está lá"),
            "somebody else's file\n"
        );
        assert_eq!(*recorder.seen.lock().expect("lock do gravador"), ["state"]);
        std::fs::remove_file(&path).expect("limpeza");
    }

    #[test]
    fn without_the_variable_the_inner_sink_is_returned_as_is() {
        let inner: Arc<dyn EventSink> = Arc::new(Recorder::default());
        let log = Arc::new(ShellLog::default());
        let sink = wrap(Arc::clone(&inner), None, Instant::now(), &log);
        assert!(Arc::ptr_eq(&inner, &sink));
    }
}
