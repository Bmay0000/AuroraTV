fn main() {
    if let Err(error) = aurora_tv::launch() {
        eprintln!("AuroraTV failed to start: {error}");
        std::process::exit(1);
    }
}
