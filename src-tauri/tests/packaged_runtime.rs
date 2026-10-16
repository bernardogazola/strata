mod support;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use strata_lib::analyzer::process::{self, AnalyzerLayout, RealProbe};
use strata_lib::analyzer::{AnalyzerConfig, AnalyzerHandle, LaunchPlan, NullSink};
use strata_lib::error::ProtocolError;
use support::{block_on, stderr_until};

const LATIN: &str = "dados-\u{e7}\u{e3}o";

const CJK: &str = "\u{6570}\u{636e}";

const LOG_PATIENCE: Duration = Duration::from_secs(5);

fn config() -> AnalyzerConfig {
    AnalyzerConfig {
        handshake_timeout: Duration::from_secs(30),
        ..AnalyzerConfig::default()
    }
}

fn staged_layout() -> AnalyzerLayout {
    let home = std::env::var_os("STRATA_ANALYZER_HOME").expect(
        "STRATA_ANALYZER_HOME deve apontar para um runtime preparado: rode scripts/build-analyzer antes",
    );
    process::resolve(Some(Path::new(&home)), None, &RealProbe)
        .expect("STRATA_ANALYZER_HOME contém um runtime completo")
}

fn production_plan(data_dir: &Path) -> LaunchPlan {
    let layout = staged_layout();
    LaunchPlan {
        args: process::analyzer_args(&layout, data_dir, None),
        program: layout.java.clone(),
        env: process::child_env(data_dir),
        working_dir: data_dir.to_path_buf(),
    }
}

fn data_directory_line(data_dir: &Path) -> String {
    format!("[INFO] analyzer: pasta de dados: {}", data_dir.display())
}

struct Scratch {
    root: PathBuf,
    data_dir: PathBuf,
}

impl Scratch {
    fn new(case: &str, leaf: &str) -> Self {
        let root =
            std::env::temp_dir().join(format!("strata-packaged-{}-{case}", std::process::id()));
        let data_dir = root.join(leaf);
        std::fs::create_dir_all(&data_dir).expect("o diretório temporário aceita escrita");
        Self { root, data_dir }
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

#[derive(Debug, PartialEq, Eq)]
enum Outcome {
    Faithful,
    Refused { exit_code: i32 },
}

fn launch(plan: LaunchPlan, data_dir: &Path) -> Outcome {
    let expected = data_directory_line(data_dir);
    block_on(async {
        match AnalyzerHandle::spawn(plan, config(), Arc::new(NullSink)).await {
            Ok(handle) => {
                let lines = stderr_until(&handle, |line| line == expected, LOG_PATIENCE);
                handle
                    .shutdown()
                    .await
                    .expect("o analisador para quando pedido");
                let logged = lines.iter().find(|line| line.contains("pasta de dados: "));
                assert!(
                    lines.contains(&expected),
                    "READY com um diretório de dados diferente do pedido:\n  \
                     pedido     {expected}\n  registrado {logged:?}"
                );
                Outcome::Faithful
            }
            Err(ProtocolError::AnalyzerCrashed {
                exit_code: Some(exit_code),
            }) if exit_code != 0 => Outcome::Refused { exit_code },
            Err(other) => panic!("nem READY nem uma saída diferente de zero antes dele: {other:?}"),
        }
    })
}

#[test]
#[ignore = "precisa de um runtime preparado em STRATA_ANALYZER_HOME"]
fn an_ascii_data_directory_reaches_ready() {
    let scratch = Scratch::new("ascii", "dados");
    let outcome = launch(production_plan(&scratch.data_dir), &scratch.data_dir);
    assert_eq!(outcome, Outcome::Faithful);
}

#[test]
#[ignore = "precisa de um runtime preparado em STRATA_ANALYZER_HOME"]
fn a_latin_data_directory_reaches_ready_on_every_system() {
    let scratch = Scratch::new("latin", LATIN);
    let outcome = launch(production_plan(&scratch.data_dir), &scratch.data_dir);
    assert_eq!(outcome, Outcome::Faithful);
}

#[test]
#[ignore = "precisa de um runtime preparado em STRATA_ANALYZER_HOME"]
fn a_cjk_data_directory_is_never_silently_rewritten() {
    let scratch = Scratch::new("cjk", CJK);
    let outcome = launch(production_plan(&scratch.data_dir), &scratch.data_dir);
    eprintln!(
        "diretório de dados CJK em {}: {outcome:?}",
        std::env::consts::OS
    );
    #[cfg(unix)]
    assert_eq!(outcome, Outcome::Faithful);
}

#[cfg(unix)]
#[test]
#[ignore = "precisa de um runtime preparado em STRATA_ANALYZER_HOME"]
fn without_a_locale_the_analyzer_refuses_instead_of_rewriting_the_path() {
    let scratch = Scratch::new("no-locale", CJK);
    let plan = production_plan(&scratch.data_dir);
    let plan = LaunchPlan {
        env: plan
            .env
            .into_iter()
            .filter(|(name, _)| name != "LANG")
            .collect(),
        ..plan
    };
    assert!(
        !plan
            .env
            .iter()
            .any(|(name, _)| name.to_string_lossy().starts_with("LC_")),
        "o ambiente de produção não leva nenhum LC_* que possa substituir o LANG"
    );

    let output = process::spawn(&plan)
        .expect("o java preparado inicia")
        .wait_with_output()
        .expect("o processo filho é coletado");
    let stderr = String::from_utf8_lossy(&output.stderr);
    eprintln!(
        "sem locale em {}: saída {:?}\n{stderr}",
        std::env::consts::OS,
        output.status.code()
    );

    if cfg!(target_os = "macos") {
        assert_eq!(output.status.code(), Some(0), "{stderr}");
        let expected = data_directory_line(&scratch.data_dir);
        assert!(stderr.lines().any(|line| line == expected), "{stderr}");
        return;
    }

    assert_eq!(output.status.code(), Some(78), "{stderr}");
    assert!(
        output.stdout.is_empty(),
        "a guarda roda antes do protocol.ready, então nada pode chegar ao stdout: {:?}",
        String::from_utf8_lossy(&output.stdout)
    );
    assert!(
        stderr.lines().any(|line| {
            line.starts_with("[ERROR] analyzer: sun.jnu.encoding vale ")
                && line.ends_with("; decodificar caminhos de arquivo exige um locale UTF-8")
        }),
        "{stderr}"
    );
    assert!(
        !stderr.contains("pasta de dados: "),
        "a guarda roda antes de o vetor de argumentos ser lido"
    );

    let shell = block_on(AnalyzerHandle::spawn(plan, config(), Arc::new(NullSink)));
    assert!(
        matches!(
            shell,
            Err(ProtocolError::AnalyzerCrashed {
                exit_code: Some(78)
            })
        ),
        "{:?}",
        shell.err()
    );
}
