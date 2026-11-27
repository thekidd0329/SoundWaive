//! Lyrics -> phoneme sequence. Text cleaning replicates upstream `make_phoneme_and_word_list`;
//! pronunciations come from the CMU Pronouncing Dictionary (first variant, stress removed),
//! loaded at runtime with [init_cmudict].
//!
//! Out-of-vocabulary (OOV) words, in order:
//!  1. user overrides (`word<TAB>PH PH ...`), which always win
//!  2. CMU dict exact match
//!  3. simple normalisations: leading/trailing apostrophes, `-in'` -> `-ing`, `'s`/`s'` -> base + Z/S
//!  4. split into the fewest dictionary words (each piece >= 2 letters), e.g. "neverending"
//!  5. digits are spelled out ("2" -> "two")
//!  6. naive English letter-to-sound rules (last resort, marked `rules`, treat as low confidence)
use std::collections::HashMap;
use std::io::Read;
use std::sync::OnceLock;

pub const PHONEMES: [&str; 44] = [
    "#", "$", "%", ">", "-", "AA", "AE", "AH", "AO", "AW", "AY", "B", "CH", "D", "DH", "EH", "ER",
    "EY", "F", "G", "HH", "IH", "IY", "JH", "K", "L", "M", "N", "NG", "OW", "OY", "P", "R", "S",
    "SH", "T", "TH", "UH", "UW", "V", "W", "Y", "Z", "ZH",
];
pub const SPACE: usize = 3;

pub fn phoneme_index(p: &str) -> Option<usize> {
    PHONEMES.iter().position(|x| *x == p)
}

static CMU: OnceLock<HashMap<String, String>> = OnceLock::new();

/// Loads the CMU Pronouncing Dictionary (`word<TAB>PH PH ...`, stress removed), gzip
/// compressed or plain text. The dictionary is not compiled into the library: the app ships
/// it as an asset (with its BSD-style notice) and loads it once before the first alignment.
/// Later calls are ignored.
pub fn init_cmudict(bytes: &[u8]) -> anyhow::Result<()> {
    if CMU.get().is_some() {
        return Ok(());
    }
    let mut s = String::new();
    if bytes.starts_with(&[0x1f, 0x8b]) {
        flate2::read::GzDecoder::new(bytes).read_to_string(&mut s)?;
    } else {
        s = String::from_utf8(bytes.to_vec())?;
    }
    let map: HashMap<String, String> = s
        .lines()
        .filter_map(|l| l.split_once('\t'))
        .map(|(w, p)| (w.to_string(), p.to_string()))
        .collect();
    anyhow::ensure!(map.len() > 1000, "the pronunciation dictionary looks empty");
    let _ = CMU.set(map);
    Ok(())
}

/// True once [init_cmudict] has loaded the dictionary.
pub fn has_cmudict() -> bool {
    CMU.get().is_some()
}

/// The loaded dictionary. Panics before [init_cmudict]; [crate::Aligner::align] checks first.
pub fn cmudict() -> &'static HashMap<String, String> {
    CMU.get().expect("call g2p::init_cmudict before aligning")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub enum G2pSource {
    Override,
    Dict,
    Normalized,
    Split,
    Digits,
    Rules,
}

#[derive(Debug, Clone)]
pub struct LyricWord {
    pub word: String,
    pub line: usize,
    pub phonemes: Vec<String>,
    pub source: G2pSource,
}

pub fn parse_overrides(text: &str) -> HashMap<String, String> {
    text.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .filter_map(|l| {
            l.split_once('\t')
                .or_else(|| l.split_once(char::is_whitespace))
        })
        .map(|(w, p)| {
            (
                w.trim().to_lowercase().replace('\u{2019}', "'"),
                p.trim().to_uppercase(),
            )
        })
        .collect()
}

/// Same cleaning as upstream: lowercase, ’ -> ', keep only alphanumerics, apostrophes and spaces
/// (so hyphens are dropped and "heart-beat" becomes "heartbeat"), split on spaces.
pub fn tokenize(lyrics: &str) -> Vec<(String, usize)> {
    let mut out = Vec::new();
    let mut line_no = 0;
    for line in lyrics.lines() {
        let line = line.to_lowercase().replace('\u{2019}', "'");
        let clean: String = line
            .chars()
            .filter(|c| c.is_alphanumeric() || *c == '\'' || *c == ' ')
            .collect();
        if clean.trim().is_empty() {
            continue;
        }
        let mut any = false;
        for w in clean.split(' ') {
            if !w.is_empty() {
                out.push((w.to_string(), line_no));
                any = true;
            }
        }
        if any {
            line_no += 1;
        }
    }
    out
}

pub fn words_to_phonemes(lyrics: &str, overrides: &HashMap<String, String>) -> Vec<LyricWord> {
    tokenize(lyrics)
        .into_iter()
        .map(|(w, line)| {
            let (ph, source) = lookup(&w, overrides);
            LyricWord {
                word: w,
                line,
                phonemes: ph.split_whitespace().map(String::from).collect(),
                source,
            }
        })
        .collect()
}

pub fn lookup(w: &str, ov: &HashMap<String, String>) -> (String, G2pSource) {
    if let Some(p) = ov.get(w) {
        return (p.clone(), G2pSource::Override);
    }
    let d = cmudict();
    if let Some(p) = d.get(w) {
        return (p.clone(), G2pSource::Dict);
    }
    if let Some(p) = normalized(w, d) {
        return (p, G2pSource::Normalized);
    }
    let bare: String = w.chars().filter(|c| *c != '\'').collect();
    if bare.chars().all(|c| c.is_ascii_digit()) && !bare.is_empty() {
        let p: Vec<&str> = bare
            .chars()
            .map(|c| d[DIGITS[c as usize - '0' as usize]].as_str())
            .collect();
        return (p.join(" "), G2pSource::Digits);
    }
    if let Some(p) = split(&bare, d) {
        return (p, G2pSource::Split);
    }
    (rules(&bare), G2pSource::Rules)
}

const DIGITS: [&str; 10] = [
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
];

fn normalized(w: &str, d: &HashMap<String, String>) -> Option<String> {
    let t = w.trim_matches('\'');
    if t != w {
        if let Some(p) = d.get(t) {
            return Some(p.clone());
        }
    }
    if let Some(stem) = t.strip_suffix("in'").or_else(|| t.strip_suffix("in")) {
        if t.ends_with("in'") || !d.contains_key(t) {
            if let Some(p) = d.get(&format!("{stem}ing")) {
                return Some(p.clone());
            }
        }
    }
    if let Some(stem) = t.strip_suffix("'s").or_else(|| t.strip_suffix("s'")) {
        if let Some(p) = d.get(stem) {
            let last = p.split_whitespace().last().unwrap_or("");
            let suf = if ["S", "Z", "SH", "ZH", "CH", "JH"].contains(&last) {
                " IH Z"
            } else if ["P", "T", "K", "F", "TH"].contains(&last) {
                " S"
            } else {
                " Z"
            };
            return Some(format!("{p}{suf}"));
        }
    }
    let noap: String = t.chars().filter(|c| *c != '\'').collect();
    if noap != t {
        if let Some(p) = d.get(&noap) {
            return Some(p.clone());
        }
    }
    None
}

/// Fewest-pieces segmentation into dictionary words (pieces >= 2 letters, max 4 pieces).
fn split(w: &str, d: &HashMap<String, String>) -> Option<String> {
    let c: Vec<char> = w.chars().collect();
    let n = c.len();
    if n < 4 {
        return None;
    }
    let mut best: Vec<Option<(usize, usize)>> = vec![None; n + 1]; // (pieces, prev)
    best[0] = Some((0, 0));
    for end in 2..=n {
        for start in 0..=end.saturating_sub(2) {
            if let Some((k, _)) = best[start] {
                let piece: String = c[start..end].iter().collect();
                if d.contains_key(&piece) && best[end].map_or(true, |(bk, _)| k + 1 < bk) {
                    best[end] = Some((k + 1, start));
                }
            }
        }
    }
    let (k, _) = best[n]?;
    if k > 4 {
        return None;
    }
    let mut pieces = Vec::new();
    let mut end = n;
    while end > 0 {
        let (_, start) = best[end].unwrap();
        pieces.push(d[&c[start..end].iter().collect::<String>()].clone());
        end = start;
    }
    pieces.reverse();
    Some(pieces.join(" "))
}

/// Very small letter-to-sound fallback. Crude on purpose: it only has to give the aligner a
/// plausible phoneme count and rough classes. Words using it are flagged in the output.
pub fn rules(w: &str) -> String {
    const MULTI: &[(&str, &str)] = &[
        ("tion", "SH AH N"),
        ("sion", "ZH AH N"),
        ("ough", "AH F"),
        ("igh", "AY"),
        ("eigh", "EY"),
        ("tch", "CH"),
        ("dge", "JH"),
        ("sch", "S K"),
        ("ch", "CH"),
        ("sh", "SH"),
        ("th", "TH"),
        ("ph", "F"),
        ("wh", "W"),
        ("ck", "K"),
        ("ng", "NG"),
        ("qu", "K W"),
        ("kn", "N"),
        ("wr", "R"),
        ("gh", "G"),
        ("ee", "IY"),
        ("ea", "IY"),
        ("oo", "UW"),
        ("ou", "AW"),
        ("ow", "OW"),
        ("oi", "OY"),
        ("oy", "OY"),
        ("ai", "EY"),
        ("ay", "EY"),
        ("au", "AO"),
        ("aw", "AO"),
        ("ie", "IY"),
        ("ei", "EY"),
        ("ey", "IY"),
        ("oa", "OW"),
        ("ue", "UW"),
        ("ew", "UW"),
        ("ar", "AA R"),
        ("er", "ER"),
        ("ir", "ER"),
        ("ur", "ER"),
        ("or", "AO R"),
        ("ll", "L"),
        ("ss", "S"),
        ("tt", "T"),
        ("pp", "P"),
        ("bb", "B"),
        ("dd", "D"),
        ("ff", "F"),
        ("gg", "G"),
        ("mm", "M"),
        ("nn", "N"),
        ("rr", "R"),
        ("zz", "Z"),
    ];
    const SINGLE: &[(char, &str)] = &[
        ('a', "AE"),
        ('b', "B"),
        ('c', "K"),
        ('d', "D"),
        ('e', "EH"),
        ('f', "F"),
        ('g', "G"),
        ('h', "HH"),
        ('i', "IH"),
        ('j', "JH"),
        ('k', "K"),
        ('l', "L"),
        ('m', "M"),
        ('n', "N"),
        ('o', "AA"),
        ('p', "P"),
        ('q', "K"),
        ('r', "R"),
        ('s', "S"),
        ('t', "T"),
        ('u', "AH"),
        ('v', "V"),
        ('w', "W"),
        ('x', "K S"),
        ('y', "IY"),
        ('z', "Z"),
    ];
    let s: Vec<char> = w
        .to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_alphabetic())
        .collect();
    let mut out: Vec<&str> = Vec::new();
    let mut i = 0;
    'outer: while i < s.len() {
        // silent final e
        if s[i] == 'e' && i == s.len() - 1 && i > 1 {
            break;
        }
        for (g, p) in MULTI {
            let gl = g.len();
            if i + gl <= s.len() && s[i..i + gl].iter().copied().eq(g.chars()) {
                out.push(p);
                i += gl;
                continue 'outer;
            }
        }
        if let Some((_, p)) = SINGLE.iter().find(|(c, _)| *c == s[i]) {
            out.push(p);
        }
        i += 1;
    }
    if out.is_empty() {
        out.push("AH");
    }
    out.join(" ")
}
