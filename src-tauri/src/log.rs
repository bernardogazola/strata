use std::sync::Mutex;

use crate::error::ProtocolError;
use crate::ring::Ring;

const MAX_BYTES: usize = 512 * 1024;
const MAX_RECORDS: usize = 2_000;
const MAX_RECORD_BYTES: usize = 2_048;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Level {
    Info,
    Warn,
}

impl Level {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Info => "INFO",
            Self::Warn => "WARN",
        }
    }
}

pub struct ShellLog {
    ring: Mutex<Ring>,
}

impl Default for ShellLog {
    fn default() -> Self {
        Self {
            ring: Mutex::new(Ring::new(MAX_BYTES, MAX_RECORDS, MAX_RECORD_BYTES)),
        }
    }
}

impl ShellLog {
    pub fn info(&self, message: impl Into<String>) {
        self.record(Level::Info, message.into());
    }

    pub fn warn(&self, message: impl Into<String>) {
        self.record(Level::Warn, message.into());
    }

    pub fn fault(&self, fault: &ProtocolError) {
        let rendered = serde_json::to_string(fault)
            .unwrap_or_else(|_| format!("{{\"code\":\"{}\"}}", fault.code().as_wire()));
        self.record(Level::Warn, format!("falha do analisador {rendered}"));
    }

    fn record(&self, level: Level, message: String) {
        self.lock().push(format!("[{}] {message}", level.as_str()));
    }

    #[must_use]
    pub fn snapshot(&self) -> (Vec<String>, bool) {
        self.lock().snapshot()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Ring> {
        self.ring
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

#[cfg(test)]
mod tests {
    use super::ShellLog;
    use crate::error::ProtocolError;

    #[test]
    fn a_fault_is_recorded_through_the_wire_serializer_not_debug() {
        let log = ShellLog::default();
        log.fault(&ProtocolError::not_found("/Users/alice/analyzer"));

        let (lines, _) = log.snapshot();
        let line = lines.last().expect("um registro");
        assert!(line.contains("PROC_ANALYZER_NOT_FOUND"), "veio {line}");
        assert!(
            !line.contains("/Users/alice"),
            "o caminho não pode chegar ao registro de log: {line}"
        );
    }

    #[test]
    fn the_log_keeps_the_end_of_the_stream() {
        let log = ShellLog::default();
        for n in 0..3_000 {
            log.info(format!("record {n}"));
        }
        let (lines, truncated) = log.snapshot();
        assert!(
            truncated,
            "3 000 registros acima de um teto de 2 000 devem indicar perda"
        );
        assert!(
            lines.last().expect("registros").contains("record 2999"),
            "o registro mais novo deve sobreviver"
        );
    }
}
