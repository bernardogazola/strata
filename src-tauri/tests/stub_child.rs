use std::io::{BufRead, Write};

const READY: &str = r#"{"event":"protocol.ready","kind":"event","payload":{"analyzerVersion":"0.0.0-stub","capabilities":[],"protocol":1},"protocol":1}"#;

const MAX_FRAME_BYTES: usize = 16_777_216;

fn main() {
    let mode = std::env::args()
        .find_map(|a| a.strip_prefix("--mode=").map(str::to_owned))
        .unwrap_or_else(|| "handshake".to_owned());

    let mut names: Vec<String> = std::env::vars_os()
        .map(|(k, _)| k.to_string_lossy().into_owned())
        .collect();
    names.sort();
    eprintln!("stub-env-begin");
    for name in &names {
        eprintln!("env: {name}");
    }
    eprintln!("stub-env-end");

    if mode == "console" {
        for line in console_report() {
            eprintln!("{line}");
        }
    }

    if mode == "wedged" {
        emit(READY);
        std::thread::sleep(std::time::Duration::from_secs(600));
        return;
    }

    emit(READY);

    let stdin = std::io::stdin();
    let mut reader = stdin.lock();
    let mut line = Vec::new();
    let mut seen: Vec<String> = Vec::new();

    loop {
        line.clear();
        match reader.read_until(b'\n', &mut line) {
            Ok(0) => break,
            Ok(_) => {}
            Err(_) => break,
        }
        if line.last() == Some(&b'\n') {
            line.pop();
        }
        let text = String::from_utf8_lossy(&line).into_owned();
        let Some(id) = extract_id(&text) else {
            continue;
        };
        let is_shutdown = text.contains(r#""method":"system.shutdown""#);

        match mode.as_str() {
            "crash" => {
                std::process::exit(70);
            }
            "silent" => {}
            "ignore-shutdown" => {
                if !is_shutdown {
                    emit(&ok_empty(&id));
                }
            }
            "reorder3" => {
                seen.push(id);
                if seen.len() == 3 {
                    for index in [1_usize, 0, 2] {
                        emit(&ok_index(&seen[index], index));
                    }
                    seen.clear();
                }
            }
            "oversized" => {
                if seen.is_empty() {
                    seen.push(id.clone());
                    emit(&oversized_response(&id));
                } else {
                    emit(&ok_empty(&id));
                }
            }
            "echo-methods" => {
                eprintln!("stub-method: {}", extract_method(&text));
                if is_shutdown {
                    emit(&ok_empty(&id));
                    std::io::stdout().flush().ok();
                    return;
                }
                emit(&ok_empty(&id));
            }
            "refuse" => {
                if is_shutdown {
                    emit(&ok_empty(&id));
                    std::io::stdout().flush().ok();
                    return;
                }
                emit(&error_response(&id));
            }
            "stderr-flood" => {
                let noise = "x".repeat(1024);
                for n in 0..1024 {
                    eprintln!("flood {n} {noise}");
                }
                emit(&ok_empty(&id));
            }
            _ => {
                if is_shutdown {
                    emit(&ok_empty(&id));
                    std::io::stdout().flush().ok();
                    return;
                }
                emit(&ok_empty(&id));
            }
        }
    }
}

#[cfg(windows)]
fn console_report() -> Vec<String> {
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetConsoleWindow() -> *mut core::ffi::c_void;
        fn GetConsoleProcessList(process_list: *mut u32, process_count: u32) -> u32;
    }
    const CAPACITY: u32 = 64;

    // SAFETY: não recebe argumentos e só lê o console deste processo; null indica que não há janela.
    let window = unsafe { GetConsoleWindow() };
    let mut ids = [0_u32; CAPACITY as usize];
    // SAFETY: `ids` tem espaço para exatamente CAPACITY u32, a quantidade passada.
    let attached = unsafe { GetConsoleProcessList(ids.as_mut_ptr(), CAPACITY) };

    let window = if window.is_null() {
        "absent"
    } else {
        "present"
    };
    vec![
        format!("console: {window}"),
        format!("console-processes: {attached}"),
    ]
}

#[cfg(not(windows))]
fn console_report() -> Vec<String> {
    vec![String::from("console: n/a")]
}

fn error_response(id: &str) -> String {
    format!(
        r#"{{"protocol":1,"kind":"response","id":"{id}","ok":false,"error":{{"code":"REPO_NO_JAVA_SOURCES","origin":"analyzer","params":{{}}}}}}"#
    )
}

fn extract_method(line: &str) -> String {
    let Some(start) = line.find(r#""method":""#) else {
        return String::from("<none>");
    };
    let rest = &line[start + r#""method":""#.len()..];
    rest.find('"').map_or_else(
        || String::from("<unterminated>"),
        |end| rest[..end].to_owned(),
    )
}

fn emit(frame: &str) {
    let mut out = std::io::stdout().lock();
    out.write_all(frame.as_bytes()).ok();
    out.write_all(b"\n").ok();
    out.flush().ok();
}

fn ok_empty(id: &str) -> String {
    format!(r#"{{"id":"{id}","kind":"response","ok":true,"payload":{{}},"protocol":1}}"#)
}

fn ok_index(id: &str, index: usize) -> String {
    format!(
        r#"{{"id":"{id}","kind":"response","ok":true,"payload":{{"index":{index}}},"protocol":1}}"#
    )
}

fn oversized_response(id: &str) -> String {
    let head = format!(r#"{{"id":"{id}","kind":"response","ok":true,"payload":{{"pad":""#);
    let tail = r#""},"protocol":1}"#;
    let pad = MAX_FRAME_BYTES + 1 - head.len() - tail.len();
    format!("{head}{}{tail}", "x".repeat(pad))
}

fn extract_id(line: &str) -> Option<String> {
    let start = line.find(r#""id":""#)? + 6;
    let rest = &line[start..];
    let end = rest.find('"')?;
    Some(rest[..end].to_owned())
}
