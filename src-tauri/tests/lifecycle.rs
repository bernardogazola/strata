mod support;

use std::time::Duration;
use strata_lib::analyzer::process::{self, RealProbe};
use strata_lib::analyzer::{AnalyzerConfig, AnalyzerHandle, BackendPhase};
use strata_lib::error::ErrorCode;
#[cfg(windows)]
use support::stderr_until;
use support::{Recorder, block_on, empty_payload, fast_config, stub_plan};

#[test]
fn configured_limits_match_the_specification() {
    let config = AnalyzerConfig::default();
    assert_eq!(config.handshake_timeout, Duration::from_millis(10_000));
    assert_eq!(config.shutdown_grace, Duration::from_millis(3_000));
    assert_eq!(config.stdin_close_grace, Duration::from_millis(500));
    assert_eq!(config.stdin_write_timeout, Duration::from_millis(5_000));
    assert_eq!(config.max_in_flight, 64);
    assert_eq!(config.malformed_tolerance, 5);
    assert_eq!(config.max_frame_bytes, 16_777_216);
    assert_eq!(config.stderr_max_bytes, 256 * 1024);
    assert_eq!(config.stderr_max_lines, 2_000);
}

#[test]
fn resource_resolution_prefers_home_then_resource_then_dev_fallback() {
    let layout = process::resolve(None, None, &RealProbe)
        .expect("rode ./mvnw -q package em analyzer/ antes deste teste");
    assert!(
        layout
            .jar
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with("analyzer-") && name.ends_with(".jar")),
        "a versão está no nome do arquivo e não é conhecida em tempo de compilação: {:?}",
        layout.jar
    );
    assert!(layout.lib.ends_with("lib"));
}

#[test]
fn child_environment_is_sanitized() {
    block_on(async {
        let events = Recorder::new();
        let handle = AnalyzerHandle::spawn(stub_plan("handshake"), fast_config(), events.sink())
            .await
            .expect("stub pronto");

        let allowed: Vec<String> = process::child_env(&std::env::temp_dir())
            .iter()
            .map(|(name, _)| name.to_string_lossy().into_owned())
            .collect();

        let mut inherited = Vec::new();
        for _ in 0..200 {
            let tail = handle.stderr_tail();
            if tail.lines.iter().any(|line| line == "stub-env-end") {
                inherited = tail
                    .lines
                    .iter()
                    .filter_map(|line| line.strip_prefix("env: ").map(str::to_owned))
                    .collect();
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(!inherited.is_empty() || allowed.is_empty(), "sem relatório");

        for name in &inherited {
            assert!(
                allowed.contains(name),
                "{name} chegou ao processo filho sem estar na allowlist"
            );
        }
        assert!(
            std::env::var_os("PATH").is_some(),
            "o processo pai tem PATH"
        );
        assert!(!inherited.iter().any(|name| name == "PATH"));
        for forbidden in process::FORBIDDEN_ENV {
            assert!(!inherited.iter().any(|name| name == forbidden));
        }

        handle.shutdown().await.expect("shutdown limpo");
    });
}

#[test]
fn shutdown_timeout_force_terminates() {
    block_on(async {
        let events = Recorder::new();
        let handle = AnalyzerHandle::spawn(stub_plan("wedged"), fast_config(), events.sink())
            .await
            .expect("stub pronto");

        let report = handle
            .shutdown()
            .await
            .expect("o shutdown retorna de qualquer jeito");
        assert!(
            report.forced,
            "um filho que ignora os dois prazos precisa ser terminado"
        );
        #[cfg(unix)]
        assert_eq!(
            report.exit_code, None,
            "a morte por sinal não tem código de saída"
        );
        #[cfg(windows)]
        assert_eq!(
            report.exit_code,
            Some(1),
            "Child::kill é TerminateProcess(h, 1)"
        );
        assert_eq!(
            handle.backend_phase(),
            BackendPhase::Crashed {
                exit_code: report.exit_code
            }
        );
    });
}

#[cfg(windows)]
#[test]
fn the_child_gets_its_own_console_and_no_window() {
    let production = block_on(async {
        let events = Recorder::new();
        let handle = AnalyzerHandle::spawn(stub_plan("console"), fast_config(), events.sink())
            .await
            .expect("stub pronto");
        let lines = stderr_until(
            &handle,
            |line| line.starts_with("console-processes: "),
            Duration::from_secs(5),
        );
        handle.shutdown().await.expect("shutdown limpo");
        console_probe(&lines)
    });

    let output = std::process::Command::new(env!("CARGO_BIN_EXE_stub_child"))
        .arg("--mode=console")
        .output()
        .expect("o stub roda sem o spawn de produção");
    let lines: Vec<String> = String::from_utf8_lossy(&output.stderr)
        .lines()
        .map(str::to_owned)
        .collect();
    let control = console_probe(&lines);
    eprintln!("sonda de console: produção {production:?} · controle sem a flag {control:?}");

    let own_windowless = (String::from("absent"), 1);
    assert_eq!(
        production, own_windowless,
        "o spawn de produção deve dar ao filho um console próprio, sem janela"
    );
    assert_ne!(
        control, own_windowless,
        "o controle deve ser diferente do filho de produção"
    );
}

#[cfg(windows)]
fn console_probe(lines: &[String]) -> (String, u32) {
    let window = lines
        .iter()
        .find_map(|line| line.strip_prefix("console: "))
        .expect("o stub informa a janela do console");
    let processes = lines
        .iter()
        .find_map(|line| line.strip_prefix("console-processes: "))
        .and_then(|count| count.parse().ok())
        .expect("o stub informa quantos processos dividem o console");
    (window.to_owned(), processes)
}

#[test]
fn in_flight_is_capped_and_the_next_caller_is_refused() {
    block_on(async {
        let events = Recorder::new();
        let handle = std::sync::Arc::new(
            AnalyzerHandle::spawn(stub_plan("silent"), fast_config(), events.sink())
                .await
                .expect("stub pronto"),
        );

        let cap = AnalyzerConfig::default().max_in_flight;
        for _ in 0..cap {
            let handle = std::sync::Arc::clone(&handle);
            tokio::spawn(async move {
                let _ = handle
                    .request("system.ping", empty_payload(), Duration::from_secs(30))
                    .await;
            });
        }

        for _ in 0..200 {
            if handle.in_flight() == cap {
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        assert_eq!(handle.in_flight(), cap);

        let refused = handle
            .request("system.ping", empty_payload(), Duration::from_millis(200))
            .await
            .expect_err("a chamada além do limite espera uma vaga e desiste");
        assert_eq!(refused.code(), ErrorCode::RequestTimeout);

        handle.shutdown().await.expect("shutdown");
    });
}
