use std::sync::Arc;

use tauri::{Manager, RunEvent, Theme, window::Color};

pub mod analyzer;
pub mod commands;
pub mod diagnostics;
pub mod dialog;
pub mod error;
pub mod events;
pub mod log;
pub mod repository;
mod ring;
pub mod runtime;
pub mod smoke;

use crate::log::ShellLog;
use crate::runtime::{AnalyzerRuntime, HandshakeCell, PlanSource};

// Tem de bater com BACKGROUND_COLORS em theme.ts, o global.css e o estilo inline do index.html.
const BG_LIGHT: Color = Color(246, 246, 246, 255);
const BG_DARK: Color = Color(10, 10, 10, 255);

const HOME_VAR: &str = "STRATA_ANALYZER_HOME";
const MAX_HEAP_VAR: &str = "STRATA_ANALYZER_MAX_HEAP";

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let started = std::time::Instant::now();
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            commands::open_repository,
            commands::close_repository,
            commands::analyzer_state,
            commands::analyzer_restart,
            commands::analyzer_stderr_tail,
            commands::analyzer_request,
            commands::analyzer_cancel,
            commands::export_diagnostics,
        ])
        .setup(move |app| {
            let window = app
                .get_webview_window("main")
                .expect("a janela principal deveria existir");

            let bg = match window.theme() {
                Ok(Theme::Dark) => BG_DARK,
                _ => BG_LIGHT,
            };
            let _ = window.set_background_color(Some(bg));

            let handle = window.clone();
            std::thread::spawn(move || {
                std::thread::sleep(std::time::Duration::from_secs(5));
                if !handle.is_visible().unwrap_or(true) {
                    let _ = handle.show();
                }
            });

            let shell_log = Arc::new(ShellLog::default());
            let handshake = Arc::new(HandshakeCell::default());
            let (sink, probe) = smoke::install(
                Arc::new(events::TauriSink::new(
                    app.handle().clone(),
                    Arc::clone(&shell_log),
                    Arc::clone(&handshake),
                )),
                std::env::var_os(smoke::SMOKE_REPORT_VAR),
                started,
                &shell_log,
            );
            app.manage(probe);
            let runtime = Arc::new(AnalyzerRuntime::new(
                PlanSource::Discovered {
                    home: std::env::var_os(HOME_VAR).map(Into::into),
                    resource_dir: app.path().resource_dir().ok(),
                    data_dir: app.path().app_data_dir().map_err(|error| {
                        format!("sem diretório de dados do aplicativo: {error}")
                    })?,
                    max_heap: std::env::var(MAX_HEAP_VAR).ok(),
                },
                analyzer::AnalyzerConfig::default(),
                sink,
                shell_log,
                handshake,
            ));
            app.manage(Arc::clone(&runtime));

            tauri::async_runtime::spawn(async move { runtime.boot().await });

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("erro ao montar o aplicativo Tauri");

    // build() + run(callback): só o callback recebe RunEvent::Exit, e sem ele o Cmd+Q no
    // macOS deixa a JVM rodando.
    app.run(|handle, event| {
        if matches!(event, RunEvent::Exit)
            && let Some(runtime) = handle.try_state::<Arc<AnalyzerRuntime>>()
        {
            runtime.shutdown_blocking();
        }
    });
}
