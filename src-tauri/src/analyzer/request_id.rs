use std::fmt;

const CROCKFORD: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

const PREFIX: &[u8] = b"req_";
const ENCODED_LEN: usize = 26;
const ID_LEN: usize = PREFIX.len() + ENCODED_LEN;

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct RequestId([u8; ID_LEN]);

impl RequestId {
    pub fn generate() -> Result<Self, getrandom::Error> {
        let millis = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis();
        let mut entropy = [0_u8; 10];
        getrandom::fill(&mut entropy)?;
        Ok(Self::from_parts(millis as u64, entropy))
    }

    fn from_parts(millis: u64, entropy: [u8; 10]) -> Self {
        let mut value = u128::from(millis & 0xFFFF_FFFF_FFFF) << 80;
        for (index, byte) in entropy.iter().enumerate() {
            value |= u128::from(*byte) << (8 * (9 - index));
        }
        let mut id = [0_u8; ID_LEN];
        id[..PREFIX.len()].copy_from_slice(PREFIX);
        encode_into(value, &mut id[PREFIX.len()..]);
        Self(id)
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        std::str::from_utf8(&self.0).unwrap_or("req_invalid")
    }
}

fn encode_into(value: u128, out: &mut [u8]) {
    for (index, slot) in out.iter_mut().enumerate().take(ENCODED_LEN) {
        let shift = 5 * (ENCODED_LEN - 1 - index);
        *slot = CROCKFORD[((value >> shift) & 0x1F) as usize];
    }
}

impl fmt::Debug for RequestId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl fmt::Display for RequestId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::{ENCODED_LEN, RequestId, encode_into};

    fn encode(value: u128) -> String {
        let mut out = [0_u8; ENCODED_LEN];
        encode_into(value, &mut out);
        String::from_utf8(out.to_vec()).expect("ascii")
    }

    #[test]
    fn crockford_encodes_hand_computed_vectors() {
        assert_eq!(encode(0), "0".repeat(26));
        assert_eq!(encode(1), format!("{}1", "0".repeat(25)));
        assert_eq!(encode(31), format!("{}Z", "0".repeat(25)));
        assert_eq!(encode(32), format!("{}10", "0".repeat(24)));
        assert_eq!(encode(u128::MAX), format!("7{}", "Z".repeat(25)));
    }

    #[test]
    fn request_id_matches_java_grammar() {
        let allowed = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        assert!(!allowed.contains(['I', 'L', 'O', 'U']));

        let mut seen = std::collections::HashSet::new();
        for _ in 0..10_000 {
            let id = RequestId::generate().expect("um CSPRNG precisa estar disponível");
            let text = id.as_str().to_owned();
            assert_eq!(text.len(), 30, "req_ mais 26 dígitos: {text}");
            let body = text.strip_prefix("req_").expect("prefixo");
            assert!(
                body.chars().all(|c| allowed.contains(c)),
                "fora do alfabeto: {text}"
            );
            assert!(
                body.as_bytes()[0] <= b'7',
                "o primeiro dígito carrega três bits: {text}"
            );
            assert!(seen.insert(text.clone()), "id duplicado: {text}");
        }
    }
}
