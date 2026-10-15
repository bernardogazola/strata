use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Deserializer, Serialize, Serializer};
use serde_json::value::RawValue;
use tauri::{AppHandle, State};

use crate::analyzer::BackendPhase;
use crate::error::{ProtocolError, Response};
use crate::repository::{OpenedRepository, RepositoryHandle};
use crate::runtime::AnalyzerRuntime;
use crate::smoke::SmokeProbe;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RequiresRepository {
    Yes,
    No,
}

pub static METHODS: &[(&str, RequiresRepository)] = &[
    ("repository.describe", RequiresRepository::Yes),
    ("analysis.start", RequiresRepository::Yes),
    ("analysis.status", RequiresRepository::No),
    ("graph.slice", RequiresRepository::No),
    ("outline.children", RequiresRepository::No),
    ("graph.search", RequiresRepository::No),
    ("element.describe", RequiresRepository::No),
    ("element.dependencies", RequiresRepository::No),
    ("element.dependencyEvidence", RequiresRepository::No),
    ("evolution.cochange", RequiresRepository::No),
    ("evolution.changeMetrics", RequiresRepository::No),
    ("archaeology.timeline", RequiresRepository::No),
    ("hotspots.list", RequiresRepository::No),
    ("hotspots.ids", RequiresRepository::No),
    ("hotspot.profile", RequiresRepository::No),
    ("tour", RequiresRepository::No),
    ("diagnostics.report", RequiresRepository::No),
    ("system.ping", RequiresRepository::No),
];

const MAX_METHOD_NAME_CHARS: usize = 128;

const ANALYSIS_START: Duration = Duration::from_millis(10_000);
const SYSTEM_PING: Duration = Duration::from_millis(2_000);
const ORDINARY_REQUEST: Duration = Duration::from_millis(30_000);
const ANALYSIS_CANCEL: Duration = Duration::from_millis(5_000);

pub fn lookup(method: &str) -> Result<RequiresRepository, ProtocolError> {
    if method.len() <= MAX_METHOD_NAME_CHARS
        && let Some((_, requires)) = METHODS.iter().find(|(name, _)| *name == method)
    {
        return Ok(*requires);
    }
    Err(ProtocolError::UnknownMethod {
        method: clamp_method(method),
    })
}

fn clamp_method(method: &str) -> String {
    if method.len() <= MAX_METHOD_NAME_CHARS {
        return method.to_owned();
    }
    let mut cut = MAX_METHOD_NAME_CHARS;
    while cut > 0 && !method.is_char_boundary(cut) {
        cut -= 1;
    }
    method[..cut].to_owned()
}

#[must_use]
pub fn budget(method: &str) -> Duration {
    match method {
        "analysis.start" => ANALYSIS_START,
        "system.ping" => SYSTEM_PING,
        _ => ORDINARY_REQUEST,
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AnalysisId(String);

impl AnalysisId {
    const PREFIX: &'static str = "an_";
    const BODY_LEN: usize = 26;
    const ALPHABET: &'static [u8] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    #[must_use]
    pub fn parse(text: &str) -> Option<Self> {
        let body = text.strip_prefix(Self::PREFIX)?;
        if body.len() != Self::BODY_LEN || !body.bytes().all(|byte| Self::ALPHABET.contains(&byte))
        {
            return None;
        }
        Some(Self(text.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl<'de> Deserialize<'de> for AnalysisId {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let text = String::deserialize(deserializer)?;
        Self::parse(&text)
            .ok_or_else(|| serde::de::Error::custom("esperava an_ seguido de 26 caracteres base32"))
    }
}

#[derive(Debug)]
pub enum CommandError {
    Shell(ProtocolError),
    Analyzer(Box<RawValue>),
}

impl From<ProtocolError> for CommandError {
    fn from(error: ProtocolError) -> Self {
        Self::Shell(error)
    }
}

impl Serialize for CommandError {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            Self::Shell(error) => error.serialize(serializer),
            Self::Analyzer(raw) => raw.serialize(serializer),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CancelPayload<'a> {
    analysis_id: &'a str,
}

pub async fn passthrough(
    runtime: &AnalyzerRuntime,
    handle: Option<RepositoryHandle>,
    method: &str,
    payload: &RawValue,
) -> Result<Box<RawValue>, CommandError> {
    let requires = lookup(method)?;
    let analyzer = runtime.analyzer().await?;
    let response = match requires {
        RequiresRepository::Yes => {
            let handle = handle.ok_or(ProtocolError::HandleUnknown)?;
            let path = runtime.repositories.resolve(handle)?;
            analyzer
                .request_in_repository(method, payload, &path, budget(method))
                .await?
        }
        RequiresRepository::No => analyzer.request(method, payload, budget(method)).await?,
    };
    match response {
        Response::Ok(raw) => Ok(raw),
        Response::Err(raw) => Err(CommandError::Analyzer(raw)),
    }
}

pub async fn cancel(
    runtime: &AnalyzerRuntime,
    analysis_id: &AnalysisId,
) -> Result<Box<RawValue>, CommandError> {
    let analyzer = runtime.analyzer().await?;
    let payload = serde_json::value::to_raw_value(&CancelPayload {
        analysis_id: analysis_id.as_str(),
    })
    .map_err(|error| ProtocolError::internal(format!("payload do cancelamento: {error}")))?;
    match analyzer
        .request("analysis.cancel", &payload, ANALYSIS_CANCEL)
        .await?
    {
        Response::Ok(raw) => Ok(raw),
        Response::Err(raw) => Err(CommandError::Analyzer(raw)),
    }
}

type Runtime<'a> = State<'a, Arc<AnalyzerRuntime>>;

#[tauri::command]
pub async fn open_repository(
    app: AppHandle,
    runtime: Runtime<'_>,
) -> Result<Option<OpenedRepository>, CommandError> {
    let Some(picked) = crate::dialog::pick_folder(&app).await else {
        return Ok(None);
    };
    let canonical = crate::repository::canonicalize(&picked).map_err(|error| {
        runtime.log.warn(format!("open_repository: {error}"));
        error
    })?;
    let opened = runtime.repositories.mint(canonical)?;
    runtime
        .log
        .info(format!("open_repository: handle {} criado", opened.handle));
    Ok(Some(opened))
}

#[tauri::command]
pub fn close_repository(handle: RepositoryHandle, runtime: Runtime<'_>) {
    if runtime.repositories.close(handle) {
        runtime.log.info(format!("close_repository: {handle}"));
    }
}

#[tauri::command]
pub async fn analyzer_state(
    runtime: Runtime<'_>,
    smoke: State<'_, SmokeProbe>,
) -> Result<BackendPhase, CommandError> {
    let phase = runtime.phase().await;
    smoke.query_served(&phase);
    Ok(phase)
}

#[tauri::command]
pub async fn analyzer_restart(runtime: Runtime<'_>) -> Result<BackendPhase, CommandError> {
    Ok(runtime.restart().await?)
}

#[tauri::command]
pub async fn analyzer_stderr_tail(
    runtime: Runtime<'_>,
) -> Result<crate::analyzer::StderrTail, CommandError> {
    Ok(runtime.analyzer().await?.stderr_tail())
}

#[tauri::command]
pub async fn analyzer_request(
    handle: Option<RepositoryHandle>,
    method: String,
    payload: Box<RawValue>,
    runtime: Runtime<'_>,
) -> Result<Box<RawValue>, CommandError> {
    passthrough(runtime.inner(), handle, &method, &payload).await
}

#[tauri::command]
pub async fn analyzer_cancel(
    analysis_id: AnalysisId,
    runtime: Runtime<'_>,
) -> Result<Box<RawValue>, CommandError> {
    cancel(runtime.inner(), &analysis_id).await
}

#[tauri::command]
pub async fn export_diagnostics(
    app: AppHandle,
    analysis_id: Option<AnalysisId>,
    redact_paths: bool,
    redact_authors: bool,
    runtime: Runtime<'_>,
) -> Result<crate::diagnostics::ExportResult, CommandError> {
    crate::diagnostics::export(
        &app,
        runtime.inner(),
        analysis_id.as_ref(),
        redact_paths,
        redact_authors,
    )
    .await
}
