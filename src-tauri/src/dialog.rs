use std::path::PathBuf;

use tauri::AppHandle;
use tauri_plugin_dialog::DialogExt;

const PICK_TITLE: &str = "Escolher repositório";

pub const DIAGNOSTICS_FILE_NAME: &str = "strata-diagnostico.json";

pub async fn pick_folder(app: &AppHandle) -> Option<PathBuf> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog()
        .file()
        .set_title(PICK_TITLE)
        .pick_folder(move |chosen| {
            let _ = tx.send(chosen);
        });
    rx.await
        .ok()
        .flatten()
        .and_then(|path| path.into_path().ok())
}

pub async fn save_file(app: &AppHandle) -> Option<PathBuf> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog()
        .file()
        .set_file_name(DIAGNOSTICS_FILE_NAME)
        .add_filter("JSON", &["json"])
        .save_file(move |chosen| {
            let _ = tx.send(chosen);
        });
    rx.await
        .ok()
        .flatten()
        .and_then(|path| path.into_path().ok())
}
