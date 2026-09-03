mod support;

use std::collections::HashSet;
use std::sync::Arc;
use std::time::Duration;

use serde_json::value::RawValue;
use strata_lib::analyzer::{AnalyzerEvent, BackendPhase, Diagnostics, StderrTail};
use strata_lib::commands::{self, AnalysisId, CommandError, METHODS, RequiresRepository};
use strata_lib::diagnostics::{BundleInputs, build_bundle};
use strata_lib::error::ErrorCode;
use strata_lib::repository::{RepositoryHandle, RepositoryRegistry};
use strata_lib::runtime::{AnalyzerRuntime, HandshakeCell, PlanSource};
use support::{Recorder, block_on, empty_payload, fast_config, stub_plan};

const SHORT: Duration = Duration::from_secs(5);

fn runtime_for(mode: &str) -> (Arc<AnalyzerRuntime>, Recorder) {
    let events = Recorder::new();
    let runtime = Arc::new(AnalyzerRuntime::new(
        PlanSource::Fixed(stub_plan(mode)),
        fast_config(),
        events.sink(),
        Arc::new(strata_lib::log::ShellLog::default()),
        Arc::new(HandshakeCell::default()),
    ));
    (runtime, events)
}

fn shell_code(error: &CommandError) -> ErrorCode {
    match error {
        CommandError::Shell(error) => error.code(),
        CommandError::Analyzer(raw) => panic!("esperava um erro do shell, mas veio {}", raw.get()),
    }
}

fn spawns(runtime: &AnalyzerRuntime) -> usize {
    runtime
        .log
        .snapshot()
        .0
        .iter()
        .filter(|record| record.contains("analyzer: iniciando o processo"))
        .count()
}

#[test]
fn a_handle_is_random_and_never_a_function_of_the_path() {
    let registry = RepositoryRegistry::default();
    let path = std::env::temp_dir();

    let first = registry
        .mint(path.clone())
        .expect("um CSPRNG precisa existir");
    let second = registry
        .mint(path.clone())
        .expect("um CSPRNG precisa existir");
    assert_ne!(
        first.handle, second.handle,
        "registrar o mesmo caminho duas vezes não pode gerar o mesmo handle: um handle derivado \
         revelaria se este diretório já foi aberto"
    );

    let allowed: HashSet<char> = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        .chars()
        .collect();
    let mut seen = HashSet::new();
    for _ in 0..10_000 {
        let handle = RepositoryHandle::generate().expect("um CSPRNG precisa existir");
        let text = handle.as_str().to_owned();
        assert_eq!(text.len(), 27, "repo_ mais 22 caracteres: {text}");
        let body = text.strip_prefix("repo_").expect("prefixo");
        assert!(
            body.chars().all(|c| allowed.contains(&c)),
            "fora do alfabeto base64url: {text}"
        );
        assert!(seen.insert(text.clone()), "handle duplicado: {text}");
    }
}

#[test]
fn the_encoder_matches_hand_computed_vectors() {
    let zeros = RepositoryHandle::generate().expect("csprng");
    assert_eq!(zeros.as_str().len(), 27);

    let finals: HashSet<char> = (0..400)
        .map(|_| {
            RepositoryHandle::generate()
                .expect("csprng")
                .as_str()
                .chars()
                .next_back()
                .expect("não vazio")
        })
        .collect();
    assert!(
        finals.is_subset(&"AQgw".chars().collect()),
        "o último caractere codifica dois bits: {finals:?}"
    );
}

#[test]
fn a_path_does_not_deserialize_into_a_handle() {
    for hostile in [
        "\"/Users/alice/repo\"",
        "\"C:\\\\Users\\\\alice\\\\repo\"",
        "\"../../etc/passwd\"",
        "\"repo_\"",
        "\"repo_tooshort\"",
        "\"repo_AAAAAAAAAAAAAAAAAAAAA/\"",
        "\"\"",
    ] {
        assert!(
            serde_json::from_str::<RepositoryHandle>(hostile).is_err(),
            "{hostile} não pode ser desserializado como handle"
        );
    }

    let minted = RepositoryHandle::generate().expect("csprng");
    let json = format!("\"{minted}\"");
    let parsed: RepositoryHandle =
        serde_json::from_str(&json).expect("um handle bem formado deve ser desserializado");
    assert_eq!(parsed, minted);
}

#[test]
fn an_unknown_handle_is_repo_handle_unknown() {
    let registry = RepositoryRegistry::default();
    let opened = registry.mint(std::env::temp_dir()).expect("csprng");

    assert!(
        registry.resolve(opened.handle).is_ok(),
        "o handle criado é resolvido"
    );

    assert!(
        registry.close(opened.handle),
        "o primeiro close remove o handle"
    );
    assert!(
        !registry.close(opened.handle),
        "fechar duas vezes é idempotente, não é erro: o StrictMode chama o cleanup duas vezes"
    );

    let error = registry
        .resolve(opened.handle)
        .expect_err("um handle fechado não pode ser resolvido");
    assert_eq!(error.code(), ErrorCode::HandleUnknown);

    let stranger = RepositoryHandle::generate().expect("csprng");
    assert_eq!(
        registry.resolve(stranger).expect_err("nunca criado").code(),
        ErrorCode::HandleUnknown
    );
}

#[test]
fn the_routing_table_is_the_eighteen_documented_methods() {
    assert_eq!(METHODS.len(), 18, "a tabela lista dezoito métodos");

    let repository_methods: Vec<&str> = METHODS
        .iter()
        .filter(|(_, requires)| *requires == RequiresRepository::Yes)
        .map(|(name, _)| *name)
        .collect();
    assert_eq!(
        repository_methods,
        vec!["repository.describe", "analysis.start"],
        "só os dois métodos que tocam o sistema de arquivos levam um caminho de repositório"
    );

    for absent in ["analysis.cancel", "system.shutdown"] {
        assert!(
            commands::lookup(absent).is_err(),
            "{absent} fica fora da tabela de propósito; tem a sua própria rota"
        );
    }
}

#[test]
fn budgets_match_the_specification() {
    assert_eq!(
        commands::budget("analysis.start"),
        Duration::from_millis(10_000)
    );
    assert_eq!(
        commands::budget("system.ping"),
        Duration::from_millis(2_000)
    );
    assert_eq!(
        commands::budget("graph.slice"),
        Duration::from_millis(30_000)
    );
    assert_eq!(
        commands::budget("repository.describe"),
        Duration::from_millis(30_000)
    );
}

#[test]
fn an_unknown_method_is_refused_before_the_child_sees_it() {
    let (runtime, _events) = runtime_for("echo-methods");
    block_on(async {
        runtime.boot().await;

        let refused = commands::passthrough(&runtime, None, "repository.delete", empty_payload())
            .await
            .expect_err("um método fora da tabela deve ser recusado");
        assert_eq!(shell_code(&refused), ErrorCode::UnknownMethod);

        commands::passthrough(&runtime, None, "system.ping", empty_payload())
            .await
            .expect("system.ping está na tabela");

        let analyzer = runtime.analyzer().await.expect("ativo");
        let seen = analyzer.stderr_tail().lines.join("\n");
        assert!(
            seen.contains("stub-method: system.ping"),
            "o stub deveria ter informado o método que recebeu: {seen}"
        );
        assert!(
            !seen.contains("repository.delete"),
            "o método recusado nunca deveria ter chegado ao processo filho: {seen}"
        );
    });
}

#[test]
fn an_over_long_method_name_is_clamped_before_it_is_echoed_back() {
    let hostile = "a".repeat(4_096);
    let error = commands::lookup(&hostile).expect_err("fora da tabela");
    let CommandError::Shell(_) = CommandError::from(error.clone()) else {
        unreachable!()
    };
    let rendered = serde_json::to_string(&error).expect("serializa");
    assert!(
        rendered.len() < 400,
        "o método ecoado deve ser cortado, não levado inteiro: {} bytes",
        rendered.len()
    );
}

#[test]
fn a_repository_method_without_a_handle_is_refused() {
    let (runtime, _events) = runtime_for("echo-methods");
    block_on(async {
        runtime.boot().await;
        let refused = commands::passthrough(&runtime, None, "repository.describe", empty_payload())
            .await
            .expect_err("um método de repositório precisa de handle");
        assert_eq!(shell_code(&refused), ErrorCode::HandleUnknown);
    });
}

#[test]
fn analysis_cancel_reaches_the_child_although_the_table_omits_it() {
    let (runtime, _events) = runtime_for("echo-methods");
    block_on(async {
        runtime.boot().await;
        let id = AnalysisId::parse("an_01JQ8Z9K3M4N5P6Q7R8S9T0V1W").expect("bem formado");
        commands::cancel(&runtime, &id)
            .await
            .expect("o cancel contorna METHODS de propósito");

        let analyzer = runtime.analyzer().await.expect("ativo");
        let seen = analyzer.stderr_tail().lines.join("\n");
        assert!(
            seen.contains("stub-method: analysis.cancel"),
            "o cancel deve chegar ao processo filho: {seen}"
        );
    });
}

#[test]
fn an_analyzer_error_is_forwarded_verbatim_and_keeps_its_origin() {
    let (runtime, _events) = runtime_for("refuse");
    block_on(async {
        runtime.boot().await;
        let error = commands::passthrough(&runtime, None, "system.ping", empty_payload())
            .await
            .expect_err("o stub recusa");
        let CommandError::Analyzer(raw) = &error else {
            panic!("um ok:false não pode ser reclassificado como erro do shell");
        };
        let rendered = raw.get();
        assert!(rendered.contains(r#""origin":"analyzer""#), "{rendered}");
        assert!(rendered.contains("REPO_NO_JAVA_SOURCES"), "{rendered}");
        let serialized = serde_json::to_string(&error).expect("serializa");
        assert!(
            serialized.contains(r#""origin":"analyzer""#),
            "{serialized}"
        );
    });
}

#[test]
fn restart_from_ready_changes_nothing() {
    let (runtime, _events) = runtime_for("echo-methods");
    block_on(async {
        runtime.boot().await;
        assert_eq!(spawns(&runtime), 1);

        let phase = runtime.restart().await.expect("READY não é erro");
        assert!(matches!(phase, BackendPhase::Ready { .. }));
        assert_eq!(
            spawns(&runtime),
            1,
            "reiniciar um analisador saudável faria falhar toda chamada em andamento com \
             PROC_ANALYZER_CRASHED, apresentando uma ação do usuário como crash"
        );
    });
}

#[test]
fn restart_while_stopping_is_refused() {
    let (runtime, _events) = runtime_for("handshake");
    block_on(async {
        runtime.boot().await;
        let analyzer = runtime.analyzer().await.expect("ativo");
        analyzer.shutdown().await.expect("shutdown limpo");

        assert!(matches!(
            analyzer.backend_phase(),
            BackendPhase::Crashed { .. }
        ));
        let refused = runtime
            .restart()
            .await
            .expect_err("um shutdown em andamento deve recusar o reinício");
        assert_eq!(refused.code(), ErrorCode::ShuttingDown);
        assert_eq!(
            spawns(&runtime),
            1,
            "nenhum segundo processo filho durante o encerramento"
        );
    });
}

#[test]
fn restart_after_a_crash_spawns_exactly_one_child_even_when_asked_twice() {
    let (runtime, events) = runtime_for("crash");
    block_on(async {
        runtime.boot().await;
        let _ = commands::passthrough(&runtime, None, "system.ping", empty_payload()).await;
        assert!(events.wait_for(
            |event| matches!(event, AnalyzerEvent::State(BackendPhase::Crashed { .. })),
            SHORT
        ));

        let (first, second) = futures_join(runtime.restart(), runtime.restart()).await;
        assert!(first.is_ok() && second.is_ok(), "{first:?} {second:?}");
        assert_eq!(
            spawns(&runtime),
            2,
            "o write guard fica preso durante todo o spawn: a segunda chamada deve \
             ver o resultado da primeira, sem iniciar uma segunda JVM de 2 GiB"
        );
    });
}

#[test]
fn a_restart_keeps_the_crashed_analyzers_evidence() {
    let (runtime, events) = runtime_for("crash");
    block_on(async {
        runtime.boot().await;
        let _ = commands::passthrough(&runtime, None, "system.ping", empty_payload()).await;
        assert!(events.wait_for(
            |event| matches!(event, AnalyzerEvent::State(BackendPhase::Crashed { .. })),
            SHORT
        ));

        assert!(
            runtime.retired_stderr().is_none(),
            "nada foi aposentado ainda"
        );
        runtime.restart().await.expect("reinício");
        let retired = runtime
            .retired_stderr()
            .expect("o tail do analisador que caiu deve sobreviver à troca");
        assert!(
            !retired.lines.is_empty(),
            "clicar em Reiniciar antes de Diagnósticos não pode exportar um pacote vazio"
        );
    });
}

async fn futures_join<A: std::future::Future, B: std::future::Future>(
    a: A,
    b: B,
) -> (A::Output, B::Output) {
    (a.await, b.await)
}

#[test]
fn backend_state_serializes_exactly_as_the_typescript_union() {
    let cases = [
        (BackendPhase::Starting, r#"{"phase":"STARTING"}"#),
        (
            BackendPhase::Ready {
                protocol_version: 1,
            },
            r#"{"phase":"READY","protocolVersion":1}"#,
        ),
        (
            BackendPhase::Crashed {
                exit_code: Some(70),
            },
            r#"{"phase":"CRASHED","exitCode":70}"#,
        ),
        (
            BackendPhase::Crashed { exit_code: None },
            r#"{"phase":"CRASHED","exitCode":null}"#,
        ),
        (
            BackendPhase::Incompatible {
                expected: 1,
                received: 2,
            },
            r#"{"phase":"INCOMPATIBLE","expected":1,"received":2}"#,
        ),
    ];
    for (phase, expected) in cases {
        assert_eq!(serde_json::to_string(&phase).expect("serializa"), expected);
    }
}

#[test]
fn the_stderr_tail_serializes_as_the_documented_dto() {
    let tail = StderrTail {
        lines: vec!["one".to_owned()],
        truncated: true,
    };
    assert_eq!(
        serde_json::to_string(&tail).expect("serializa"),
        r#"{"lines":["one"],"truncated":true}"#
    );
}

fn bundle_with(redact_paths: bool, redact_authors: bool) -> String {
    let handshake: Box<RawValue> = serde_json::value::to_raw_value(&serde_json::json!({
        "analyzerVersion": "0.0.0-stub",
        "capabilities": [],
        "protocol": 1,
    }))
    .expect("valor bruto");
    let stderr = StderrTail {
        lines: vec![
            "at br.pucpr.Main(/Users/alice/repo/src/Main.java:12)".to_owned(),
            r"caused by C:\Users\alice\repo\pom.xml".to_owned(),
            "cloned by /home/jenkins/workspace/build".to_owned(),
            "author alice@example.com committed".to_owned(),
        ],
        truncated: false,
    };
    build_bundle(&BundleInputs {
        app_version: "0.1.0",
        identifier: "br.pucpr.tcc.strata",
        phase: BackendPhase::Crashed {
            exit_code: Some(70),
        },
        protocol_version: 1,
        handshake: Some(&handshake),
        diagnostics: Diagnostics {
            malformed_count: 2,
            last_fault: Some(strata_lib::error::ProtocolError::not_found(
                "/Users/alice/analyzer/runtime/bin/java",
            )),
        },
        stderr_tail: stderr,
        retired_stderr: None,
        retired_diagnostics: None,
        log: (
            vec!["[INFO] analyzer: iniciando o processo".to_owned()],
            false,
        ),
        analysis_id: None,
        known_roots: vec!["/Users/alice/repo".to_owned()],
        redact_paths,
        redact_authors,
    })
    .expect("o pacote é montado")
}

#[test]
fn the_redacted_bundle_already_passes_the_phase_47_predicate() {
    let bundle = bundle_with(true, true);
    for leak in ["/Users/", "/home/", "/Volumes/", r"C:\Users", r"C:\\Users"] {
        assert!(
            !bundle.contains(leak),
            "redactPaths deve remover {leak}:\n{bundle}"
        );
    }
    assert!(
        !bundle.contains("alice@example.com"),
        "redactAuthors deve remover os e-mails:\n{bundle}"
    );
    assert!(
        bundle.contains(r#""paths": "known-roots""#),
        "o pacote deve dizer o que foi aplicado, sem prometer mais"
    );
}

#[test]
fn the_bundle_never_carries_a_path_through_the_error_channel() {
    let bundle = bundle_with(false, false);
    assert!(
        !bundle.contains("/Users/alice/analyzer"),
        "o detail suprimido não pode reaparecer no pacote:\n{bundle}"
    );
    assert!(
        bundle.contains("PROC_ANALYZER_NOT_FOUND"),
        "o código em si deve sobreviver, senão a seção não serve para nada"
    );
    assert!(bundle.contains("0.0.0-stub"), "{bundle}");
}

#[test]
fn an_unredacted_bundle_still_contains_the_paths_it_was_not_asked_to_remove() {
    let bundle = bundle_with(false, false);
    assert!(
        bundle.contains("/Users/alice/repo/src/Main.java"),
        "{bundle}"
    );
    assert!(bundle.contains("alice@example.com"), "{bundle}");
}
