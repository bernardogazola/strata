use serde::Serialize;
use serde::ser::SerializeMap;
use serde_json::value::RawValue;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ErrorCode {
    AnalyzerNotFound,
    AnalyzerStartFailed,
    AnalyzerCrashed,
    AnalyzerNotReady,
    ShuttingDown,
    RequestTimeout,
    VersionMismatch,
    MalformedMessage,
    MessageTooLarge,
    UnknownMethod,
    InternalUnexpected,
}

impl ErrorCode {
    pub const fn as_wire(self) -> &'static str {
        match self {
            Self::AnalyzerNotFound => "PROC_ANALYZER_NOT_FOUND",
            Self::AnalyzerStartFailed => "PROC_ANALYZER_START_FAILED",
            Self::AnalyzerCrashed => "PROC_ANALYZER_CRASHED",
            Self::AnalyzerNotReady => "PROC_ANALYZER_NOT_READY",
            Self::ShuttingDown => "PROC_SHUTTING_DOWN",
            Self::RequestTimeout => "PROC_REQUEST_TIMEOUT",
            Self::VersionMismatch => "PROTO_VERSION_MISMATCH",
            Self::MalformedMessage => "PROTO_MALFORMED_MESSAGE",
            Self::MessageTooLarge => "PROTO_MESSAGE_TOO_LARGE",
            Self::UnknownMethod => "PROTO_UNKNOWN_METHOD",
            Self::InternalUnexpected => "INTERNAL_UNEXPECTED",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum ProtocolError {
    #[error("runtime do analisador não encontrado")]
    AnalyzerNotFound { detail: String },
    #[error("o analisador não conseguiu iniciar")]
    AnalyzerStartFailed { detail: String },
    #[error("o analisador terminou com {exit_code:?}")]
    AnalyzerCrashed { exit_code: Option<i32> },
    #[error("o analisador não está pronto")]
    AnalyzerNotReady,
    #[error("o analisador está sendo encerrado")]
    ShuttingDown,
    #[error("{method} excedeu {timeout_ms} ms")]
    RequestTimeout { method: String, timeout_ms: u64 },
    #[error("protocolo {received}, esperado {expected}")]
    VersionMismatch { expected: u32, received: u32 },
    #[error("frame malformado: {detail}")]
    MalformedMessage { detail: String },
    #[error("frame de {received_bytes} B acima do limite de {limit_bytes} B")]
    MessageTooLarge {
        limit_bytes: u64,
        received_bytes: u64,
    },
    #[error("método desconhecido {method}")]
    UnknownMethod { method: String },
    #[error("erro interno: {detail}")]
    Internal { detail: String },
}

impl ProtocolError {
    pub fn code(&self) -> ErrorCode {
        match self {
            Self::AnalyzerNotFound { .. } => ErrorCode::AnalyzerNotFound,
            Self::AnalyzerStartFailed { .. } => ErrorCode::AnalyzerStartFailed,
            Self::AnalyzerCrashed { .. } => ErrorCode::AnalyzerCrashed,
            Self::AnalyzerNotReady => ErrorCode::AnalyzerNotReady,
            Self::ShuttingDown => ErrorCode::ShuttingDown,
            Self::RequestTimeout { .. } => ErrorCode::RequestTimeout,
            Self::VersionMismatch { .. } => ErrorCode::VersionMismatch,
            Self::MalformedMessage { .. } => ErrorCode::MalformedMessage,
            Self::MessageTooLarge { .. } => ErrorCode::MessageTooLarge,
            Self::UnknownMethod { .. } => ErrorCode::UnknownMethod,
            Self::Internal { .. } => ErrorCode::InternalUnexpected,
        }
    }

    pub fn not_found(detail: impl Into<String>) -> Self {
        Self::AnalyzerNotFound {
            detail: detail.into(),
        }
    }

    pub fn start_failed(source: &std::io::Error) -> Self {
        Self::AnalyzerStartFailed {
            detail: source.to_string(),
        }
    }

    pub fn malformed(source: &serde_json::Error) -> Self {
        Self::MalformedMessage {
            detail: source.to_string(),
        }
    }

    pub fn internal(detail: impl Into<String>) -> Self {
        Self::Internal {
            detail: detail.into(),
        }
    }

    const fn wire_detail(&self) -> Option<&String> {
        match self {
            Self::AnalyzerNotFound { .. } | Self::AnalyzerStartFailed { .. } => None,
            Self::MalformedMessage { detail } | Self::Internal { detail } => Some(detail),
            Self::AnalyzerCrashed { .. }
            | Self::AnalyzerNotReady
            | Self::ShuttingDown
            | Self::RequestTimeout { .. }
            | Self::VersionMismatch { .. }
            | Self::MessageTooLarge { .. }
            | Self::UnknownMethod { .. } => None,
        }
    }
}

impl Serialize for ProtocolError {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(None)?;
        map.serialize_entry("code", self.code().as_wire())?;
        if let Some(detail) = self.wire_detail() {
            map.serialize_entry("detail", detail)?;
        }
        map.serialize_entry("origin", "shell")?;
        match self {
            Self::AnalyzerCrashed { exit_code } => {
                map.serialize_entry(
                    "params",
                    &ExitCodeParams {
                        exit_code: *exit_code,
                    },
                )?;
            }
            Self::RequestTimeout { method, timeout_ms } => {
                map.serialize_entry(
                    "params",
                    &TimeoutParams {
                        method,
                        timeout_ms: *timeout_ms,
                    },
                )?;
            }
            Self::VersionMismatch { expected, received } => {
                map.serialize_entry(
                    "params",
                    &VersionParams {
                        expected: *expected,
                        received: *received,
                    },
                )?;
            }
            Self::MessageTooLarge {
                limit_bytes,
                received_bytes,
            } => {
                map.serialize_entry(
                    "params",
                    &SizeParams {
                        limit_bytes: *limit_bytes,
                        received_bytes: *received_bytes,
                    },
                )?;
            }
            Self::UnknownMethod { method } => {
                map.serialize_entry("params", &MethodParams { method })?;
            }
            Self::AnalyzerNotFound { .. }
            | Self::AnalyzerStartFailed { .. }
            | Self::AnalyzerNotReady
            | Self::ShuttingDown
            | Self::MalformedMessage { .. }
            | Self::Internal { .. } => {}
        }
        map.end()
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ExitCodeParams {
    exit_code: Option<i32>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TimeoutParams<'a> {
    method: &'a str,
    timeout_ms: u64,
}

#[derive(Serialize)]
struct VersionParams {
    expected: u32,
    received: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SizeParams {
    limit_bytes: u64,
    received_bytes: u64,
}

#[derive(Serialize)]
struct MethodParams<'a> {
    method: &'a str,
}

#[derive(Debug)]
pub enum Response {
    Ok(Box<RawValue>),
    Err(Box<RawValue>),
}
