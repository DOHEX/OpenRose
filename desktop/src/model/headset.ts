import { appInfo, cleanup, computed, signal } from "nsh";
import { OpenRoseCatalog, OpenRoseClient, OpenRoseHeadsetFlow } from "openrose-native";
import { connectionPhaseLabel, errorCategoryLabel, sortNoiseModes } from "./labels";

export type BatteryLayout = "EarbudsAndCase" | "EarbudsOnly" | "SingleUnit";
export type ProtocolFamilyId = "Zt" | "RsCommonV2" | "F8" | "RsCommon";
export type ConnectionPhase = "Connecting" | "Discovering" | "Subscribing" | "Initializing" | "Ready";
export type SettingStage =
  | "Submitted"
  | "PartiallyWritten"
  | "Written"
  | "Confirmed"
  | "NotApplied"
  | "TimedOut"
  | "Disconnected"
  | "Failed";
export type ControlErrorCode =
  | "PermissionRequired"
  | "TransportUnavailable"
  | "NotReady"
  | "Unsupported"
  | "InvalidArgument"
  | "NotApplied"
  | "Timeout"
  | "Disconnected"
  | "Failed";

export interface ModelCatalogEntry {
  id: string;
  displayName: string;
  shortLabel: string;
  versionName: string | null;
  family: ProtocolFamilyId;
  familyLabel: string;
  batteryLayout: BatteryLayout;
  defaultFirmware: string;
  firmwareHint: string;
}

export interface DevHeadsetConfig {
  headsetId: string;
  modelId: string;
  displayName: string;
  versionName: string | null;
  family: string;
  firmware: string;
  replyLatencyMs: number;
}

export interface ControlError {
  code: ControlErrorCode;
  message: string;
}

export interface TouchBinding {
  side: string;
  gesture: string;
  action: string | null;
  raw_action: number;
}

export type FieldValue =
  | { Number: number }
  | { Toggle: boolean }
  | { Text: string }
  | { IntegerList: number[] }
  | { Firmware: { parts: number[]; display_text: string } }
  | { TouchKeys: TouchBinding[] }
  | { TouchAssignment: { side: string; gesture: string; action: string } }
  | { NoiseCycle: Record<string, boolean | null> };

export type FieldReading =
  | "Loading"
  | { NotProvided: { raw_hex: string } }
  | { Unknown: { raw_hex: string } }
  | { Known: { value: FieldValue; raw_hex: string } };

export interface NoiseLevelCapabilities {
  levels: number[];
  visible_modes: string[];
}

export interface TouchSlot {
  side: string;
  gesture: string;
}

export interface TouchSlotLabelEntry {
  slot: TouchSlot;
  label: string;
}

export interface TouchControlCapabilities {
  slots: TouchSlot[];
  actions: string[];
  slot_labels: TouchSlotLabelEntry[];
}

export interface CustomEqualizerCapabilities {
  bands_hz: number[];
  min_gain: number;
  max_gain: number;
}

export interface HeadsetCapabilities {
  readable_fields: string[];
  writable_settings: string[];
  noise_control: {
    modes: string[];
    cycle_modes: string[];
    anc_level: NoiseLevelCapabilities | null;
    transparency_level: NoiseLevelCapabilities | null;
    minimum_cycle_selections: number;
  } | null;
  equalizer: {
    presets: string[];
    custom: CustomEqualizerCapabilities | null;
  } | null;
  touch_control: TouchControlCapabilities | null;
  tap_control: TouchControlCapabilities | null;
  audio_control: {
    modes: string[];
    multipoint_coupled: boolean;
  } | null;
  prompt_volume_levels: number[];
  gain_levels: number[];
  hearing_protection_levels: number[];
  bass_boost_levels: number[];
  prompt_languages: string[];
  spatial_modes: string[];
  spatial_contents: string[];
}

export type HostPlatform = "Unknown" | "Android" | "Windows" | "Linux" | "MacOs";
export type PermissionState = "NotRequired" | "Required" | "Granted" | "Denied";
export type PlatformApplicability = "Unknown" | "NotApplicable" | "Applicable";
export type ReconnectGuidance = "OpenCaseAutoConnect" | "WaitSystemBluetooth";
export type TransportRecoveryReason =
  | "ServiceDiscovery"
  | "ServicesUnavailable"
  | "Subscription"
  | "WriteFailed"
  | "ServicesChanged"
  | "NoInitialResponse";
export type TransportSelectionReason =
  | "ModelBleOnly"
  | "ForcedBle"
  | "SppUnavailable"
  | "ClassicAddressUnavailable"
  | "AudioProfileUnknown"
  | "LeAudio"
  | "ClassicAudio";

export type TransportUnavailableReason =
  | { UnsupportedPlatform: { platform: string | null } }
  | "BleUnsupported"
  | "PermissionRequired"
  | "NoAdapter"
  | "AdapterPoweredOff"
  | "AdapterStateUnknown"
  | "AccessDenied"
  | "ServiceUnavailable"
  | { MissingDependency: { detail: string | null } }
  | "Initializing"
  | "Recovering"
  | "ShuttingDown"
  | "Closed"
  | { BackendUnavailable: { detail: string | null } };

export type TransportAvailability =
  | "Available"
  | "Disabled"
  | { Unavailable: { reason: TransportUnavailableReason } };

export type ConnectionBlockReason =
  | "BluetoothPermissionRequired"
  | "BluetoothDisabled"
  | { TransportUnavailable: { reason: TransportUnavailableReason } };

export interface PlatformState {
  permission: PermissionState;
  transport: TransportAvailability;
  host_platform: HostPlatform;
  user_unlocked: boolean;
  scan_permission: PermissionState;
  control_transport_applicability: PlatformApplicability;
}
export type ScanState = "Idle" | "Scanning" | { Failed: ControlError };

export interface ConnectionRecovery {
  session_id: number;
  reason: TransportRecoveryReason;
}

export type TransportEndpoint =
  | { BleGatt: { service_uuid: string; write_uuid: string; notify_uuid: string } }
  | { SppRfcomm: { address: string; service_uuid: string } };

export interface TransportSelection {
  endpoint: TransportEndpoint;
  reason: TransportSelectionReason;
}

export interface HeadsetDiagnostics {
  field_errors: Record<string, ControlError>;
  raw_notifications: string[];
  unknown_notifications: string[];
  warnings: string[];
}
export interface HeadsetState {
  headset_id: string;
  session_id: number;
  readings: {
    profile: string;
    capabilities: HeadsetCapabilities;
    fields: Record<string, FieldReading>;
  };
  diagnostics: HeadsetDiagnostics;
  classic_address: string | null;
  transport_selection: TransportSelection | null;
}

export interface SettingOperation {
  id: number;
  session_id: number;
  setting: string;
  requested_value: FieldValue;
  stage: SettingStage;
  error: ControlError | null;
  written_frames: number;
  total_frames: number;
  confirmed_fields: string[];
}

export type ConnectionState =
  | "Disconnected"
  | { Connecting: { headset_id: string; session_id: number; phase: ConnectionPhase } }
  | { Connected: { headset_id: string; session_id: number; profile: string } };

export interface HeadsetCandidate {
  id: string;
  name: string | null;
  rssi: number | null;
  profile: string | null;
  manufacturer_data_hex: string;
  classic_address: string | null;
}

export interface ControlSnapshot {
  revision: number;
  platform: PlatformState;
  settings: { force_ble: boolean };
  connection: ConnectionState;
  bonded_headsets: HeadsetCandidate[];
  candidates: HeadsetCandidate[];
  scan: ScanState;
  headset: HeadsetState | null;
  last_error: ControlError | null;
  setting_operations: SettingOperation[];
  audio_headset_id: string | null;
  connection_target: HeadsetCandidate | null;
  selected_headset_id: string | null;
  connection_recovery: ConnectionRecovery | null;
  connection_block_reason: ConnectionBlockReason | null;
  reconnect_guidance: ReconnectGuidance | null;
}

export interface BatterySlotView {
  id: "left" | "right" | "case" | "unit";
  label: string;
  valueText: string;
  caption: string | null;
  low: boolean;
}

export type NoiseFeedback =
  | { kind: "idle" }
  | { kind: "in_progress"; mode: string; stage: SettingStage }
  | { kind: "confirmed"; mode: string }
  | { kind: "failed"; mode: string; code: string; message: string };

const PENDING_STAGES: readonly SettingStage[] = ["Submitted", "PartiallyWritten", "Written"];
const FAILED_STAGES: readonly SettingStage[] = ["NotApplied", "TimedOut", "Disconnected", "Failed"];

export type HeadsetCommand = Record<string, unknown>;

export function isPendingStage(stage: SettingStage): boolean {
  return PENDING_STAGES.includes(stage);
}

export function isFailedStage(stage: SettingStage): boolean {
  return FAILED_STAGES.includes(stage);
}

export function controlErrorOf(cause: unknown): ControlError {
  const code = cause instanceof Error ? cause.message : String(cause);
  return { code: code as ControlErrorCode, message: "" };
}

export function headsetDisplayName(
  candidate: HeadsetCandidate | null,
  catalogById: Map<string, ModelCatalogEntry>,
  profileId: string | null = null,
): string | null {
  const name = candidate?.name?.trim();
  if (name) return name;
  const profile = profileId ?? candidate?.profile ?? null;
  if (profile) return catalogById.get(profile)?.displayName ?? profile;
  return candidate?.id ?? null;
}

function readKnownValue(fields: Record<string, FieldReading>, key: string): FieldValue | null {
  const reading = fields[key];
  if (reading && typeof reading === "object" && "Known" in reading) {
    return reading.Known.value;
  }
  return null;
}

export function readKnownNumber(fields: Record<string, FieldReading>, key: string): number | null {
  const value = readKnownValue(fields, key);
  return value && "Number" in value ? value.Number : null;
}

export function readKnownToggle(fields: Record<string, FieldReading>, key: string): boolean | null {
  const value = readKnownValue(fields, key);
  return value && "Toggle" in value ? value.Toggle : null;
}

export function readKnownText(fields: Record<string, FieldReading>, key: string): string | null {
  const value = readKnownValue(fields, key);
  return value && "Text" in value ? value.Text : null;
}

export function readKnownIntegerList(
  fields: Record<string, FieldReading>,
  key: string,
): number[] | null {
  const value = readKnownValue(fields, key);
  return value && "IntegerList" in value ? value.IntegerList : null;
}

export function readKnownTouchKeys(
  fields: Record<string, FieldReading>,
  key: string,
): TouchBinding[] | null {
  const value = readKnownValue(fields, key);
  return value && "TouchKeys" in value ? value.TouchKeys : null;
}

export function readKnownNoiseCycle(
  fields: Record<string, FieldReading>,
): Record<string, boolean | null> | null {
  const value = readKnownValue(fields, "NoiseCycle");
  return value && "NoiseCycle" in value ? value.NoiseCycle : null;
}

function readKnownFirmware(fields: Record<string, FieldReading>): string | null {
  const value = readKnownValue(fields, "Firmware");
  return value && "Firmware" in value ? value.Firmware.display_text : null;
}

function formatBatteryValue(fields: Record<string, FieldReading>, key: string): {
  text: string;
  low: boolean;
} {
  const reading = fields[key];
  if (reading === "Loading") {
    return { text: "读取中", low: false };
  }
  const num = readKnownNumber(fields, key);
  if (num !== null) {
    return { text: `${num}%`, low: num <= 20 };
  }
  return { text: "--", low: false };
}

function formatUnitCaption(charging: boolean | null, inCase: boolean | null): string | null {
  const parts: string[] = [];
  if (charging === true) parts.push("充电中");
  if (inCase === true) parts.push("在盒内");
  return parts.length > 0 ? parts.join(" · ") : null;
}

export interface ConnectionStatusView {
  kind: "disconnected" | "connecting" | "connected";
  label: string;
  phaseLabel: string;
  phase: ConnectionPhase | null;
  headsetId: string | null;
  sessionId: number | null;
  profileId: string | null;
}

export interface HeadsetController {
  client: OpenRoseClient;
  catalog: ModelCatalogEntry[];
  catalogById: Map<string, ModelCatalogEntry>;
  snapshot: () => ControlSnapshot;
  devConfig: () => DevHeadsetConfig | null;
  connectionStatus: () => ConnectionStatusView;
  connectionBusy: () => boolean;
  connectionError: () => ControlError | null;
  currentModel: () => ModelCatalogEntry | null;
  firmwareText: () => string | null;
  batterySlots: () => BatterySlotView[];
  noiseModes: () => string[];
  confirmedNoiseMode: () => string | null;
  canControlNoiseMode: () => boolean;
  noiseFeedback: () => NoiseFeedback;
  submittingNoiseMode: () => string | null;
  devBusy: () => boolean;
  devError: () => string | null;
  connect: (headsetId?: string | null) => Promise<void>;
  disconnect: () => Promise<void>;
  setNoiseMode: (mode: string, expectedSession?: number | null) => Promise<void>;
  executeCommand: (command: HeadsetCommand, expectedSession: number | null) => Promise<void>;
  currentHeadset: () => HeadsetCandidate | null;
  switchSimulatedHeadset: (modelId: string, firmware: string) => Promise<boolean>;
  setSimulatedLatencyMs: (millis: number) => void;
  selectCurrentHeadset: (headsetId: string) => void;
  refreshBondedHeadsets: () => Promise<void>;
}

export function createHeadsetController(): HeadsetController {
  const catalog = OpenRoseCatalog.listModels() as ModelCatalogEntry[];
  const catalogById = new Map(catalog.map((entry) => [entry.id, entry]));
  const client = new OpenRoseClient(appInfo().development);

  const snapshot = signal<ControlSnapshot>(client.snapshot() as ControlSnapshot);
  const devConfig = signal<DevHeadsetConfig | null>(
    (client.devConfig() as DevHeadsetConfig | null) ?? null,
  );
  const connectionBusy = signal(false);
  const connectionError = signal<ControlError | null>(null);
  const submittingNoiseMode = signal<string | null>(null);
  const localNoiseError = signal<{ mode: string; error: ControlError } | null>(null);
  const devBusy = signal(false);
  const devError = signal<string | null>(null);

  const stop = client.subscribe((next) => {
    snapshot.set(next as ControlSnapshot);
  });
  void client.refreshBondedHeadsets();
  cleanup(() => {
    stop();
    client.dispose();
  });

  const connectionStatus = computed<ConnectionStatusView>(() => {
    const conn = snapshot().connection;
    if (conn === "Disconnected") {
      return {
        kind: "disconnected",
        label: "未连接",
        phaseLabel: "等待连接耳机",
        phase: null,
        headsetId: null,
        sessionId: null,
        profileId: null,
      };
    }
    if ("Connecting" in conn) {
      return {
        kind: "connecting",
        label: "连接中",
        phaseLabel: connectionPhaseLabel(conn.Connecting.phase),
        phase: conn.Connecting.phase,
        headsetId: conn.Connecting.headset_id,
        sessionId: conn.Connecting.session_id,
        profileId: snapshot().headset?.readings.profile ?? null,
      };
    }
    return {
      kind: "connected",
      label: "已连接",
      phaseLabel: "控制通道已就绪",
      phase: "Ready",
      headsetId: conn.Connected.headset_id,
      sessionId: conn.Connected.session_id,
      profileId: conn.Connected.profile,
    };
  });

  const currentHeadset = computed(() => {
    snapshot();
    return (OpenRoseHeadsetFlow.currentHeadset(client) as HeadsetCandidate | null) ?? null;
  });

  const currentModel = computed(() => {
    const profileId = connectionStatus().profileId ?? devConfig()?.modelId ?? null;
    return profileId ? catalogById.get(profileId) ?? null : null;
  });

  const firmwareText = computed(() => {
    const headset = snapshot().headset;
    if (headset) {
      const confirmed = readKnownFirmware(headset.readings.fields);
      if (confirmed) return confirmed;
    }
    return devConfig()?.firmware ?? null;
  });

  const batterySlots = computed<BatterySlotView[]>(() => {
    const headset = snapshot().headset;
    if (!headset) return [];
    const fields = headset.readings.fields;
    const layout = currentModel()?.batteryLayout ?? "EarbudsAndCase";
    if (layout === "SingleUnit") {
      const caseBat = formatBatteryValue(fields, "CaseBattery");
      return [
        {
          id: "unit",
          label: "耳机电量",
          valueText: caseBat.text,
          caption: readKnownToggle(fields, "CaseFlag") ? "充电中" : null,
          low: caseBat.low,
        },
      ];
    }
    const left = formatBatteryValue(fields, "LeftBattery");
    const right = formatBatteryValue(fields, "RightBattery");
    const slots: BatterySlotView[] = [
      {
        id: "left",
        label: "左耳",
        valueText: left.text,
        caption: formatUnitCaption(
          readKnownToggle(fields, "LeftCharging"),
          readKnownToggle(fields, "LeftInCase"),
        ),
        low: left.low,
      },
      {
        id: "right",
        label: "右耳",
        valueText: right.text,
        caption: formatUnitCaption(
          readKnownToggle(fields, "RightCharging"),
          readKnownToggle(fields, "RightInCase"),
        ),
        low: right.low,
      },
    ];
    if (layout === "EarbudsAndCase") {
      const box = formatBatteryValue(fields, "CaseBattery");
      slots.push({
        id: "case",
        label: "充电盒",
        valueText: box.text,
        caption: readKnownToggle(fields, "CaseFlag") ? "开盖" : null,
        low: box.low,
      });
    }
    return slots;
  });

  const noiseModes = computed<string[]>(() =>
    sortNoiseModes(snapshot().headset?.readings.capabilities.noise_control?.modes ?? []),
  );

  const confirmedNoiseMode = computed<string | null>(() => {
    const headset = snapshot().headset;
    if (!headset) return null;
    return readKnownText(headset.readings.fields, "NoiseMode");
  });

  const canControlNoiseMode = computed(() => {
    const headset = snapshot().headset;
    if (!headset) return false;
    if (!headset.readings.capabilities.writable_settings.includes("NoiseMode")) return false;
    return connectionStatus().kind === "connected";
  });

  const noiseFeedback = computed<NoiseFeedback>(() => {
    const snap = snapshot();
    const sessionId = snap.headset?.session_id ?? null;
    const ops = snap.setting_operations.filter(
      (op) => op.setting === "NoiseMode" && (sessionId === null || op.session_id === sessionId),
    );
    const latest = ops.length > 0 ? ops[ops.length - 1] : null;
    const pendingMode = submittingNoiseMode();

    if (latest) {
      const opMode =
        "Text" in latest.requested_value ? latest.requested_value.Text : confirmedNoiseMode() ?? "";
      if (isPendingStage(latest.stage)) {
        return { kind: "in_progress", mode: opMode, stage: latest.stage };
      }
      if (pendingMode) {
        return { kind: "in_progress", mode: pendingMode, stage: "Submitted" };
      }
      if (isFailedStage(latest.stage)) {
        const code = latest.error?.code ?? latest.stage;
        const message = latest.error?.message ?? "";
        return { kind: "failed", mode: opMode, code, message };
      }
      if (latest.stage === "Confirmed") {
        return { kind: "confirmed", mode: confirmedNoiseMode() ?? opMode };
      }
    }

    if (pendingMode) {
      return { kind: "in_progress", mode: pendingMode, stage: "Submitted" };
    }

    const err = localNoiseError();
    if (err) {
      return {
        kind: "failed",
        mode: err.mode,
        code: err.error.code,
        message: err.error.message,
      };
    }

    return { kind: "idle" };
  });

  async function connect(headsetId: string | null = null) {
    if (connectionBusy()) return;
    connectionBusy.set(true);
    connectionError.set(null);
    localNoiseError.set(null);
    try {
      await client.connect(headsetId);
    } catch (error) {
      connectionError.set(controlErrorOf(error));
    } finally {
      connectionBusy.set(false);
    }
  }

  async function disconnect() {
    if (connectionBusy()) return;
    connectionBusy.set(true);
    connectionError.set(null);
    try {
      await client.disconnect();
    } finally {
      connectionBusy.set(false);
    }
  }

  async function setNoiseMode(mode: string, expectedSession?: number | null) {
    if (submittingNoiseMode()) return;
    const session =
      expectedSession === undefined ? (snapshot().headset?.session_id ?? null) : expectedSession;
    submittingNoiseMode.set(mode);
    localNoiseError.set(null);
    try {
      await client.setNoiseMode(mode, session);
    } catch (error) {
      localNoiseError.set({ mode, error: controlErrorOf(error) });
    } finally {
      submittingNoiseMode.set(null);
    }
  }

  async function switchSimulatedHeadset(modelId: string, firmware: string) {
    if (devBusy()) return false;
    devBusy.set(true);
    devError.set(null);
    connectionError.set(null);
    localNoiseError.set(null);
    try {
      const updated = (await client.switchSimulatedHeadset(modelId, firmware)) as DevHeadsetConfig;
      devConfig.set(updated);
      return true;
    } catch (error) {
      devError.set(errorCategoryLabel(controlErrorOf(error).code));
      return false;
    } finally {
      devBusy.set(false);
    }
  }

  function setSimulatedLatencyMs(millis: number) {
    try {
      client.setSimulatedLatencyMs(millis);
      const latest = client.devConfig() as DevHeadsetConfig | null;
      if (latest) {
        devConfig.set(latest);
      }
      devError.set(null);
    } catch (error) {
      devError.set(errorCategoryLabel(controlErrorOf(error).code));
    }
  }
  function selectCurrentHeadset(headsetId: string) {
    connectionError.set(null);
    client.selectCurrentHeadset(headsetId);
    snapshot.set(client.snapshot() as ControlSnapshot);
  }

  async function refreshBondedHeadsets() {
    await client.refreshBondedHeadsets();
    snapshot.set(client.snapshot() as ControlSnapshot);
  }

  async function executeCommand(command: HeadsetCommand, expectedSession: number | null) {
    await client.executeCommand(JSON.stringify(command), expectedSession);
  }

  return {
    client,
    catalog,
    catalogById,
    snapshot,
    devConfig,
    connectionStatus,
    connectionBusy,
    connectionError,
    currentModel,
    firmwareText,
    batterySlots,
    noiseModes,
    confirmedNoiseMode,
    canControlNoiseMode,
    noiseFeedback,
    submittingNoiseMode,
    devBusy,
    devError,
    connect,
    disconnect,
    selectCurrentHeadset,
    refreshBondedHeadsets,
    setNoiseMode,
    executeCommand,
    currentHeadset,
    switchSimulatedHeadset,
    setSimulatedLatencyMs,
  };
}

