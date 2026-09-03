use std::collections::VecDeque;

pub(crate) struct Ring {
    lines: VecDeque<String>,
    bytes: usize,
    max_bytes: usize,
    max_lines: usize,
    max_line_bytes: usize,
    truncated: bool,
}

impl Ring {
    pub(crate) const fn new(max_bytes: usize, max_lines: usize, max_line_bytes: usize) -> Self {
        Self {
            lines: VecDeque::new(),
            bytes: 0,
            max_bytes,
            max_lines,
            max_line_bytes,
            truncated: false,
        }
    }

    pub(crate) fn push(&mut self, mut line: String) {
        if line.len() > self.max_line_bytes {
            let mut cut = self.max_line_bytes;
            while cut > 0 && !line.is_char_boundary(cut) {
                cut -= 1;
            }
            line.truncate(cut);
            line.push_str("…truncada");
            self.truncated = true;
        }
        self.bytes += line.len();
        self.lines.push_back(line);
        while self.lines.len() > self.max_lines || self.bytes > self.max_bytes {
            match self.lines.pop_front() {
                Some(dropped) => {
                    self.bytes -= dropped.len();
                    self.truncated = true;
                }
                None => break,
            }
        }
    }

    pub(crate) fn snapshot(&self) -> (Vec<String>, bool) {
        (self.lines.iter().cloned().collect(), self.truncated)
    }
}

#[cfg(test)]
mod tests {
    use super::Ring;

    #[test]
    fn the_ring_keeps_the_newest_lines_and_says_it_dropped_some() {
        let mut ring = Ring::new(64 * 1024, 3, 8_192);
        for n in 0..10 {
            ring.push(format!("line {n}"));
        }
        let (lines, truncated) = ring.snapshot();
        assert_eq!(lines, vec!["line 7", "line 8", "line 9"]);
        assert!(truncated);
    }

    #[test]
    fn a_byte_budget_binds_before_the_line_count() {
        let mut ring = Ring::new(20, 1_000, 8_192);
        ring.push("a".repeat(15));
        ring.push("b".repeat(15));
        let (lines, truncated) = ring.snapshot();
        assert_eq!(lines.len(), 1);
        assert!(lines[0].starts_with('b'));
        assert!(
            truncated,
            "descartar uma linha pelo limite de bytes também é truncamento"
        );
    }

    #[test]
    fn an_over_long_line_is_truncated_rather_than_dropped() {
        let mut ring = Ring::new(64 * 1024, 10, 16);
        ring.push("x".repeat(500));
        let (lines, truncated) = ring.snapshot();
        assert_eq!(lines.len(), 1, "a linha sobrevive, encurtada");
        assert!(lines[0].ends_with("…truncada"));
        assert!(truncated);
    }

    #[test]
    fn truncation_lands_on_a_character_boundary() {
        let mut ring = Ring::new(64 * 1024, 10, 5);
        ring.push("ãããã".to_owned());
        let (lines, truncated) = ring.snapshot();
        assert!(lines[0].ends_with("…truncada"));
        assert!(truncated);
    }
}
