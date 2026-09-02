use crate::error::ProtocolError;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BackendPhase {
    Starting,
    Ready { protocol_version: u32 },
    Crashed { exit_code: Option<i32> },
    Incompatible { expected: u32, received: u32 },
}

#[derive(Clone, Debug, Default)]
pub struct Diagnostics {
    pub malformed_count: u32,
    pub last_fault: Option<ProtocolError>,
}

#[derive(Clone, Debug, Default)]
pub struct StderrTail {
    pub lines: Vec<String>,
    pub truncated: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ExitReport {
    pub exit_code: Option<i32>,
    pub forced: bool,
}
