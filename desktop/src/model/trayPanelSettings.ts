import { Storage, signal } from "nsh";
import { OpenRoseTray } from "openrose-native";
import type { HeadsetCapabilities } from "./headset";

export type TrayPanelControl = "game_mode" | "equalizer" | "spatial_audio" | "independent_mic";

export interface TrayPanelControlMeta {
  label: string;
  summary: string;
}

export const TRAY_PANEL_CONTROL_META: Record<TrayPanelControl, TrayPanelControlMeta> = {
  game_mode: { label: "游戏模式", summary: "快速开关低延迟模式" },
  equalizer: { label: "EQ 预设", summary: "在面板内切换预设均衡器" },
  spatial_audio: { label: "空间音频", summary: "控制空间模式与条件显示的空间内容" },
  independent_mic: { label: "独立麦克风模式", summary: "未激活独立麦克风时禁用并提示前提" },
};

export interface TrayPanelSettings {
  showNoiseLevels: boolean;
  enabledControls: TrayPanelControl[];
  controlOrder: TrayPanelControl[];
}

export interface DesktopSettings {
  backgroundControl: boolean;
  trayPanel: TrayPanelSettings;
}

const CONTROL_IDS: TrayPanelControl[] = ["game_mode", "equalizer", "spatial_audio", "independent_mic"];

export const DEFAULT_TRAY_PANEL_SETTINGS: TrayPanelSettings = {
  showNoiseLevels: true,
  enabledControls: ["game_mode", "equalizer"],
  controlOrder: [...CONTROL_IDS],
};

export const DEFAULT_DESKTOP_SETTINGS: DesktopSettings = {
  backgroundControl: true,
  trayPanel: DEFAULT_TRAY_PANEL_SETTINGS,
};

const STORAGE_KEY = "desktop.settings";

function isControl(value: unknown): value is TrayPanelControl {
  return typeof value === "string" && (CONTROL_IDS as string[]).includes(value);
}

function controlList(value: unknown): TrayPanelControl[] {
  if (!Array.isArray(value)) return [];
  return value.filter(isControl).filter((item, index, all) => all.indexOf(item) === index);
}

export function normalizeTrayPanelSettings(settings: TrayPanelSettings): TrayPanelSettings {
  const order = controlList(settings.controlOrder);
  for (const id of CONTROL_IDS) {
    if (!order.includes(id)) order.push(id);
  }
  const enabled = new Set(controlList(settings.enabledControls));
  return {
    showNoiseLevels: settings.showNoiseLevels,
    enabledControls: order.filter((id) => enabled.has(id)),
    controlOrder: order,
  };
}

export function setTrayControlEnabled(
  settings: TrayPanelSettings,
  control: TrayPanelControl,
  enabled: boolean,
): TrayPanelSettings {
  const current = normalizeTrayPanelSettings(settings).enabledControls;
  const next = enabled ? [...current, control] : current.filter((id) => id !== control);
  return normalizeTrayPanelSettings({ ...settings, enabledControls: next });
}

export function moveTrayControl(
  settings: TrayPanelSettings,
  control: TrayPanelControl,
  delta: number,
): TrayPanelSettings {
  const normalized = normalizeTrayPanelSettings(settings);
  const from = normalized.controlOrder.indexOf(control);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= normalized.controlOrder.length) return normalized;
  const order = [...normalized.controlOrder];
  [order[from], order[to]] = [order[to], order[from]];
  return normalizeTrayPanelSettings({ ...normalized, controlOrder: order });
}

export function isDefaultTrayPanelSettings(settings: TrayPanelSettings): boolean {
  return JSON.stringify(normalizeTrayPanelSettings(settings)) ===
    JSON.stringify(normalizeTrayPanelSettings(DEFAULT_TRAY_PANEL_SETTINGS));
}

export function trayPanelSettingsSummary(settings: TrayPanelSettings): string {
  const normalized = normalizeTrayPanelSettings(settings);
  const items = normalized.showNoiseLevels ? ["降噪／通透档位"] : [];
  for (const id of normalized.controlOrder) {
    if (normalized.enabledControls.includes(id)) items.push(TRAY_PANEL_CONTROL_META[id].label);
  }
  return items.length === 0 ? "仅显示连接状态、电量与降噪模式" : items.join(" · ");
}

export function isControlSupported(
  control: TrayPanelControl,
  capabilities: HeadsetCapabilities,
): boolean {
  const writable = capabilities.writable_settings;
  switch (control) {
    case "game_mode":
      return writable.includes("GameMode");
    case "equalizer":
      return writable.includes("Eq") && (capabilities.equalizer?.presets.length ?? 0) > 0;
    case "spatial_audio":
      return writable.includes("SpatialMode") || writable.includes("SpatialAudio");
    case "independent_mic":
      return writable.includes("IndependentMicMode");
  }
}

export function supportedTrayControls(
  settings: TrayPanelSettings,
  capabilities: HeadsetCapabilities | null | undefined,
): TrayPanelControl[] {
  if (!capabilities) return [];
  const normalized = normalizeTrayPanelSettings(settings);
  return normalized.controlOrder.filter(
    (id) => normalized.enabledControls.includes(id) && isControlSupported(id, capabilities),
  );
}

function parseSettings(raw: string | null): DesktopSettings {
  if (raw === null) return DEFAULT_DESKTOP_SETTINGS;
  try {
    const parsed = JSON.parse(raw) as Partial<DesktopSettings> & {
      trayPanel?: Partial<TrayPanelSettings>;
    };
    const panel: Partial<TrayPanelSettings> = parsed.trayPanel ?? {};
    return {
      backgroundControl:
        typeof parsed.backgroundControl === "boolean"
          ? parsed.backgroundControl
          : DEFAULT_DESKTOP_SETTINGS.backgroundControl,
      trayPanel: normalizeTrayPanelSettings({
        showNoiseLevels:
          typeof panel.showNoiseLevels === "boolean"
            ? panel.showNoiseLevels
            : DEFAULT_TRAY_PANEL_SETTINGS.showNoiseLevels,
        enabledControls: Array.isArray(panel.enabledControls)
          ? panel.enabledControls
          : DEFAULT_TRAY_PANEL_SETTINGS.enabledControls,
        controlOrder: Array.isArray(panel.controlOrder)
          ? panel.controlOrder
          : DEFAULT_TRAY_PANEL_SETTINGS.controlOrder,
      }),
    };
  } catch {
    return DEFAULT_DESKTOP_SETTINGS;
  }
}

export function createDesktopSettingsStore(): DesktopSettingsStore {
  const settings = signal<DesktopSettings>(DEFAULT_DESKTOP_SETTINGS);
  let edited = false;

  void Storage.getItem(STORAGE_KEY)
    .then((raw) => {
      if (!edited) settings.set(parseSettings(raw));
    })
    .catch(() => undefined);

  const update = (next: DesktopSettings) => {
    edited = true;
    const normalized = { ...next, trayPanel: normalizeTrayPanelSettings(next.trayPanel) };
    settings.set(normalized);
    void Storage.setItem(STORAGE_KEY, JSON.stringify(normalized)).catch(() => undefined);
  };

  return {
    backgroundControl: () => settings().backgroundControl,
    trayPanel: () => settings().trayPanel,
    setBackgroundControl: (enabled: boolean) => update({ ...settings(), backgroundControl: enabled }),
    setShowNoiseLevels: (show: boolean) =>
      update({ ...settings(), trayPanel: { ...settings().trayPanel, showNoiseLevels: show } }),
    setControlEnabled: (control: TrayPanelControl, enabled: boolean) =>
      update({
        ...settings(),
        trayPanel: setTrayControlEnabled(settings().trayPanel, control, enabled),
      }),
    moveControl: (control: TrayPanelControl, delta: number) =>
      update({ ...settings(), trayPanel: moveTrayControl(settings().trayPanel, control, delta) }),
    resetTrayPanel: () => update({ ...settings(), trayPanel: DEFAULT_TRAY_PANEL_SETTINGS }),
  };
}

export interface DesktopSettingsStore {
  backgroundControl: () => boolean;
  trayPanel: () => TrayPanelSettings;
  setBackgroundControl: (enabled: boolean) => void;
  setShowNoiseLevels: (show: boolean) => void;
  setControlEnabled: (control: TrayPanelControl, enabled: boolean) => void;
  moveControl: (control: TrayPanelControl, delta: number) => void;
  resetTrayPanel: () => void;
}

export type TrayAvailability =
  | { status: "available" }
  | { status: "unavailable"; reason: string };

export function createTrayAvailabilityStore(): TrayAvailabilityStore {
  const availability = signal<TrayAvailability>({ status: "available" });

  const refresh = (): TrayAvailability => {
    let next: TrayAvailability;
    try {
      const report = OpenRoseTray.checkAvailability() as { available: boolean; reason?: string | null };
      next = report.available
        ? { status: "available" }
        : { status: "unavailable", reason: report.reason?.trim() || "系统托盘不可用" };
    } catch (error) {
      next = {
        status: "unavailable",
        reason: error instanceof Error ? error.message : "系统托盘不可用",
      };
    }
    availability.set(next);
    return next;
  };

  refresh();
  return { availability, refresh };
}

export interface TrayAvailabilityStore {
  availability: () => TrayAvailability;
  refresh: () => TrayAvailability;
}
