fn main() {
    let a: Vec<_> = std::env::args().collect();
    match crownpulse_engine::analyze_pcm(std::path::Path::new(&a[1]), std::path::Path::new(&a[2])) {
        Ok(r) => println!(
            "{} frames, {:.2} BPM, {} notes, {} beats",
            r.frames, r.bpm, r.notes, r.beats
        ),
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(1)
        }
    }
}
