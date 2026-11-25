# OpenRose Audio Connection Observation Probe

Standalone Rust CLI probe for ticket #143 (prototype branch `prototype/139-nsh`, never merged to `main`). It verifies per-headset system audio connection observation on **Windows** and **macOS** and records which layer each OS can distinguish (`device-level`, `classic-audio`, `a2dp`, `hfp`, `le-audio`) ahead of gate #144 and platform tickets #160 / #161.

## Build & Run

### Windows (x86_64)

Prerequisites: Rust toolchain (MSVC recommended, e.g., `x86_64-pc-windows-msvc` with Visual Studio C++ Build Tools).

```powershell
cd prototype\audio-probe
cargo run -- --once
cargo run -- --filter i5
cargo run -- --json
```

To build a standalone executable on Windows:

```powershell
cargo build --release
.\target\release\openrose-audio-probe.exe --filter i5
```

Cross-checking from Linux (type/API verification without linking):

```bash
cargo check --manifest-path prototype/audio-probe/Cargo.toml --target x86_64-pc-windows-msvc
cargo check --manifest-path prototype/audio-probe/Cargo.toml --target x86_64-pc-windows-gnu
```

### macOS (x86_64 / arm64)

Prerequisites: Rust toolchain + Xcode Command Line Tools (`xcode-select --install`).

```bash
cd prototype/audio-probe
cargo run -- --once
cargo run -- --filter i5
cargo run -- --json
```

> Note: On macOS 10.15+, running from Terminal requires Bluetooth permission for the terminal app (`System Settings -> Privacy & Security -> Bluetooth`). If permission is denied, `IOBluetoothHostController::powerState` reports `Unintialized` and `IOBluetoothDevice::pairedDevices` may return empty; CoreAudio still reports connected audio endpoints.

Cross-checking from Linux (type/API verification without an Apple SDK):

```bash
cargo check --manifest-path prototype/audio-probe/Cargo.toml --target x86_64-apple-darwin
cargo check --manifest-path prototype/audio-probe/Cargo.toml --target aarch64-apple-darwin
```

### Offline Unit/Seam Tests (Any OS)

```bash
cargo test --manifest-path prototype/audio-probe/Cargo.toml
```

## CLI Options

- `--once`: Print the current paired Bluetooth devices, audio endpoints, and layer assessment, then exit.
- `--json`: Emit newline-delimited JSON (`snapshot` / `event`) for logging during gate #144.
- `--filter <TEXT>`: Case-insensitive filter matching device name, MAC address, or Windows `ContainerId`.
- `--poll-ms <MS>`: Fallback rescan interval in milliseconds (default `2000`; event callbacks trigger an immediate debounced refresh at `150ms`).

## Gate #144 Real-Device Checklist (i5)

Run `cargo run -- --filter i5` (or without `--filter` to see all paired headsets), then exercise:

1. **Initial paired / disconnected state**: Verify `i5` appears in the initial `SNAPSHOT` with `link=disconnected`, `layer=none`.
2. **Connect headset audio**: Verify `AUDIO_CONNECTED` fires, and record the reported `layer`, `direct_layer`, `evidence`, and raw `endpoint:` lines.
3. **Media playback (A2DP)**: Verify `layer=a2dp` (`direct` on Win10, `inferred; direct=classic-audio` on Win11 / macOS).
4. **Open microphone / voice call (HFP)**: Start a voice recorder or call using the headset mic; verify `AUDIO_LAYER_CHANGED` (`a2dp -> hfp`) fires, then reverts (`hfp -> a2dp`) when the mic closes.
5. **Disconnect audio (or put buds back in case)**: Verify `AUDIO_DISCONNECTED` fires promptly and note whether `device_link=still-connected` occurs transiently before `DEVICE_LINK disconnected`.
6. **LE Audio (if host & headset support it)**: Verify `layer=le-audio (direct)` and check whether Classic + LE share a Windows `ContainerId` or whether macOS reports `kAudioDeviceTransportTypeBluetoothLE`.

## Platform Findings: APIs, Layers & Known Limitations

### Layer Distinguishability Matrix

| Layer | Windows 10 | Windows 11 (Unified Classic + LE Audio) | macOS |
| --- | --- | --- | --- |
| Paired device list | **Direct** (WinRT `DeviceInformation` AEP: Classic + LE) | **Direct** (WinRT `DeviceInformation` AEP: Classic + LE) | **Direct (Classic)** via `IOBluetoothDevice::pairedDevices`; pure LE Audio only visible when connected in CoreAudio |
| `device-level` (ACL / LE link) | **Direct** (`System.Devices.Aep.IsConnected`) | **Direct** (`System.Devices.Aep.IsConnected`) | **Direct (Classic ACL)** via `IOBluetoothDevice::isConnected` + connect/disconnect notifications |
| `classic-audio` (any Classic audio) | **Direct** (MMDevice `DEVICE_STATE_ACTIVE` + `ContainerId` + `BTHENUM`/`BTHHFENUM` filter) | **Direct** (MMDevice `DEVICE_STATE_ACTIVE` + `ContainerId` + `BTHENUM`/`BTHHFENUM` filter) | **Direct** (CoreAudio presence + `kAudioDeviceTransportTypeBluetooth`) |
| `a2dp` | **Direct** (separate `{Name} Stereo` render endpoint backed by `BTHENUM\{0000110b-...}`) | **Inferred** (Win11 unifies A2DP/HFP into one render + one capture endpoint; inferred when render is active & capture sessions are idle) | **Inferred** (CoreAudio Classic output present, input not running, sample rate > 16 kHz) |
| `hfp` | **Direct** (separate `{Name} Hands-Free` endpoint backed by `BTHHFENUM`) | **Inferred** (unified Classic endpoint with active `IAudioSessionManager2` capture session or mono <= 16 kHz render mix format) | **Inferred** (CoreAudio Classic input `kAudioDevicePropertyDeviceIsRunningSomewhere != 0`, or output sample rate <= 16 kHz, or input-only) |
| `le-audio` | Not supported by OS | **Direct** when active endpoint topology filter uses `BTHLE`/`BTHLEDEVICE`/`BTHLEAUDIO` (fallback **Inferred** if matched to LE-only / ASCS-PACS container) | **Direct** via CoreAudio `kAudioDevicePropertyTransportType == kAudioDeviceTransportTypeBluetoothLE` (`'blea'`) |

### APIs Used

#### Windows (`windows` 0.61 + `windows-collections` 0.2)
- **WinRT Bluetooth & Enumeration**:
  - `BluetoothAdapter::GetDefaultAsync`, `GetRadioAsync`, `RadioState`
  - `BluetoothDevice::GetDeviceSelectorFromPairingState(true)` & `BluetoothLEDevice::GetDeviceSelectorFromPairingState(true)` with `DeviceInformation::FindAllAsyncWithKindAqsFilterAndAdditionalProperties` (`DeviceInformationKind::AssociationEndpoint`) reading `System.Devices.Aep.ContainerId`, `System.Devices.Aep.DeviceAddress`, `System.Devices.Aep.IsConnected`
  - `DeviceInformation::CreateWatcherWithKindAqsFilterAndAdditionalProperties` for real-time AEP connect/disconnect/pairing notifications
  - `DeviceInformationKind::AssociationEndpointService` querying Bluetooth Classic (`{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}`) and LE (`{bb7bb05e-5972-42b5-94fc-76eaa7084d49}`) service class IDs (`110B`, `111E`, `184E`, `1850`, `1855`, `1846`)
- **Win32 Core Audio MMDevice & Topology**:
  - `IMMDeviceEnumerator::EnumAudioEndpoints(eRender / eCapture, DEVICE_STATE_ACTIVE | DEVICE_STATE_UNPLUGGED)`
  - `IMMNotificationClient` (`RegisterEndpointNotificationCallback`) for real-time endpoint state/add/remove/property events
  - `IPropertyStore`: `PKEY_Device_ContainerId`, `PKEY_Device_FriendlyName`, `PKEY_Device_DeviceDesc`, `PKEY_Device_InstanceId`, `DEVPKEY_Device_EnumeratorName`
  - `IDeviceTopology::GetConnector` -> `GetDeviceIdConnectedTo` -> `IMMDeviceEnumerator::GetDevice` to inspect hardware audio filter enumerator (`BTHENUM`, `BTHHFENUM`, `BTHLE`)
  - `IAudioSessionManager2` (`AudioSessionStateActive` on capture endpoints) and `IAudioClient::GetMixFormat` (`nSamplesPerSec`, `nChannels`)

#### macOS (`objc2` 0.6 + `objc2-io-bluetooth` 0.3 + `objc2-core-audio` 0.3)
- **IOBluetooth**:
  - `IOBluetoothHostController::defaultController` / `powerState` (`BluetoothHCIPowerState`)
  - `IOBluetoothDevice::pairedDevices`, `nameOrAddress`, `addressString`, `isConnected`, `isHandsFreeAudioGateway`, `isHandsFreeDevice`, `getServiceRecordForUUID` (SDP `0x110B`, `0x111E`, `0x1108`, `0x110E`)
  - `IOBluetoothDevice::registerForConnectNotifications:selector:` & `registerForDisconnectNotification:selector:` (Classic ACL connect/disconnect)
- **CoreAudio HAL**:
  - `AudioObjectGetPropertyData` / `AudioObjectGetPropertyDataSize` on `kAudioObjectSystemObject` (`kAudioHardwarePropertyDevices`)
  - Per-device properties: `kAudioDevicePropertyTransportType` (`kAudioDeviceTransportTypeBluetooth` vs `kAudioDeviceTransportTypeBluetoothLE`), `kAudioDevicePropertyDeviceUID`, `kAudioObjectPropertyName`, `kAudioDevicePropertyDeviceIsAlive`, `kAudioDevicePropertyStreamConfiguration` (output & input channel counts), `kAudioDevicePropertyNominalSampleRate`, `kAudioDevicePropertyDeviceIsRunningSomewhere` (output & input)
  - `AudioObjectAddPropertyListener` on `kAudioHardwarePropertyDevices` and per-Bluetooth-device live properties, driven on `CFRunLoop::run_in_mode`

### Known Limitations

1. **Windows 11 A2DP vs HFP unification**: Windows 11 intentionally unifies A2DP and HFP into one render endpoint and one capture endpoint; there is no public WinRT/Win32 API that directly exposes "A2DP stream active vs HFP SCO active". Capture-session state and mix format provide an inference, and `IAudioSessionManager2` state changes do not trigger `IMMNotificationClient` (covered by the fallback `--poll-ms` timer).
2. **Windows `ContainerId` correlation edge cases**: Some OEM Bluetooth I2S/offload audio drivers must implement `KSPROPERTY_JACK_CONTAINERID` for MMDevice to populate `PKEY_Device_ContainerId`; if an audio driver omits it, endpoints cannot be correlated by `ContainerId` alone.
3. **Windows LE Audio filter strings**: Real Windows 11 LE Audio hardware topology filter enumerators/instance IDs (e.g., `BTHLE`, `BthLeAudio`, ACX VSAP drivers) vary by IHV audio DSP driver; the probe logs raw `filters=[...]` so gate #144 can record exact strings on real hardware.
4. **macOS has no public per-profile Bluetooth audio API**: `IOBluetoothDevice` only reports Classic ACL connection and cached SDP capabilities (never A2DP/HFP link state, never pure BLE/LE Audio). CoreAudio directly tells you audio presence and Classic (`'blue'`) vs LE Audio (`'blea'`), while A2DP vs HFP can only be inferred from input running state and nominal sample rate.
5. **macOS `kAudioDevicePropertyDeviceUID` format is undocumented**: Apple documents `DeviceUID` as an opaque persistent string. In practice macOS formats Classic Bluetooth UIDs with the device MAC prefix (e.g. `AA-BB-CC-DD-EE-FF:output` / `:input`), which this probe parses first with a unique-name fallback. Gate #144 should confirm actual UID strings on the maintainer's Mac.
6. **macOS CoreBluetooth BLE identity**: Consistent with ADR 0038 and #161, this probe does not use any private APIs to read BLE MAC addresses. `IOBluetoothDevice::addressString` is a documented public API for Classic paired devices only.
7. **Verification status**: Offline classification/diff/CLI tests pass on Linux; Windows (`x86_64-pc-windows-msvc`, `x86_64-pc-windows-gnu`) and macOS (`x86_64-apple-darwin`, `aarch64-apple-darwin`) compile-check cleanly (`cargo check` / `cargo clippy`). Real-device execution on Windows and the maintainer's Mac is deferred to human gate #144.

## Primary Sources

- [Bluetooth Classic Audio — Windows drivers (Microsoft Learn)](https://learn.microsoft.com/en-us/windows-hardware/drivers/bluetooth/bluetooth-classic-audio)
- [Bluetooth Low Energy (LE) Audio — Windows drivers (Microsoft Learn)](https://learn.microsoft.com/en-us/windows-hardware/drivers/bluetooth/bluetooth-low-energy-audio)
- [Detect audio format capabilities for communications scenarios (Microsoft Learn)](https://learn.microsoft.com/en-us/windows/win32/coreaudio/communications-audio-format-capabilities)
- [Audio Endpoint Container ID (Microsoft Learn)](https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/audio-endpoint-container-id)
- [Device Properties — Core Audio APIs (Microsoft Learn)](https://learn.microsoft.com/en-us/windows/win32/coreaudio/device-properties)
- [IMMNotificationClient interface (Microsoft Learn)](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nn-mmdeviceapi-immnotificationclient)
- [AEP service class IDs & DeviceInformationKind (Microsoft Learn)](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/aep-service-class-ids)
- [IOBluetoothDevice (Apple Developer Documentation)](https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice)
- [kAudioDeviceTransportTypeBluetooth / CoreAudio HAL (Apple Developer Documentation)](https://developer.apple.com/documentation/coreaudio/kaudiodevicetransporttypebluetooth)
