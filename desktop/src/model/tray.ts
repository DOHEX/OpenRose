import type { RemoteHeadsetView } from "./circulation";
import { noiseModeLabel } from "./headset";
import type {
  BatterySlotView,
  ControlSnapshot,
  HeadsetCandidate,
  ModelCatalogEntry,
  NoiseFeedback,
} from "./headset";

export const TRAY_APP_NAME = "OpenRose";
export const TRAY_PANEL_TITLE = "OpenRose 托盘面板";

export interface TrayHeadsetController {
  snapshot: () => ControlSnapshot;
  catalogById: Map<string, ModelCatalogEntry>;
  batterySlots: () => BatterySlotView[];
  noiseModes: () => string[];
  canControlNoiseMode: () => boolean;
  noiseFeedback: () => NoiseFeedback;
  connect: (headsetId?: string | null) => Promise<void>;
  disconnect: () => Promise<void>;
  setNoiseMode: (mode: string, expectedSession?: number | null) => Promise<void>;
  executeCommand: (commandJson: string, expectedSession: number | null) => Promise<void>;
}

export interface TrayNoiseChoice {
  mode: string;
  label: string;
  selected: boolean;
  enabled: boolean;
}

export interface TraySummary {
  connected: boolean;
  connecting: boolean;
  headsetName: string | null;
  title: string;
  stateText: string;
  batteryText: string | null;
  sessionId: number | null;
  noiseModes: TrayNoiseChoice[];
  noiseFeedbackText: string | null;
  reconnectTarget: string | null;
  reconnectLabel: string;
  addHeadset: boolean;
  errorText: string | null;
  remote: { title: string; batteryText: string | null } | null;
}

const BATTERY_SHORT_LABELS: Record<BatterySlotView["id"], string> = {
  left: "左",
  right: "右",
  case: "盒",
  unit: "电量",
};

function targetHeadset(snapshot: ControlSnapshot): HeadsetCandidate | null {
  if (snapshot.connection_target) return snapshot.connection_target;
  const selected = snapshot.bonded_headsets.find((item) => item.id === snapshot.selected_headset_id);
  return selected ?? snapshot.bonded_headsets[0] ?? null;
}

function headsetName(
  target: HeadsetCandidate | null,
  profileId: string | null,
  catalogById: Map<string, ModelCatalogEntry>,
): string | null {
  if (target?.name?.trim()) return target.name;
  const profile = profileId ?? target?.profile ?? null;
  if (profile) return catalogById.get(profile)?.displayName ?? profile;
  return target?.id ?? null;
}

export function computeTraySummary(
  controller: TrayHeadsetController,
  remoteHolder: RemoteHeadsetView | null = null,
): TraySummary {
  const snapshot = controller.snapshot();
  const connection = snapshot.connection;
  const headset = snapshot.headset;
  const connected = typeof connection === "object" && "Connected" in connection && headset !== null;
  const connecting = typeof connection === "object" && "Connecting" in connection;
  const target = targetHeadset(snapshot);
  const profileId =
    typeof connection === "object" && "Connected" in connection
      ? connection.Connected.profile
      : (headset?.readings.profile ?? null);
  const name = headsetName(target, profileId, controller.catalogById);
  const audioConnected =
    target !== null &&
    snapshot.audio_headset_id !== null &&
    (target.id === snapshot.audio_headset_id || target.classic_address === snapshot.audio_headset_id);

  let stateText = "本机未连接";
  if (connected) stateText = "已连接";
  else if (connecting) stateText = "正在连接…";
  else if (audioConnected) stateText = "耳机已接入，控制未连接";
  else if (target === null) stateText = "未添加耳机";

  const batteryText = connected
    ? controller
        .batterySlots()
        .map((slot) => `${BATTERY_SHORT_LABELS[slot.id]} ${slot.valueText}`)
        .join(" · ") || null
    : null;

  const feedback = controller.noiseFeedback();
  const pendingMode = feedback.kind === "in_progress" ? feedback.mode : null;
  const confirmedMode = headset?.readings.fields.NoiseMode;
  const confirmed =
    confirmedMode && typeof confirmedMode === "object" && "Known" in confirmedMode &&
    "Text" in confirmedMode.Known.value
      ? confirmedMode.Known.value.Text
      : null;
  const noiseModes: TrayNoiseChoice[] =
    connected && controller.canControlNoiseMode()
      ? controller.noiseModes().map((mode) => ({
          mode,
          label: noiseModeLabel(mode),
          selected: mode === confirmed,
          enabled: pendingMode === null,
        }))
      : [];

  return {
    connected,
    connecting,
    headsetName: name,
    title: name ? `${name} · ${stateText}` : stateText,
    stateText,
    batteryText,
    sessionId: connected && headset ? headset.session_id : null,
    noiseModes,
    noiseFeedbackText: pendingMode ? `正在切换至${noiseModeLabel(pendingMode)}…` : null,
    reconnectTarget:
      !connected && !connecting && !snapshot.connection_block_reason && target ? target.id : null,
    reconnectLabel: audioConnected ? "恢复控制" : "重新连接",
    addHeadset: snapshot.bonded_headsets.length === 0 && snapshot.connection_target === null,
    errorText: !connected && !connecting ? (snapshot.last_error?.message ?? null) : null,
    remote:
      !connected && remoteHolder?.headsetName
        ? {
            title: `${remoteHolder.headsetName} · ${remoteHolder.peerName}`,
            batteryText: remoteHolder.batteryText,
          }
        : null,
  };
}

export function trayIconState(summary: TraySummary): "connected" | "remote" | "disconnected" {
  if (summary.connected) return "connected";
  return summary.remote ? "remote" : "disconnected";
}

export function trayTooltip(started: boolean, summary: TraySummary): string {
  if (!started) return TRAY_APP_NAME;
  if (summary.remote) return [summary.remote.title, summary.remote.batteryText].filter(Boolean).join("\n");
  return [summary.title, summary.batteryText].filter(Boolean).join("\n");
}

export interface TrayMenuEntry {
  kind: "label" | "separator" | "noise_mode" | "action";
  label?: string;
  checked?: boolean;
  disabled?: boolean;
  onSelect?: () => void;
}

export interface TrayMenuActions {
  togglePanel: () => void;
  openMain: () => void;
  openAddHeadset: () => void;
  reconnect: (headsetId: string) => void;
  selectNoiseMode: (mode: string, sessionId: number) => void;
  quit: () => void;
}

export function buildTrayMenuEntries(
  summary: TraySummary,
  panelOpen: boolean,
  actions: TrayMenuActions,
): TrayMenuEntry[] {
  const entries: TrayMenuEntry[] = [{ kind: "label", label: summary.title, disabled: true }];
  if (summary.batteryText) {
    entries.push({ kind: "label", label: summary.batteryText, disabled: true });
  }
  const boundSession = summary.sessionId;
  if (summary.noiseModes.length > 0) {
    entries.push({ kind: "separator" });
    if (summary.noiseFeedbackText) {
      entries.push({ kind: "label", label: summary.noiseFeedbackText, disabled: true });
    }
    for (const choice of summary.noiseModes) {
      entries.push({
        kind: "noise_mode",
        label: choice.label,
        checked: choice.selected,
        disabled: !choice.enabled,
        onSelect: () => {
          if (choice.selected || boundSession === null) return;
          actions.selectNoiseMode(choice.mode, boundSession);
        },
      });
    }
  }
  const reconnectTarget = summary.reconnectTarget;
  if (reconnectTarget) {
    entries.push({
      kind: "action",
      label: summary.reconnectLabel,
      onSelect: () => actions.reconnect(reconnectTarget),
    });
  }
  if (summary.addHeadset) {
    entries.push({ kind: "action", label: "添加耳机", onSelect: () => actions.openAddHeadset() });
  }
  entries.push(
    { kind: "separator" },
    {
      kind: "action",
      label: panelOpen ? "关闭托盘面板" : "打开托盘面板",
      onSelect: () => actions.togglePanel(),
    },
    { kind: "action", label: "打开 OpenRose", onSelect: () => actions.openMain() },
    { kind: "action", label: "退出", onSelect: () => actions.quit() },
  );
  return entries;
}

export function usesNativeCloseOnBlur(os: string): boolean {
  return os !== "linux";
}

export function shouldCloseTrayPanelOnBlur(state: {
  os: string;
  focused: boolean;
  pointerEntered: boolean;
  popupsOpen: number;
}): boolean {
  return (
    !usesNativeCloseOnBlur(state.os) &&
    !state.focused &&
    state.pointerEntered &&
    state.popupsOpen === 0
  );
}

export function closesMainWindowToTray(
  backgroundControl: boolean,
  tray: { status: "available" | "unavailable" },
): boolean {
  return backgroundControl && tray.status === "available";
}
