import { expect, test } from "nsh/test";
import type { RemoteHeadsetView } from "./circulation";
import type { ControlSnapshot, HeadsetCapabilities, NoiseFeedback } from "./headset";
import {
  buildTrayMenuEntries,
  closesMainWindowToTray,
  computeTraySummary,
  shouldCloseTrayPanelOnBlur,
  trayIconState,
  trayTooltip,
  usesNativeCloseOnBlur,
} from "./tray";
import type { TrayHeadsetController, TrayMenuActions } from "./tray";
import {
  DEFAULT_TRAY_PANEL_SETTINGS,
  moveTrayControl,
  normalizeTrayPanelSettings,
  setTrayControlEnabled,
  supportedTrayControls,
  trayPanelSettingsSummary,
} from "./trayPanelSettings";
import {
  normalizeLoginLaunchStatus,
  shouldStartMainWindowAtLaunch,
} from "./loginLaunch";

function capabilities(overrides: Partial<HeadsetCapabilities> = {}): HeadsetCapabilities {
  return {
    readable_fields: [],
    writable_settings: ["NoiseMode"],
    noise_control: {
      modes: ["anc", "normal", "transparency"],
      cycle_modes: [],
      anc_level: null,
      transparency_level: null,
      minimum_cycle_selections: 0,
    },
    equalizer: null,
    touch_control: null,
    tap_control: null,
    audio_control: null,
    prompt_volume_levels: [],
    gain_levels: [],
    hearing_protection_levels: [],
    bass_boost_levels: [],
    prompt_languages: [],
    spatial_modes: [],
    spatial_contents: [],
    ...overrides,
  };
}

function snapshot(options: {
  connected: boolean;
  sessionId?: number;
  bonded?: boolean;
  noiseMode?: string;
}): ControlSnapshot {
  const bonded = options.bonded === false ? [] : [{ id: "sim:1", name: null, profile: "I5_ZT", classic_address: null }];
  return {
    revision: 1,
    connection: options.connected
      ? { Connected: { headset_id: "sim:1", session_id: options.sessionId ?? 1, profile: "I5_ZT" } }
      : "Disconnected",
    bonded_headsets: bonded,
    headset: options.connected
      ? {
          headset_id: "sim:1",
          session_id: options.sessionId ?? 1,
          readings: {
            profile: "I5_ZT",
            capabilities: capabilities(),
            fields: {
              NoiseMode: { Known: { value: { Text: options.noiseMode ?? "anc" }, raw_hex: "" } },
            },
          },
          classic_address: null,
        }
      : null,
    last_error: null,
    setting_operations: [],
    audio_headset_id: null,
    connection_target: null,
    selected_headset_id: null,
    connection_block_reason: null,
  };
}

function controller(
  snap: ControlSnapshot,
  feedback: NoiseFeedback = { kind: "idle" },
): TrayHeadsetController {
  const connected = snap.headset !== null;
  return {
    snapshot: () => snap,
    catalogById: new Map([
      [
        "I5_ZT",
        {
          id: "I5_ZT",
          displayName: "EarFree i5",
          shortLabel: "i5",
          versionName: null,
          family: "Zt",
          familyLabel: "ZT",
          batteryLayout: "EarbudsAndCase",
          defaultFirmware: "125",
          firmwareHint: "",
        },
      ],
    ]),
    batterySlots: () => [
      { id: "left", label: "左耳", valueText: "95%", caption: null, low: false },
      { id: "right", label: "右耳", valueText: "98%", caption: null, low: false },
    ],
    noiseModes: () => ["anc", "transparency", "normal"],
    canControlNoiseMode: () => connected,
    noiseFeedback: () => feedback,
    connect: async () => undefined,
    disconnect: async () => undefined,
    setNoiseMode: async () => undefined,
    executeCommand: async () => undefined,
  };
}

function recorder() {
  const noiseWrites: [string, number][] = [];
  const reconnects: string[] = [];
  const actions: TrayMenuActions = {
    togglePanel: () => undefined,
    openMain: () => undefined,
    openAddHeadset: () => undefined,
    reconnect: (id) => void reconnects.push(id),
    selectNoiseMode: (mode, sessionId) => void noiseWrites.push([mode, sessionId]),
    quit: () => undefined,
  };
  return { noiseWrites, reconnects, actions };
}

test("tray icon follows the control connection and the tooltip starts with the app name", () => {
  const connected = computeTraySummary(controller(snapshot({ connected: true })));
  const disconnected = computeTraySummary(controller(snapshot({ connected: false })));
  expect(trayIconState(connected)).toBe("connected");
  expect(trayIconState(disconnected)).toBe("disconnected");
  expect(trayTooltip(false, connected)).toBe("OpenRose");
  expect(trayTooltip(true, connected)).toBe("EarFree i5 · 已连接\n左 95% · 右 98%");
  expect(trayTooltip(true, disconnected)).toBe("EarFree i5 · 本机未连接");
});

const REMOTE_HOLDER: RemoteHeadsetView = {
  peerId: "p1",
  peerName: "模拟手机",
  available: true,
  holds: true,
  headsetId: "sim:1",
  headsetName: "EarFree i7",
  batteryText: "左 80% · 右 78% · 盒 90%",
  confirmedMode: "anc",
  modes: ["anc", "normal"],
  pendingMode: null,
  canSwitch: true,
  session: { peer_id: "p1", headset_id: "sim:1", token: "t" },
};

test("a paired peer holding a headset shows the remote icon and its headset in the tooltip when this device is not connected", () => {
  const remote = computeTraySummary(controller(snapshot({ connected: false })), REMOTE_HOLDER);
  expect(trayIconState(remote)).toBe("remote");
  expect(trayTooltip(false, remote)).toBe("OpenRose");
  expect(trayTooltip(true, remote)).toBe("EarFree i7 · 模拟手机\n左 80% · 右 78% · 盒 90%");
});

test("the local connection wins over a remote holder", () => {
  const local = computeTraySummary(controller(snapshot({ connected: true })), REMOTE_HOLDER);
  expect(trayIconState(local)).toBe("connected");
  expect(trayTooltip(true, local)).toBe("EarFree i5 · 已连接\n左 95% · 右 98%");
});

test("without a remote holder the tray state is unchanged", () => {
  const none = computeTraySummary(controller(snapshot({ connected: false })), null);
  expect(trayIconState(none)).toBe("disconnected");
  expect(trayTooltip(true, none)).toBe("EarFree i5 · 本机未连接");
});

test("tray menu lists state, battery and noise modes and binds them to the state it was built from", () => {
  const { noiseWrites, actions } = recorder();
  const summary = computeTraySummary(controller(snapshot({ connected: true, sessionId: 7, noiseMode: "anc" })));
  const entries = buildTrayMenuEntries(summary, false, actions);
  expect(entries.filter((entry) => entry.kind === "label").map((entry) => entry.label)).toEqual([
    "EarFree i5 · 已连接",
    "左 95% · 右 98%",
  ]);
  const modes = entries.filter((entry) => entry.kind === "noise_mode");
  expect(modes.map((entry) => [entry.label, entry.checked])).toEqual([
    ["降噪", true],
    ["通透", false],
    ["关闭", false],
  ]);

  const later = computeTraySummary(controller(snapshot({ connected: true, sessionId: 8, noiseMode: "normal" })));
  buildTrayMenuEntries(later, false, actions);
  modes[1].onSelect?.();
  expect(noiseWrites).toEqual([["transparency", 7]]);
  expect(entries.map((entry) => entry.label)).toContain("打开托盘面板");
  expect(entries.map((entry) => entry.label)).toContain("打开 OpenRose");
  expect(entries.map((entry) => entry.label)).toContain("退出");
});

test("choosing the already selected noise mode writes nothing", () => {
  const { noiseWrites, actions } = recorder();
  const summary = computeTraySummary(controller(snapshot({ connected: true, sessionId: 3, noiseMode: "anc" })));
  const selected = buildTrayMenuEntries(summary, false, actions).filter(
    (entry) => entry.kind === "noise_mode" && entry.checked,
  );
  expect(selected.length).toBe(1);
  selected[0].onSelect?.();
  expect(noiseWrites).toEqual([]);
});

test("a pending noise write disables the modes and shows its progress", () => {
  const { actions } = recorder();
  const summary = computeTraySummary(
    controller(snapshot({ connected: true }), { kind: "in_progress", mode: "normal", stage: "Submitted" }),
  );
  const entries = buildTrayMenuEntries(summary, false, actions);
  expect(entries.filter((entry) => entry.kind === "noise_mode").every((entry) => entry.disabled)).toBe(true);
  expect(entries.map((entry) => entry.label)).toContain("正在切换至关闭…");
});

test("disconnected menu offers reconnect, and no bonded headset offers adding one", () => {
  const { reconnects, actions } = recorder();
  const reconnectable = computeTraySummary(controller(snapshot({ connected: false })));
  const entries = buildTrayMenuEntries(reconnectable, true, actions);
  expect(entries.map((entry) => entry.label)).toContain("关闭托盘面板");
  entries.find((entry) => entry.label === "重新连接")?.onSelect?.();
  expect(reconnects).toEqual(["sim:1"]);
  expect(entries.map((entry) => entry.label)).not.toContain("添加耳机");

  const empty = computeTraySummary(controller(snapshot({ connected: false, bonded: false })));
  expect(empty.addHeadset).toBe(true);
  expect(empty.reconnectTarget).toBe(null);
  expect(buildTrayMenuEntries(empty, false, actions).map((entry) => entry.label)).toContain("添加耳机");
});

test("the Linux panel never uses native close-on-blur and ignores blur until the pointer entered", () => {
  expect(usesNativeCloseOnBlur("linux")).toBe(false);
  expect(usesNativeCloseOnBlur("macos")).toBe(true);
  expect(usesNativeCloseOnBlur("windows")).toBe(true);
  const base = { os: "linux", focused: false, pointerEntered: false, popupsOpen: 0 };
  expect(shouldCloseTrayPanelOnBlur(base)).toBe(false);
  expect(shouldCloseTrayPanelOnBlur({ ...base, pointerEntered: true })).toBe(true);
  expect(shouldCloseTrayPanelOnBlur({ ...base, pointerEntered: true, focused: true })).toBe(false);
  expect(shouldCloseTrayPanelOnBlur({ ...base, pointerEntered: true, popupsOpen: 1 })).toBe(false);
  expect(shouldCloseTrayPanelOnBlur({ ...base, os: "macos", pointerEntered: true })).toBe(false);
});

test("closing the main window hides it only with background control and an available tray", () => {
  expect(closesMainWindowToTray(true, { status: "available" })).toBe(true);
  expect(closesMainWindowToTray(false, { status: "available" })).toBe(false);
  expect(closesMainWindowToTray(true, { status: "unavailable" })).toBe(false);
});

test("panel controls default to the first three, reorder and intersect with model capabilities", () => {
  expect(DEFAULT_TRAY_PANEL_SETTINGS.showNoiseLevels).toBe(true);
  expect(DEFAULT_TRAY_PANEL_SETTINGS.enabledControls).toEqual(["game_mode", "equalizer"]);
  expect(trayPanelSettingsSummary(DEFAULT_TRAY_PANEL_SETTINGS)).toBe("降噪／通透档位 · 游戏模式 · EQ 预设");

  const full = capabilities({
    writable_settings: ["GameMode", "Eq", "SpatialMode", "IndependentMicMode"],
    equalizer: { presets: ["pop"], custom: null },
    spatial_modes: ["off", "fixed"],
  });
  const noSpatial = capabilities({
    writable_settings: ["GameMode", "Eq"],
    equalizer: { presets: ["pop"], custom: null },
  });
  expect(supportedTrayControls(DEFAULT_TRAY_PANEL_SETTINGS, full)).toEqual(["game_mode", "equalizer"]);

  let settings = setTrayControlEnabled(DEFAULT_TRAY_PANEL_SETTINGS, "spatial_audio", true);
  settings = setTrayControlEnabled(settings, "independent_mic", true);
  expect(supportedTrayControls(settings, full)).toEqual([
    "game_mode",
    "equalizer",
    "spatial_audio",
    "independent_mic",
  ]);
  expect(supportedTrayControls(settings, noSpatial)).toEqual(["game_mode", "equalizer"]);
  expect(supportedTrayControls(settings, null)).toEqual([]);

  settings = moveTrayControl(settings, "independent_mic", 1);
  expect(settings.controlOrder).toEqual(["game_mode", "equalizer", "spatial_audio", "independent_mic"]);
  settings = moveTrayControl(settings, "independent_mic", -1);
  settings = moveTrayControl(settings, "independent_mic", -1);
  expect(settings.controlOrder).toEqual(["game_mode", "independent_mic", "equalizer", "spatial_audio"]);
  expect(supportedTrayControls(settings, full)).toEqual([
    "game_mode",
    "independent_mic",
    "equalizer",
    "spatial_audio",
  ]);
  expect(moveTrayControl(settings, "game_mode", -1).controlOrder[0]).toBe("game_mode");
  expect(normalizeTrayPanelSettings({ showNoiseLevels: false, enabledControls: [], controlOrder: ["equalizer"] }).controlOrder)
    .toEqual(["equalizer", "game_mode", "spatial_audio", "independent_mic"]);
});

test("login launch status normalizes installed, unsupported and off-by-default states", () => {
  expect(normalizeLoginLaunchStatus({ installed: false, supported: true, enabled: false })).toEqual({
    kind: "hidden",
    reason: "仅安装版可设置登录时启动",
  });
  expect(
    normalizeLoginLaunchStatus({
      installed: true,
      supported: false,
      enabled: false,
      reason: "当前系统暂不支持登录时启动",
    }),
  ).toEqual({
    kind: "unsupported",
    reason: "当前系统暂不支持登录时启动",
  });
  expect(normalizeLoginLaunchStatus({ installed: true, supported: true, enabled: false })).toEqual({
    kind: "ready",
    enabled: false,
  });
  expect(normalizeLoginLaunchStatus({ installed: true, supported: true, enabled: true })).toEqual({
    kind: "ready",
    enabled: true,
  });
});

test("login launch skips the main window only when started at login with background control and an available tray", () => {
  expect(
    shouldStartMainWindowAtLaunch({
      startedAtLogin: false,
      backgroundControl: true,
      availability: { status: "available" },
    }),
  ).toBe(true);
  expect(
    shouldStartMainWindowAtLaunch({
      startedAtLogin: true,
      backgroundControl: true,
      availability: { status: "available" },
    }),
  ).toBe(false);
  expect(
    shouldStartMainWindowAtLaunch({
      startedAtLogin: true,
      backgroundControl: false,
      availability: { status: "available" },
    }),
  ).toBe(true);
  expect(
    shouldStartMainWindowAtLaunch({
      startedAtLogin: true,
      backgroundControl: true,
      availability: { status: "unavailable", reason: "no watcher" },
    }),
  ).toBe(true);
});

test("tray summary and menu include current output and only show sound settings when available", () => {
  let openedSettings = 0;
  const { actions } = recorder();
  const withSettings = computeTraySummary(controller(snapshot({ connected: true })), null, {
    currentOutput: "EarFree i5",
    soundSettingsAvailable: true,
  });
  expect(withSettings.currentOutput).toBe("EarFree i5");
  expect(withSettings.soundSettingsAvailable).toBe(true);
  const entriesWithSettings = buildTrayMenuEntries(withSettings, false, {
    ...actions,
    openSoundSettings: () => {
      openedSettings += 1;
    },
  });
  expect(entriesWithSettings.map((entry) => entry.label)).toContain("当前输出：EarFree i5");
  const settingsEntry = entriesWithSettings.find((entry) => entry.label === "打开声音设置");
  expect(settingsEntry).toBeDefined();
  settingsEntry?.onSelect?.();
  expect(openedSettings).toBe(1);

  const withoutSettings = computeTraySummary(controller(snapshot({ connected: false })), REMOTE_HOLDER, {
    currentOutput: "Built-in Speaker",
    soundSettingsAvailable: false,
  });
  expect(withoutSettings.remote?.address).toBe("sim:1");
  const entriesWithoutSettings = buildTrayMenuEntries(withoutSettings, false, {
    ...actions,
    openSoundSettings: () => {
      openedSettings += 1;
    },
  });
  expect(entriesWithoutSettings.map((entry) => entry.label)).toContain("当前输出：Built-in Speaker");
  expect(entriesWithoutSettings.map((entry) => entry.label)).not.toContain("打开声音设置");
});
