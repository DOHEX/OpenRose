use std::{
    env,
    io::{self, BufRead},
    time::{Duration, Instant},
};
use windows::Win32::System::WinRT::{RoInitialize, RO_INIT_MULTITHREADED};

mod facts;
mod gatt;
mod scan;
type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

fn clean(text: impl ToString) -> String {
    text.to_string().replace(['\r', '\n', '\t'], " ")
}

fn main() -> Result<()> {
    unsafe { RoInitialize(RO_INIT_MULTITHREADED)? };
    let args: Vec<String> = env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("facts") => {
            // A pipe EOF means the desktop process exited, even if its normal shutdown was interrupted.
            std::thread::spawn(|| {
                let _ = io::stdin().lock().lines().next();
                std::process::exit(0)
            });
            let mut last = Instant::now();
            loop {
                if last.elapsed() > Duration::from_secs(12) {
                    println!("unavailable\tWindows 正在恢复蓝牙状态");
                    std::thread::sleep(Duration::from_millis(250));
                }
                last = Instant::now();
                println!(
                    "{}",
                    facts::snapshot().unwrap_or_else(|e| format!("unavailable\t{}", clean(e)))
                );
                std::thread::sleep(Duration::from_secs(2));
            }
        }
        Some("scan") => scan::watch(),
        Some("gatt") if args.len() == 4 => gatt::serve(&args[2], &args[3]),
        _ => Err("Expected facts, scan, or gatt ADDRESS SERVICE".into()),
    }
}
