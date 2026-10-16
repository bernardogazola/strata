#![allow(dead_code)]

use std::ffi::OsString;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use serde_json::value::RawValue;
use strata_lib::analyzer::{
    AnalyzerConfig, AnalyzerEvent, AnalyzerHandle, BackendPhase, EventSink, LaunchPlan, process,
};

pub fn block_on<F: std::future::Future>(future: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread()
        .enable_time()
        .build()
        .expect("um runtime current-thread não falha ao ser montado em um processo de teste")
        .block_on(future)
}

pub fn fast_config() -> AnalyzerConfig {
    AnalyzerConfig {
        handshake_timeout: Duration::from_secs(10),
        shutdown_grace: Duration::from_millis(300),
        stdin_close_grace: Duration::from_millis(200),
        stdin_write_timeout: Duration::from_secs(5),
        ..AnalyzerConfig::default()
    }
}

pub fn stub_plan(mode: &str) -> LaunchPlan {
    let working_dir = std::env::temp_dir();
    LaunchPlan {
        program: PathBuf::from(env!("CARGO_BIN_EXE_stub_child")),
        args: vec![OsString::from(format!("--mode={mode}"))],
        env: process::child_env(&working_dir),
        working_dir,
    }
}

pub fn stderr_until(
    handle: &AnalyzerHandle,
    done: impl Fn(&str) -> bool,
    patience: Duration,
) -> Vec<String> {
    let deadline = std::time::Instant::now() + patience;
    loop {
        let lines: Vec<String> = handle
            .stderr_tail()
            .lines
            .iter()
            .map(|line| line.trim_end_matches('\r').to_owned())
            .collect();
        if lines.iter().any(|line| done(line)) || std::time::Instant::now() >= deadline {
            return lines;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

pub fn empty_payload() -> &'static RawValue {
    static EMPTY: OnceLock<Box<RawValue>> = OnceLock::new();
    EMPTY.get_or_init(|| RawValue::from_string("{}".to_owned()).expect("{} é JSON válido"))
}

#[derive(Clone)]
pub struct Recorder {
    events: Arc<Mutex<Vec<AnalyzerEvent>>>,
}

impl Recorder {
    pub fn new() -> Self {
        Self {
            events: Arc::new(Mutex::new(Vec::new())),
        }
    }

    pub fn sink(&self) -> Arc<dyn EventSink> {
        Arc::new(self.clone())
    }

    fn snapshot(&self) -> Vec<AnalyzerEvent> {
        self.events
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    }

    pub fn wait_for(&self, predicate: impl Fn(&AnalyzerEvent) -> bool, patience: Duration) -> bool {
        let deadline = std::time::Instant::now() + patience;
        loop {
            if self.snapshot().iter().any(&predicate) {
                return true;
            }
            if std::time::Instant::now() >= deadline {
                return false;
            }
            std::thread::sleep(Duration::from_millis(2));
        }
    }

    pub fn settle(&self, quiet: Duration) {
        let mut last = self.snapshot().len();
        loop {
            std::thread::sleep(quiet);
            let now = self.snapshot().len();
            if now == last {
                return;
            }
            last = now;
        }
    }

    pub fn wire_names(&self) -> Vec<String> {
        self.snapshot()
            .into_iter()
            .filter_map(|event| match event {
                AnalyzerEvent::Wire { name, .. } => Some(name),
                AnalyzerEvent::State(_) | AnalyzerEvent::Fault(_) => None,
            })
            .collect()
    }

    pub fn saw_crashed(&self) -> bool {
        self.snapshot()
            .into_iter()
            .any(|event| matches!(event, AnalyzerEvent::State(BackendPhase::Crashed { .. })))
    }
}

impl EventSink for Recorder {
    fn emit(&self, event: AnalyzerEvent) {
        self.events
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .push(event);
    }
}
