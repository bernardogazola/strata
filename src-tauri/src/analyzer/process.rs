use std::ffi::OsString;
use std::path::Path;

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
        env.push((OsString::from("LANG"), OsString::from("C.UTF-8")));
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
