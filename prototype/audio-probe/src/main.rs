use openrose_audio_probe::cli::{help_text, parse_args};
use openrose_audio_probe::platform;
use std::env;
use std::process::ExitCode;

fn main() -> ExitCode {
    let options = match parse_args(env::args().skip(1)) {
        Ok(options) => options,
        Err(message) => {
            eprintln!("error: {message}\n\n{}", help_text());
            return ExitCode::from(2);
        }
    };

    if options.show_help {
        print!("{}", help_text());
        return ExitCode::SUCCESS;
    }

    if let Err(err) = platform::watch(&options) {
        eprintln!("error: {err}");
        return ExitCode::FAILURE;
    }
    ExitCode::SUCCESS
}
