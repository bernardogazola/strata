use std::ffi::OsString;
use std::path::Path;

use crate::error::ProtocolError;

pub const FORBIDDEN_ENV: &[&str] = &[
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "CLASSPATH",
];

#[must_use]
pub fn child_env(data_dir: &Path) -> Vec<(OsString, OsString)> {
    let mut env: Vec<(OsString, OsString)> = Vec::new();

    #[cfg(unix)]
    {
        env.push((OsString::from("LANG"), OsString::from(CHILD_LOCALE)));
        env.push((OsString::from("TMPDIR"), data_dir.as_os_str().to_owned()));
        inherit(&mut env, "HOME");
    }

    #[cfg(windows)]
    {
        // Sem SystemRoot a JVM morre antes do main: o loader não consegue iniciar o runtime de C
        // nem o Winsock.
        for name in [
            "SystemRoot",
            "windir",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "ProgramData",
            "PATHEXT",
            "NUMBER_OF_PROCESSORS",
        ] {
            inherit(&mut env, name);
        }
        env.push((OsString::from("TEMP"), data_dir.as_os_str().to_owned()));
        env.push((OsString::from("TMP"), data_dir.as_os_str().to_owned()));
    }

    let _ = data_dir;
    env
}

fn inherit(env: &mut Vec<(OsString, OsString)>, name: &str) {
    if let Some(value) = std::env::var_os(name) {
        env.push((OsString::from(name), value));
    }
}

#[cfg(unix)]
const CHILD_LOCALE: &str = "C.UTF-8";

pub const MAIN_CLASS: &str = "br.pucpr.tcc.strata.analyzer.bootstrap.Main";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AnalyzerLayout {
    pub java: std::path::PathBuf,
    pub jar: std::path::PathBuf,
    pub lib: std::path::PathBuf,
}

pub trait Probe {
    fn is_file(&self, path: &Path) -> bool;
    fn is_dir(&self, path: &Path) -> bool;
    fn find_jar(&self, dir: &Path) -> Vec<std::path::PathBuf>;
}

pub struct RealProbe;

impl Probe for RealProbe {
    fn is_file(&self, path: &Path) -> bool {
        path.is_file()
    }

    fn is_dir(&self, path: &Path) -> bool {
        path.is_dir()
    }

    fn find_jar(&self, dir: &Path) -> Vec<std::path::PathBuf> {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return Vec::new();
        };
        let mut found: Vec<_> = entries
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| {
                path.extension().is_some_and(|ext| ext == "jar")
                    && path
                        .file_name()
                        .and_then(|name| name.to_str())
                        .is_some_and(|name| name.starts_with("analyzer-"))
            })
            .collect();
        found.sort();
        found
    }
}

#[must_use]
pub fn packaged_layout(root: &Path) -> AnalyzerLayout {
    AnalyzerLayout {
        java: root.join("runtime").join("bin").join(java_executable()),
        jar: root.join("analyzer.jar"),
        lib: root.join("lib"),
    }
}

const fn java_executable() -> &'static str {
    if cfg!(windows) { "java.exe" } else { "java" }
}

pub fn resolve(
    home: Option<&Path>,
    resource_dir: Option<&Path>,
    probe: &dyn Probe,
) -> Result<AnalyzerLayout, ProtocolError> {
    if let Some(root) = home {
        let layout = packaged_layout(root);
        return validate(layout, probe).ok_or_else(|| {
            ProtocolError::not_found(format!(
                "STRATA_ANALYZER_HOME aponta para {}, mas não há runtime preparado lá",
                root.display()
            ))
        });
    }

    if let Some(resource_dir) = resource_dir
        && let Some(layout) = validate(packaged_layout(&resource_dir.join("analyzer")), probe)
    {
        return Ok(layout);
    }

    #[cfg(debug_assertions)]
    if let Some(layout) = development_layout(probe) {
        return Ok(layout);
    }

    Err(ProtocolError::not_found(
        "nenhum runtime preparado do analisador foi encontrado",
    ))
}

fn validate(layout: AnalyzerLayout, probe: &dyn Probe) -> Option<AnalyzerLayout> {
    let complete =
        probe.is_file(&layout.java) && probe.is_file(&layout.jar) && probe.is_dir(&layout.lib);
    complete.then_some(layout)
}

#[cfg(debug_assertions)]
fn development_layout(probe: &dyn Probe) -> Option<AnalyzerLayout> {
    let target = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()?
        .join("analyzer")
        .join("target");
    let jars = probe.find_jar(&target);
    let jar = match jars.as_slice() {
        [only] => only.clone(),
        _ => return None,
    };
    let lib = target.join("lib");
    probe.is_dir(&lib).then_some(AnalyzerLayout {
        java: std::path::PathBuf::from("java"),
        jar,
        lib,
    })
}

#[must_use]
pub fn classpath(layout: &AnalyzerLayout) -> OsString {
    let separator = if cfg!(windows) { ";" } else { ":" };
    let mut path = layout.jar.clone().into_os_string();
    path.push(separator);
    path.push(layout.lib.clone().into_os_string());
    path.push(std::path::MAIN_SEPARATOR_STR);
    path.push("*");
    path
}

#[must_use]
pub fn max_heap_arg(override_value: Option<&str>) -> OsString {
    const DEFAULT: &str = "-Xmx2g";
    let Some(value) = override_value else {
        return OsString::from(DEFAULT);
    };
    let (digits, unit) = value.split_at(value.len().saturating_sub(1));
    let plausible = !digits.is_empty()
        && digits.len() <= 5
        && digits.bytes().all(|b| b.is_ascii_digit())
        && matches!(unit, "m" | "M" | "g" | "G");
    if plausible {
        OsString::from(format!("-Xmx{value}"))
    } else {
        OsString::from(DEFAULT)
    }
}

#[must_use]
pub fn analyzer_args(
    layout: &AnalyzerLayout,
    data_dir: &Path,
    max_heap: Option<&str>,
) -> Vec<OsString> {
    let mut args = vec![
        max_heap_arg(max_heap),
        OsString::from("-XX:+ExitOnOutOfMemoryError"),
        OsString::from("-Xss2m"),
        OsString::from("-Dfile.encoding=UTF-8"),
        OsString::from("-Dstdout.encoding=UTF-8"),
        OsString::from("-Dstderr.encoding=UTF-8"),
        OsString::from("-Duser.language=en"),
        OsString::from("-Duser.country=US"),
    ];
    args.push(prefixed("-Djava.io.tmpdir=", data_dir));
    args.push(prefixed("-Dorg.sqlite.tmpdir=", data_dir));
    args.push(OsString::from("-cp"));
    args.push(classpath(layout));
    args.push(OsString::from(MAIN_CLASS));
    args.push(prefixed("--data-dir=", data_dir));
    args
}

fn prefixed(prefix: &str, path: &Path) -> OsString {
    let mut value = OsString::from(prefix);
    value.push(path.as_os_str());
    value
}

pub fn spawn(plan: &crate::analyzer::LaunchPlan) -> Result<std::process::Child, ProtocolError> {
    let mut command = std::process::Command::new(&plan.program);
    command
        .args(&plan.args)
        .current_dir(&plan.working_dir)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());

    command.env_clear();
    for (name, value) in &plan.env {
        command.env(name, value);
    }

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }

    command
        .spawn()
        .map_err(|error| ProtocolError::AnalyzerStartFailed {
            detail: format!("{}: {error}", plan.program.display()),
        })
}

#[cfg(test)]
mod tests {
    use super::{
        AnalyzerLayout, FORBIDDEN_ENV, Probe, analyzer_args, child_env, classpath, max_heap_arg,
        packaged_layout, resolve,
    };
    use std::path::{Path, PathBuf};

    #[derive(Default)]
    struct FakeProbe {
        files: Vec<PathBuf>,
        dirs: Vec<PathBuf>,
    }

    impl FakeProbe {
        fn staged(root: &Path) -> Self {
            let layout = packaged_layout(root);
            Self {
                files: vec![layout.java, layout.jar],
                dirs: vec![layout.lib],
            }
        }
    }

    impl Probe for FakeProbe {
        fn is_file(&self, path: &Path) -> bool {
            self.files.iter().any(|known| known == path)
        }

        fn is_dir(&self, path: &Path) -> bool {
            self.dirs.iter().any(|known| known == path)
        }

        fn find_jar(&self, _dir: &Path) -> Vec<PathBuf> {
            Vec::new()
        }
    }

    #[test]
    fn an_explicit_home_wins_over_the_bundle() {
        let home = Path::new("/opt/strata-dev");
        let probe = FakeProbe::staged(home);
        let layout = resolve(Some(home), Some(Path::new("/apps/Strata")), &probe)
            .expect("o override tem um runtime preparado");
        assert_eq!(layout, packaged_layout(home));
    }

    #[test]
    fn a_home_that_is_set_but_wrong_fails_instead_of_falling_through() {
        let bundle = Path::new("/apps/Strata/resources");
        let probe = FakeProbe::staged(&bundle.join("analyzer"));
        let error = resolve(Some(Path::new("/opt/typo")), Some(bundle), &probe).expect_err(
            "um override definido, mas inválido, interrompe a busca em vez de passar adiante",
        );
        assert_eq!(error.code(), crate::error::ErrorCode::AnalyzerNotFound);
    }

    #[test]
    fn the_bundle_is_used_when_no_override_is_set() {
        let bundle = Path::new("/apps/Strata/resources");
        let probe = FakeProbe::staged(&bundle.join("analyzer"));
        let layout =
            resolve(None, Some(bundle), &probe).expect("o bundle tem um runtime preparado");
        assert_eq!(layout, packaged_layout(&bundle.join("analyzer")));
    }

    #[test]
    fn nothing_staged_and_nothing_to_fall_back_to_is_not_found() {
        let probe = FakeProbe::default();
        let error = resolve(None, Some(Path::new("/apps/Strata/resources")), &probe)
            .expect_err("uma instalação incompleta não é um runtime");
        assert_eq!(error.code(), crate::error::ErrorCode::AnalyzerNotFound);
    }

    #[test]
    fn the_classpath_uses_the_jvm_separator_and_the_jvm_wildcard() {
        let layout = AnalyzerLayout {
            java: PathBuf::from("/r/runtime/bin/java"),
            jar: PathBuf::from("/r/analyzer.jar"),
            lib: PathBuf::from("/r/lib"),
        };
        let path = classpath(&layout);
        let text = path.to_string_lossy().into_owned();
        let separator = if cfg!(windows) { ';' } else { ':' };
        assert_eq!(
            text.matches(separator).count(),
            1,
            "um único separador: {text}"
        );
        assert!(
            text.ends_with('*'),
            "quem expande isto é a JVM, não um shell: {text}"
        );
    }

    #[test]
    fn an_implausible_heap_override_falls_back_instead_of_being_passed_along() {
        assert_eq!(max_heap_arg(None), "-Xmx2g");
        assert_eq!(max_heap_arg(Some("512m")), "-Xmx512m");
        assert_eq!(max_heap_arg(Some("4G")), "-Xmx4G");
        for hostile in [
            "2g -XX:+HeapDumpOnOutOfMemoryError",
            "",
            "g",
            "999999g",
            "2gb",
            "-Xmx2g",
        ] {
            assert_eq!(
                max_heap_arg(Some(hostile)),
                "-Xmx2g",
                "não pode repassar {hostile:?}"
            );
        }
    }

    #[test]
    fn the_argument_vector_carries_the_documented_flags() {
        let layout = packaged_layout(Path::new("/r"));
        let args = analyzer_args(&layout, Path::new("/data"), None);
        let text: Vec<String> = args
            .iter()
            .map(|a| a.to_string_lossy().into_owned())
            .collect();
        for expected in [
            "-Xmx2g",
            "-XX:+ExitOnOutOfMemoryError",
            "-Xss2m",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-Duser.language=en",
            "-Duser.country=US",
            "-Djava.io.tmpdir=/data",
            "-Dorg.sqlite.tmpdir=/data",
            "-cp",
            super::MAIN_CLASS,
            "--data-dir=/data",
        ] {
            assert!(text.iter().any(|a| a == expected), "falta {expected}");
        }
        assert!(!text.iter().any(|a| a.contains("HeapDump")));
    }

    #[test]
    fn the_child_environment_names_nothing_that_can_inject_jvm_flags() {
        let env = child_env(Path::new("/data"));
        let names: Vec<String> = env
            .iter()
            .map(|(k, _)| k.to_string_lossy().into_owned())
            .collect();
        for forbidden in FORBIDDEN_ENV {
            assert!(
                !names.iter().any(|name| name == forbidden),
                "{forbidden} nunca pode entrar no ambiente do processo filho"
            );
        }
        #[cfg(unix)]
        {
            let lang = env.iter().find(|(name, _)| name == "LANG");
            assert!(
                lang.is_some_and(|(_, value)| value == "C.UTF-8"),
                "seja qual for o locale do pai, o LANG do filho é C.UTF-8: {lang:?}"
            );
        }
    }
}
