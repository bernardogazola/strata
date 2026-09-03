use serde::{Deserialize, Serialize};
use serde_json::value::RawValue;

use crate::error::ProtocolError;

const ID_SCAN_PREFIX: usize = 512;

const REUSE_CAPACITY: usize = 64 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Frame {
    Line,
    Oversized { received_bytes: u64 },
    Eof,
}

pub fn read_frame<R: std::io::BufRead>(
    reader: &mut R,
    max_bytes: usize,
    buf: &mut Vec<u8>,
) -> std::io::Result<Frame> {
    if buf.capacity() > REUSE_CAPACITY {
        *buf = Vec::new();
    }
    buf.clear();

    let mut counted: u64 = 0;
    let mut over = false;

    loop {
        let chunk = match reader.fill_buf() {
            Ok(chunk) => chunk,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        };
        if chunk.is_empty() {
            if counted == 0 {
                return Ok(Frame::Eof);
            }
            return Ok(finish(over, counted, max_bytes));
        }

        let (usable, consumed, terminated) = match chunk.iter().position(|byte| *byte == b'\n') {
            Some(position) => (position, position + 1, true),
            None => (chunk.len(), chunk.len(), false),
        };

        if !over {
            let room = max_bytes.saturating_sub(buf.len());
            buf.extend_from_slice(&chunk[..usable.min(room)]);
        }
        counted += usable as u64;
        if !over && counted > max_bytes as u64 {
            over = true;
            buf.truncate(ID_SCAN_PREFIX.min(buf.len()));
        }
        reader.consume(consumed);

        if terminated {
            return Ok(finish(over, counted, max_bytes));
        }
    }
}

const fn finish(over: bool, counted: u64, max_bytes: usize) -> Frame {
    if over || counted > max_bytes as u64 {
        Frame::Oversized {
            received_bytes: counted,
        }
    } else {
        Frame::Line
    }
}

#[must_use]
pub fn scan_id(prefix: &[u8]) -> Option<&str> {
    let needle = b"\"id\":\"";
    let start = prefix
        .windows(needle.len())
        .position(|window| window == needle)?
        + needle.len();
    let rest = &prefix[start..];
    let end = rest.iter().position(|byte| *byte == b'"')?;
    std::str::from_utf8(&rest[..end]).ok()
}

#[derive(Debug, Deserialize)]
pub struct Incoming<'a> {
    pub protocol: u32,
    pub kind: &'a str,
    pub id: Option<&'a str>,
    pub event: Option<&'a str>,
    pub ok: Option<bool>,
    #[serde(borrow)]
    pub payload: Option<&'a RawValue>,
    #[serde(borrow)]
    pub error: Option<&'a RawValue>,
}

pub fn parse(line: &[u8]) -> Result<Incoming<'_>, ProtocolError> {
    serde_json::from_slice(line).map_err(|error| ProtocolError::malformed(&error))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Outgoing<'a> {
    protocol: u32,
    kind: &'static str,
    id: &'a str,
    method: &'a str,
    payload: &'a RawValue,
    #[serde(skip_serializing_if = "Option::is_none")]
    repository_path: Option<&'a str>,
}

pub fn encode_request(
    id: &str,
    method: &str,
    payload: &RawValue,
    repository_path: Option<&str>,
) -> Result<Vec<u8>, ProtocolError> {
    let envelope = Outgoing {
        protocol: super::PROTOCOL_VERSION,
        kind: "request",
        id,
        method,
        payload,
        repository_path,
    };
    let mut line = serde_json::to_vec(&envelope)
        .map_err(|error| ProtocolError::internal(format!("envelope: {error}")))?;
    if line.contains(&b'\n') {
        return Err(ProtocolError::internal(
            "o payload contém uma quebra de linha literal e dividiria o frame",
        ));
    }
    line.push(b'\n');
    Ok(line)
}

#[cfg(test)]
mod tests {
    use super::{Frame, encode_request, parse, read_frame, scan_id};
    use serde_json::value::RawValue;
    use std::io::{BufReader, Cursor};

    const CAP: usize = 8;

    fn read_all(input: &[u8], max: usize) -> Vec<(Frame, String)> {
        let mut reader = Cursor::new(input.to_vec());
        let mut buf = Vec::new();
        let mut out = Vec::new();
        loop {
            let frame = read_frame(&mut reader, max, &mut buf).expect("um cursor não falha");
            out.push((frame, String::from_utf8_lossy(&buf).into_owned()));
            if frame == Frame::Eof {
                return out;
            }
        }
    }

    #[test]
    fn frame_at_cap_is_legal_and_one_over_is_not() {
        let input = [b"xxxxxxxx\n".as_slice(), b"yyyyyyyyy\n", b"after\n"].concat();
        let frames = read_all(&input, CAP);
        assert_eq!(frames[0].0, Frame::Line);
        assert_eq!(frames[0].1, "xxxxxxxx");
        assert_eq!(frames[1].0, Frame::Oversized { received_bytes: 9 });
        assert_eq!(frames[2].0, Frame::Line);
        assert_eq!(frames[2].1, "after");
        assert_eq!(frames[3].0, Frame::Eof);
    }

    #[test]
    fn frame_at_the_real_cap_is_legal_and_one_byte_over_is_not() {
        let cap = crate::analyzer::AnalyzerConfig::default().max_frame_bytes;
        assert_eq!(cap, 16_777_216);

        let mut legal = vec![b'x'; cap];
        legal.push(b'\n');
        assert_eq!(read_all(&legal, cap)[0].0, Frame::Line);

        let mut over = vec![b'x'; cap + 1];
        over.push(b'\n');
        assert_eq!(
            read_all(&over, cap)[0].0,
            Frame::Oversized {
                received_bytes: cap as u64 + 1
            }
        );
    }

    #[test]
    fn oversized_count_is_exact_across_many_chunks() {
        let mut input = vec![b'z'; 100];
        input.push(b'\n');
        let mut reader = BufReader::with_capacity(1, Cursor::new(input));
        let mut buf = Vec::new();
        let frame = read_frame(&mut reader, CAP, &mut buf).expect("um cursor não falha");
        assert_eq!(
            frame,
            Frame::Oversized {
                received_bytes: 100
            }
        );
    }

    #[test]
    fn a_lone_carriage_return_does_not_split_a_frame() {
        let frames = read_all(b"{\"a\":\"b\rc\"}\nnext\n", 64);
        assert_eq!(frames[0].0, Frame::Line);
        assert!(frames[0].1.contains('\r'));
        assert_eq!(frames[1].1, "next");
    }

    #[test]
    fn a_final_line_without_a_terminator_is_still_a_frame() {
        let frames = read_all(b"one\ntail", 64);
        assert_eq!(frames[0].1, "one");
        assert_eq!(frames[1].0, Frame::Line);
        assert_eq!(frames[1].1, "tail");
        assert_eq!(frames[2].0, Frame::Eof);
    }

    #[test]
    fn an_empty_stream_is_eof_not_an_empty_frame() {
        assert_eq!(read_all(b"", 64)[0].0, Frame::Eof);
    }

    #[test]
    fn scan_id_recovers_a_caller_from_an_oversized_prefix() {
        let prefix = br#"{"id":"req_01JQ8ZA1B2C3D4E5F6G7H8J9K0","kind":"response","ok":true,"#;
        assert_eq!(scan_id(prefix), Some("req_01JQ8ZA1B2C3D4E5F6G7H8J9K0"));
        assert_eq!(scan_id(b"{\"kind\":\"event\""), None);
    }

    #[test]
    fn incoming_borrows_the_payload_and_never_parses_it() {
        let line = br#"{"id":"req_X","kind":"response","ok":true,"payload":{"deep":{"a":[1,2]}},"protocol":1}"#;
        let incoming = parse(line).expect("frame válido");
        assert_eq!(incoming.protocol, 1);
        assert_eq!(incoming.kind, "response");
        assert_eq!(incoming.id, Some("req_X"));
        assert_eq!(incoming.ok, Some(true));
        assert!(incoming.error.is_none());
        assert_eq!(
            incoming.payload.expect("payload presente").get(),
            r#"{"deep":{"a":[1,2]}}"#
        );
    }

    #[test]
    fn an_unknown_inbound_key_is_tolerated() {
        let line = br#"{"protocol":1,"kind":"event","event":"analysis.progress","payload":{},"futureKey":true}"#;
        let incoming = parse(line).expect("chaves desconhecidas não quebram o envelope");
        assert_eq!(incoming.event, Some("analysis.progress"));
    }

    #[test]
    fn a_request_carries_exactly_the_keys_the_analyzer_accepts() {
        let payload = RawValue::from_string(r#"{"scope":"FULL"}"#.to_owned()).expect("json");
        let line = encode_request("req_X", "analysis.start", &payload, Some("/tmp/repo"))
            .expect("codifica");
        assert_eq!(
            String::from_utf8(line).expect("utf8"),
            "{\"protocol\":1,\"kind\":\"request\",\"id\":\"req_X\",\"method\":\"analysis.start\",\"payload\":{\"scope\":\"FULL\"},\"repositoryPath\":\"/tmp/repo\"}\n"
        );

        let empty = RawValue::from_string("{}".to_owned()).expect("json");
        let line = encode_request("req_Y", "system.ping", &empty, None).expect("codifica");
        assert_eq!(
            String::from_utf8(line).expect("utf8"),
            "{\"protocol\":1,\"kind\":\"request\",\"id\":\"req_Y\",\"method\":\"system.ping\",\"payload\":{}}\n"
        );
    }

    #[test]
    fn a_payload_with_a_raw_newline_is_refused_rather_than_split() {
        let pretty = RawValue::from_string("{\n  \"a\": 1\n}".to_owned()).expect("json");
        let error = encode_request("req_X", "system.ping", &pretty, None)
            .expect_err("um frame que se dividiria não pode ser escrito");
        assert_eq!(error.code(), crate::error::ErrorCode::InternalUnexpected);
    }
}
