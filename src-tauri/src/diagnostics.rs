use serde::Serialize;
use serde_json::value::RawValue;
use tauri::AppHandle;

use crate::analyzer::{Diagnostics, StderrTail};
use crate::commands::{AnalysisId, CommandError};
use crate::error::ProtocolError;
use crate::runtime::AnalyzerRuntime;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportResult {
    pub written: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Bundle<'a> {
    schema: u32,
    app: App<'a>,
    platform: Platform,
    analyzer: Analyzer<'a>,
    log: Log,
    stderr_tail: StderrTail,
    #[serde(skip_serializing_if = "Option::is_none")]
    retired_analyzer: Option<Retired>,
    #[serde(skip_serializing_if = "Option::is_none")]
    analysis_id: Option<&'a str>,
    redaction: Redaction,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct App<'a> {
    version: &'a str,
    identifier: &'a str,
    tauri: &'a str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Platform {
    os: &'static str,
    arch: &'static str,
    family: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Analyzer<'a> {
    phase: crate::analyzer::BackendPhase,
    protocol_version: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    handshake: Option<&'a RawValue>,
    malformed_count: u32,
    last_fault: Option<&'a ProtocolError>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Retired {
    stderr_tail: StderrTail,
    malformed_count: u32,
    last_fault: Option<ProtocolError>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Log {
    records: Vec<String>,
    truncated: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Redaction {
    paths: &'static str,
    authors: &'static str,
}

pub struct BundleInputs<'a> {
    pub app_version: &'a str,
    pub identifier: &'a str,
    pub phase: crate::analyzer::BackendPhase,
    pub protocol_version: u32,
    pub handshake: Option<&'a RawValue>,
    pub diagnostics: Diagnostics,
    pub stderr_tail: StderrTail,
    pub retired_stderr: Option<StderrTail>,
    pub retired_diagnostics: Option<Diagnostics>,
    pub log: (Vec<String>, bool),
    pub analysis_id: Option<&'a str>,
    pub known_roots: Vec<String>,
    pub redact_paths: bool,
    pub redact_authors: bool,
}

pub fn build_bundle(inputs: &BundleInputs<'_>) -> Result<String, ProtocolError> {
    let bundle = Bundle {
        schema: 1,
        app: App {
            version: inputs.app_version,
            identifier: inputs.identifier,
            tauri: tauri::VERSION,
        },
        platform: Platform {
            os: std::env::consts::OS,
            arch: std::env::consts::ARCH,
            family: std::env::consts::FAMILY,
        },
        analyzer: Analyzer {
            phase: inputs.phase.clone(),
            protocol_version: inputs.protocol_version,
            handshake: inputs.handshake,
            malformed_count: inputs.diagnostics.malformed_count,
            last_fault: inputs.diagnostics.last_fault.as_ref(),
        },
        log: Log {
            records: inputs.log.0.clone(),
            truncated: inputs.log.1,
        },
        stderr_tail: inputs.stderr_tail.clone(),
        retired_analyzer: inputs.retired_stderr.as_ref().map(|stderr| Retired {
            stderr_tail: stderr.clone(),
            malformed_count: inputs
                .retired_diagnostics
                .as_ref()
                .map_or(0, |d| d.malformed_count),
            last_fault: inputs
                .retired_diagnostics
                .as_ref()
                .and_then(|d| d.last_fault.clone()),
        }),
        analysis_id: inputs.analysis_id,
        redaction: Redaction {
            paths: if inputs.redact_paths {
                "known-roots"
            } else {
                "off"
            },
            authors: if inputs.redact_authors {
                "applied"
            } else {
                "off"
            },
        },
    };

    let document = serde_json::to_string_pretty(&bundle)
        .map_err(|error| ProtocolError::internal(format!("pacote de diagnóstico: {error}")))?;
    Ok(redact(
        document,
        &inputs.known_roots,
        inputs.redact_paths,
        inputs.redact_authors,
    ))
}

#[must_use]
pub fn redact(document: String, known_roots: &[String], paths: bool, authors: bool) -> String {
    let mut text = document;
    if paths {
        text = substitute_known_roots(text, known_roots);
        text = scrub_residual_paths(&text);
    }
    if authors {
        text = scrub_emails(&text);
    }
    text
}

fn substitute_known_roots(document: String, roots: &[String]) -> String {
    let mut ordered: Vec<&String> = roots.iter().filter(|root| !root.is_empty()).collect();
    ordered.sort_by_key(|root| std::cmp::Reverse(root.len()));
    let mut text = document;
    for (index, root) in ordered.iter().enumerate() {
        text = text.replace(root.as_str(), &format!("<ROOT:{index}>"));
        let escaped = root.replace('\\', "\\\\");
        if escaped != **root {
            text = text.replace(&escaped, &format!("<ROOT:{index}>"));
        }
    }
    text
}

const PATH_MARKERS: &[&str] = &["/Users/", "/home/", "/Volumes/", r":\Users\", r":\\Users\\"];

fn scrub_residual_paths(document: &str) -> String {
    let mut out = String::with_capacity(document.len());
    let mut rest = document;
    'outer: loop {
        let Some((index, marker)) = PATH_MARKERS
            .iter()
            .filter_map(|marker| rest.find(marker).map(|at| (at, *marker)))
            .min_by_key(|(at, _)| *at)
        else {
            break 'outer;
        };
        out.push_str(&rest[..index]);
        out.push_str("<PATH>");
        let after = &rest[index + marker.len()..];
        let end = after
            .find(|c: char| c == '"' || c == ',' || c.is_whitespace())
            .unwrap_or(after.len());
        rest = &after[end..];
    }
    out.push_str(rest);
    out
}

fn is_local_part(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '%' | '+' | '-')
}

fn is_domain_part(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '-')
}

fn scrub_emails(document: &str) -> String {
    let bytes: Vec<char> = document.chars().collect();
    let mut out = String::with_capacity(document.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] != '@' {
            out.push(bytes[index]);
            index += 1;
            continue;
        }
        let mut start = index;
        while start > 0 && is_local_part(bytes[start - 1]) {
            start -= 1;
        }
        let mut end = index + 1;
        while end < bytes.len() && is_domain_part(bytes[end]) {
            end += 1;
        }
        let domain: String = bytes[index + 1..end].iter().collect();
        if start == index || !domain.contains('.') {
            out.push('@');
            index += 1;
            continue;
        }
        let keep = out.chars().count() - (index - start);
        out = out.chars().take(keep).collect();
        out.push_str("<EMAIL>");
        index = end;
    }
    out
}

pub async fn export(
    app: &AppHandle,
    runtime: &AnalyzerRuntime,
    analysis_id: Option<&AnalysisId>,
    redact_paths: bool,
    redact_authors: bool,
) -> Result<ExportResult, CommandError> {
    let phase = runtime.phase().await;
    let (diagnostics, stderr_tail) = match runtime.analyzer().await {
        Ok(handle) => (handle.diagnostics(), handle.stderr_tail()),
        Err(_) => (Diagnostics::default(), StderrTail::default()),
    };
    let handshake = runtime.handshake.payload();
    let app_version = app.package_info().version.to_string();
    let identifier = app.config().identifier.clone();
    let document = build_bundle(&BundleInputs {
        app_version: &app_version,
        identifier: &identifier,
        phase,
        protocol_version: runtime.protocol_version(),
        handshake: handshake.as_deref(),
        diagnostics,
        stderr_tail,
        retired_stderr: runtime.retired_stderr(),
        retired_diagnostics: runtime.retired_diagnostics(),
        log: runtime.log.snapshot(),
        analysis_id: analysis_id.map(AnalysisId::as_str),
        known_roots: runtime.repositories.known_roots(),
        redact_paths,
        redact_authors,
    })?;

    let Some(destination) = crate::dialog::save_file(app).await else {
        return Ok(ExportResult { written: false });
    };
    std::fs::write(&destination, document)
        .map_err(|_| ProtocolError::internal("falha ao gravar o diagnóstico"))?;
    runtime.log.info("export_diagnostics: pacote gravado");
    Ok(ExportResult { written: true })
}
