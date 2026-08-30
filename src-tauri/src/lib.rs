use tauri::{window::Color, Manager, Theme};

const BG_LIGHT: Color = Color(246, 246, 246, 255);
const BG_DARK: Color = Color(10, 10, 10, 255);

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Olá, {}! Você foi saudado pelo Rust!", name)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![greet])
        .setup(|app| {
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

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("erro ao executar o aplicativo Tauri");
}
