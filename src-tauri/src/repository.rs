use std::borrow::Cow;
use std::collections::HashMap;
use std::fmt;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::RwLock;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::error::ProtocolError;

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

const PREFIX: &[u8] = b"repo_";
const ENTROPY_BYTES: usize = 16;
const ENCODED_LEN: usize = 22;
const HANDLE_LEN: usize = PREFIX.len() + ENCODED_LEN;

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct RepositoryHandle([u8; HANDLE_LEN]);

impl RepositoryHandle {
    pub fn generate() -> Result<Self, getrandom::Error> {
        let mut entropy = [0_u8; ENTROPY_BYTES];
        getrandom::fill(&mut entropy)?;
        Ok(Self::from_entropy(&entropy))
    }

    fn from_entropy(entropy: &[u8; ENTROPY_BYTES]) -> Self {
        let mut handle = [0_u8; HANDLE_LEN];
        handle[..PREFIX.len()].copy_from_slice(PREFIX);
        encode_into(entropy, &mut handle[PREFIX.len()..]);
        Self(handle)
    }

    #[must_use]
    pub fn parse(text: &str) -> Option<Self> {
        let bytes = text.as_bytes();
        if bytes.len() != HANDLE_LEN || !bytes.starts_with(PREFIX) {
            return None;
        }
        if !bytes[PREFIX.len()..]
            .iter()
            .all(|byte| ALPHABET.contains(byte))
        {
            return None;
        }
        let mut handle = [0_u8; HANDLE_LEN];
        handle.copy_from_slice(bytes);
        Some(Self(handle))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        std::str::from_utf8(&self.0).unwrap_or("repo_invalid")
    }
}

fn encode_into(entropy: &[u8; ENTROPY_BYTES], out: &mut [u8]) {
    let mut written = 0;
    for group in entropy[..15].as_chunks::<3>().0 {
        let packed = (u32::from(group[0]) << 16) | (u32::from(group[1]) << 8) | u32::from(group[2]);
        out[written] = ALPHABET[(packed >> 18) as usize & 63];
        out[written + 1] = ALPHABET[(packed >> 12) as usize & 63];
        out[written + 2] = ALPHABET[(packed >> 6) as usize & 63];
        out[written + 3] = ALPHABET[packed as usize & 63];
        written += 4;
    }
    let tail = u32::from(entropy[15]);
    out[written] = ALPHABET[(tail >> 2) as usize & 63];
    out[written + 1] = ALPHABET[(tail << 4) as usize & 63];
}

impl fmt::Debug for RepositoryHandle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl fmt::Display for RepositoryHandle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl Serialize for RepositoryHandle {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for RepositoryHandle {
    // String própria, e não emprestada: o Tauri desserializa os argumentos dos comandos a partir
    // de serde_json::Value, que não consegue entregar um &'de str.
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let text = String::deserialize(deserializer)?;
        Self::parse(&text).ok_or_else(|| {
            serde::de::Error::custom("esperava repo_ seguido de 22 caracteres base64url")
        })
    }
}

/// Caminho canônico do repositório no envelope do analisador. O campo é privado e só
/// [`RepositoryRegistry::resolve`] cria um, então dado vindo da interface nunca vira caminho:
///
/// ```compile_fail
/// use strata_lib::repository::RepositoryPath;
/// let smuggled = RepositoryPath(String::from("/etc/passwd"));
/// ```
///
/// O caso positivo precisa compilar, para que o de cima não passe por um erro de digitação:
///
/// ```
/// use strata_lib::repository::RepositoryRegistry;
/// let registry = RepositoryRegistry::default();
/// let opened = registry.mint(std::env::temp_dir()).expect("um CSPRNG precisa estar disponível");
/// let path = registry.resolve(opened.handle).expect("o handle acabou de ser criado");
/// assert!(!path.as_str().is_empty());
/// ```
#[derive(Debug)]
pub struct RepositoryPath(String);

impl RepositoryPath {
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OpenedRepository {
    pub handle: RepositoryHandle,
    pub display_name: String,
}

struct Entry {
    wire: String,
    display_name: String,
}

#[derive(Default)]
pub struct RepositoryRegistry {
    entries: RwLock<HashMap<RepositoryHandle, Entry>>,
}

impl RepositoryRegistry {
    pub fn mint(&self, canonical: PathBuf) -> Result<OpenedRepository, ProtocolError> {
        let handle = RepositoryHandle::generate()
            .map_err(|error| ProtocolError::internal(format!("sem CSPRNG: {error}")))?;
        let wire = canonical
            .to_str()
            .ok_or_else(|| ProtocolError::internal("o caminho canônico não é UTF-8 válido"))?;
        let wire = strip_verbatim(wire).into_owned();
        let display_name = display_name(&canonical);
        let mut entries = self.write();
        entries.insert(
            handle,
            Entry {
                wire,
                display_name: display_name.clone(),
            },
        );
        Ok(OpenedRepository {
            handle,
            display_name,
        })
    }

    pub fn resolve(&self, handle: RepositoryHandle) -> Result<RepositoryPath, ProtocolError> {
        self.read()
            .get(&handle)
            .map(|entry| RepositoryPath(entry.wire.clone()))
            .ok_or(ProtocolError::HandleUnknown)
    }

    #[must_use]
    pub fn display_name_of(&self, handle: RepositoryHandle) -> Option<String> {
        self.read()
            .get(&handle)
            .map(|entry| entry.display_name.clone())
    }

    pub fn close(&self, handle: RepositoryHandle) -> bool {
        self.write().remove(&handle).is_some()
    }

    #[must_use]
    pub fn known_roots(&self) -> Vec<String> {
        self.read()
            .values()
            .map(|entry| entry.wire.clone())
            .collect()
    }

    #[must_use]
    pub fn len(&self) -> usize {
        self.read().len()
    }

    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    fn read(&self) -> std::sync::RwLockReadGuard<'_, HashMap<RepositoryHandle, Entry>> {
        self.entries
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn write(&self) -> std::sync::RwLockWriteGuard<'_, HashMap<RepositoryHandle, Entry>> {
        self.entries
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

pub fn canonicalize(picked: &Path) -> Result<PathBuf, ProtocolError> {
    std::fs::canonicalize(picked).map_err(|error| canonicalize_failure(&error))
}

fn canonicalize_failure(error: &io::Error) -> ProtocolError {
    match error.kind() {
        io::ErrorKind::PermissionDenied => ProtocolError::root_unreadable(error),
        _ => ProtocolError::path_changed(error),
    }
}

#[must_use]
pub fn strip_verbatim(text: &str) -> Cow<'_, str> {
    // UNC primeiro: `\\?\UNC\server\share` também começa com `\\?\`, e remover esse prefixo
    // deixaria o caminho relativo `UNC\server\share`.
    if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
        return Cow::Owned(format!(r"\\{rest}"));
    }
    text.strip_prefix(r"\\?\")
        .map_or(Cow::Borrowed(text), Cow::Borrowed)
}

#[must_use]
pub fn display_name(path: &Path) -> String {
    path.file_name().map_or_else(
        || path.to_string_lossy().into_owned(),
        |name| name.to_string_lossy().into_owned(),
    )
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::path::Path;

    use super::{canonicalize, canonicalize_failure};
    use crate::error::ErrorCode;

    fn code_for(kind: io::ErrorKind) -> ErrorCode {
        canonicalize_failure(&io::Error::from(kind)).code()
    }

    #[test]
    fn a_folder_that_no_longer_resolves_changed_and_never_needs_a_reinstall() {
        for kind in [
            io::ErrorKind::NotFound,
            io::ErrorKind::NotADirectory,
            io::ErrorKind::InvalidFilename,
            io::ErrorKind::Other,
        ] {
            assert_eq!(code_for(kind), ErrorCode::PathChanged, "{kind:?}");
        }
    }

    #[test]
    fn a_folder_the_os_will_not_list_is_unreadable() {
        assert_eq!(
            code_for(io::ErrorKind::PermissionDenied),
            ErrorCode::RootUnreadable
        );
    }

    #[test]
    fn the_refusal_is_the_shells_and_puts_neither_path_nor_os_text_on_the_wire() {
        let picked = Path::new("/strata-nowhere/repositorio-sumido");
        let refused = canonicalize(picked).expect_err("a pasta não existe");

        assert_eq!(refused.code(), ErrorCode::PathChanged);
        assert_eq!(
            serde_json::to_string(&refused).expect("serializa"),
            r#"{"code":"REPO_PATH_CHANGED","origin":"shell"}"#
        );
        assert!(
            !refused.to_string().contains("repositorio-sumido"),
            "a linha de log não pode carregar o caminho: {refused}"
        );
    }

    #[test]
    fn the_unreadable_refusal_serializes_with_no_params() {
        let refused = canonicalize_failure(&io::Error::from(io::ErrorKind::PermissionDenied));

        assert_eq!(
            serde_json::to_string(&refused).expect("serializa"),
            r#"{"code":"REPO_ROOT_UNREADABLE","origin":"shell"}"#
        );
    }
}
