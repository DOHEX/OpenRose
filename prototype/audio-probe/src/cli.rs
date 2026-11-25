#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CliOptions {
    pub once: bool,
    pub json: bool,
    pub filter: Option<String>,
    pub poll_ms: u64,
    pub show_help: bool,
}

impl Default for CliOptions {
    fn default() -> Self {
        Self {
            once: false,
            json: false,
            filter: None,
            poll_ms: 2000,
            show_help: false,
        }
    }
}

pub fn parse_args<I, S>(args: I) -> Result<CliOptions, String>
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    let mut options = CliOptions::default();
    let mut iter = args.into_iter();
    while let Some(arg) = iter.next() {
        let token = arg.as_ref();
        match token {
            "--once" => options.once = true,
            "--json" => options.json = true,
            "-h" | "--help" => options.show_help = true,
            "--filter" => {
                let value = iter
                    .next()
                    .ok_or_else(|| "missing value for --filter".to_owned())?;
                options.filter = Some(value.as_ref().to_owned());
            }
            "--poll-ms" => {
                let raw = iter
                    .next()
                    .ok_or_else(|| "missing value for --poll-ms".to_owned())?;
                let parsed: u64 = raw
                    .as_ref()
                    .parse()
                    .map_err(|_| format!("invalid --poll-ms value: {}", raw.as_ref()))?;
                if parsed == 0 {
                    return Err("--poll-ms must be greater than 0".to_owned());
                }
                options.poll_ms = parsed;
            }
            _ => return Err(format!("unknown argument: {token}")),
        }
    }
    Ok(options)
}

pub fn help_text() -> &'static str {
    "openrose-audio-probe - Observe Bluetooth headset audio connections on Windows and macOS

USAGE:
  openrose-audio-probe [OPTIONS]

OPTIONS:
  --once              Print initial paired devices and audio state snapshot, then exit
  --json              Emit newline-delimited JSON records
  --filter <TEXT>     Filter devices by name, MAC address, or container ID (case-insensitive)
  --poll-ms <MS>      Fallback rescan interval in milliseconds (default: 2000)
  -h, --help          Show this help message

LAYERS:
  none           No Bluetooth ACL/LE or audio link active
  device-level   Bluetooth link connected (WinRT AEP / IOBluetooth ACL), no active audio endpoint
  classic-audio  Active Classic Bluetooth audio endpoint (direct OS signal)
  a2dp           Stereo media playback profile (direct on Win10; inferred on Win11/macOS)
  hfp            Hands-Free voice/mic profile (direct on Win10; inferred on Win11/macOS)
  le-audio       Bluetooth LE Audio (direct via BTHLE filter / CoreAudio kAudioDeviceTransportTypeBluetoothLE)
  audio-unknown  Active audio endpoint matched container without profile disambiguation
"
}
