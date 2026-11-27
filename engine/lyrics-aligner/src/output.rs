//! JSON and enhanced-LRC writers.
use crate::WordTiming;
use std::fmt::Write;

pub fn lrc_time(t: f64) -> String {
    let cs = (t.max(0.0) * 100.0).round() as u64;
    format!("{:02}:{:02}.{:02}", cs / 6000, (cs / 100) % 60, cs % 100)
}

/// Enhanced LRC: `[mm:ss.xx] <mm:ss.xx> word <mm:ss.xx> word ... <mm:ss.xx>` one lyric line per row.
/// Words are written with the original line's spelling where possible (`original_lines`), else cleaned.
pub fn enhanced_lrc(
    words: &[WordTiming],
    original_lines: &[String],
    tags: &[(&str, &str)],
) -> String {
    let mut s = String::new();
    for (k, v) in tags {
        let _ = writeln!(s, "[{k}:{v}]");
    }
    let mut i = 0;
    while i < words.len() {
        let line = words[i].line;
        let j = words[i..]
            .iter()
            .position(|w| w.line != line)
            .map_or(words.len(), |p| i + p);
        let orig: Vec<&str> = original_lines
            .get(line)
            .map(|l| l.split_whitespace().collect())
            .unwrap_or_default();
        let use_orig = orig.len() == j - i;
        let _ = write!(s, "[{}]", lrc_time(words[i].onset));
        for (k, w) in words[i..j].iter().enumerate() {
            let text = if use_orig { orig[k] } else { w.word.as_str() };
            let _ = write!(s, " <{}> {}", lrc_time(w.onset), text);
        }
        let _ = writeln!(s, " <{}>", lrc_time(words[j - 1].offset));
        i = j;
    }
    s
}

/// Non-empty lyric lines in the same numbering `g2p::tokenize` uses.
pub fn lyric_lines(lyrics: &str) -> Vec<String> {
    lyrics
        .lines()
        .filter(|l| {
            let c: String = l
                .to_lowercase()
                .replace('\u{2019}', "'")
                .chars()
                .filter(|c| c.is_alphanumeric() || *c == '\'' || *c == ' ')
                .collect();
            c.split(' ').any(|w| !w.is_empty())
        })
        .map(|l| l.trim().to_string())
        .collect()
}
