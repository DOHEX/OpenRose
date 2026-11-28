import { expect, open, test } from "nsh/test";
import type { App } from "nsh/test";

const TRAY_AVAILABLE = { available: true, reason: null };

async function openApp(tray: { available: boolean; reason: string | null } = TRAY_AVAILABLE) {
  const app = await open("../../App.tsx", {
    platform: "linux",
    width: 880,
    height: 700,
    modules: { "openrose-native": { "OpenRoseTray.checkAvailability": tray } },
  });
  await app.waitFor("id:headset-status-card");
  return app;
}

async function waitForQuit(app: App) {
  for (let attempt = 0; attempt < 60 && !app.quit(); attempt += 1) {
    await new Promise<void>((resolve) => setTimeout(resolve, 50));
  }
  expect(app.quit()).toBe(true);
}

async function openPanel(app: App) {
  await app.press("id:nav-tray");
  await app.press("id:btn-toggle-tray-panel");
  expect(app.windowIds()).toContain("tray-panel");
}

async function connectFromPanel(app: App) {
  await openPanel(app);
  await app.press("id:tray-btn-reconnect");
  await app.waitFor("id:tray-status-badge");
}

async function switchModel(app: App, search: string, title: string) {
  await app.press("id:nav-dev");
  await app.press("id:dev-model-select");
  await app.type("id:dev-model-search", search);
  await app.press(`text:${title}`);
  await app.press("id:btn-apply-dev");
  await app.waitFor("id:tray-status-badge");
  await app.press("id:nav-tray");
}

test("panel without a bonded headset guides to the add-headset flow", async () => {
  const app = await openApp();
  await app.press("id:nav-dev");
  await app.press("id:bonded-none");
  await openPanel(app);
  expect(app.shows("text:未添加耳机")).toBe(true);
  expect(app.shows("id:tray-btn-add-headset")).toBe(true);
  expect(app.shows("id:tray-noise-anc")).toBe(false);
  expect(app.shows("id:tray-btn-control-settings")).toBe(false);

  await app.press("id:tray-btn-add-headset");
  expect(app.windowIds()).not.toContain("tray-panel");
  expect(app.shows("id:add-headset-card")).toBe(true);
});

test("disconnected panel reconnects the current headset and shows state, battery, noise modes and default controls", async () => {
  const app = await openApp();
  await openPanel(app);
  expect(app.shows("id:tray-btn-reconnect")).toBe(true);
  expect(app.shows("id:tray-noise-anc")).toBe(false);

  await app.press("id:tray-btn-reconnect");
  await app.waitFor("id:tray-status-badge");
  expect(app.shows("id:tray-battery")).toBe(true);
  expect(app.shows("id:tray-noise-anc")).toBe(true);
  expect(app.shows("id:tray-control-game_mode")).toBe(true);
  expect(app.shows("id:tray-control-equalizer")).toBe(true);
  expect(app.shows("id:tray-control-spatial_audio")).toBe(false);
  expect(app.shows("id:tray-control-independent_mic")).toBe(false);
  expect(app.shows("id:tray-control-noise_level")).toBe(false);
  expect(app.shows("id:tray-btn-reconnect")).toBe(false);

  await app.press("id:nav-headset");
  await app.press("id:btn-disconnect");
  await app.waitFor("id:tray-btn-reconnect");
  expect(app.shows("id:tray-noise-anc")).toBe(false);
});

test("panel noise mode writes once and ignores choosing the confirmed mode again", async () => {
  const app = await openApp();
  await connectFromPanel(app);
  await app.press("id:nav-dev");
  await app.press("id:latency-400");
  await app.press("id:nav-tray");
  await app.press("id:tray-noise-normal");
  expect(app.shows("text:正在切换至关闭…")).toBe(true);
  await app.press("id:nav-headset");
  await app.waitFor("text:已确认：关闭");
  expect(app.shows("text:正在切换至关闭…")).toBe(false);

  await app.press("id:tray-noise-normal");
  expect(app.shows("text:正在切换至关闭…")).toBe(false);
  expect(app.shows("text:当前：关闭")).toBe(true);
});

test("panel game mode toggle is written through the control entry without errors", async () => {
  const app = await openApp();
  await connectFromPanel(app);
  await app.press("id:tray-toggle-game_mode");
  expect(app.shows("id:tray-command-error")).toBe(false);
  expect(app.shows("id:tray-control-equalizer")).toBe(true);
});

test("control settings enable, reorder and reset panel controls and intersect with the model", async () => {
  const app = await openApp();
  await app.press("id:nav-tray");
  expect(app.shows("text:降噪／通透档位 · 游戏模式 · EQ 预设")).toBe(true);

  await app.press("id:tray-setting-up-equalizer");
  expect(app.shows("text:降噪／通透档位 · EQ 预设 · 游戏模式")).toBe(true);
  await app.press("id:tray-setting-toggle-spatial_audio");
  expect(app.shows("text:降噪／通透档位 · EQ 预设 · 游戏模式 · 空间音频")).toBe(true);
  await app.press("id:tray-setting-toggle-noise_level");
  expect(app.shows("text:EQ 预设 · 游戏模式 · 空间音频")).toBe(true);

  await connectFromPanel(app);
  expect(app.shows("id:tray-control-spatial_audio")).toBe(false);

  await switchModel(app, "Astro X", "Astro X");
  expect(app.shows("id:tray-select-spatial-mode")).toBe(true);
  expect(app.shows("id:tray-control-equalizer")).toBe(true);

  await app.press("id:tray-setting-reset");
  expect(app.shows("text:降噪／通透档位 · 游戏模式 · EQ 预设")).toBe(true);
  expect(app.shows("id:tray-control-spatial_audio")).toBe(false);
});

test("independent microphone mode explains its prerequisite and noise levels follow the mode", async () => {
  const app = await openApp();
  await app.press("id:nav-tray");
  await app.press("id:tray-setting-toggle-independent_mic");
  await app.press("id:btn-toggle-tray-panel");
  await switchModel(app, "speakfeel2", "SPEAKFEEL (speakfeel2)");
  expect(app.shows("id:tray-toggle-independent_mic")).toBe(true);
  expect(app.shows("text:需先激活独立麦克风")).toBe(true);

  await switchModel(app, "EXPLORER", "EXPLORER");
  expect(app.shows("id:tray-control-noise_level")).toBe(true);
  await app.press("id:tray-setting-toggle-noise_level");
  expect(app.shows("id:tray-control-noise_level")).toBe(false);
});

test("closing the main window keeps the control connection in the tray and reopening raises it without the panel", async () => {
  const app = await openApp();
  await connectFromPanel(app);
  await app.window("main").pressClose();
  expect(app.quit()).toBe(false);
  expect(app.window("main").state()?.visible).toBe(false);
  expect(app.windowIds()).toContain("tray-panel");

  await app.reopen();
  expect(app.window("main").state()?.visible).toBe(true);
  expect(app.windowIds()).not.toContain("tray-panel");
  expect(app.shows("text:已连接")).toBe(true);
});

test("panel opens the main window, jumps to the control settings and quit ends the app", async () => {
  const app = await openApp();
  await connectFromPanel(app);
  await app.press("id:nav-headset");
  await app.window("main").pressClose();
  await app.press("id:tray-btn-open-main");
  expect(app.window("main").state()?.visible).toBe(true);
  expect(app.windowIds()).not.toContain("tray-panel");
  expect(app.shows("id:tray-settings-page")).toBe(false);

  await app.press("id:nav-tray");
  await app.press("id:btn-toggle-tray-panel");
  await app.press("id:nav-headset");
  await app.press("id:tray-btn-control-settings");
  expect(app.windowIds()).not.toContain("tray-panel");
  expect(app.shows("id:tray-settings-page")).toBe(true);

  await app.press("id:btn-toggle-tray-panel");
  await app.press("id:tray-btn-quit");
  await waitForQuit(app);
});

test("without background control, or without a tray, closing the window quits", async () => {
  const off = await openApp();
  await off.press("id:nav-tray");
  await off.press("id:toggle-background-control");
  await off.window("main").pressClose();
  await waitForQuit(off);

  const unavailable = await openApp({ available: false, reason: "没有已注册的系统托盘宿主" });
  await unavailable.press("id:nav-tray");
  expect(unavailable.shows("text:托盘不可用，关闭窗口将直接退出：没有已注册的系统托盘宿主")).toBe(true);
  await unavailable.window("main").pressClose();
  await waitForQuit(unavailable);
});

test("launch at login is hidden in development builds", async () => {
  const app = await openApp();
  await app.press("id:nav-tray");
  expect(app.shows("id:login-launch-row")).toBe(false);
  expect(app.shows("id:toggle-launch-at-login")).toBe(false);
});

test("launch at login defaults to off in installed builds, toggles autostart and disables unsupported hosts", async () => {
  const calls: Array<{ development: boolean; enabled: boolean }> = [];
  const app = await open("../../App.tsx", {
    platform: "linux",
    width: 880,
    height: 700,
    modules: {
      "openrose-native": {
        "OpenRoseTray.checkAvailability": TRAY_AVAILABLE,
        "OpenRoseLoginLaunch.status": {
          installed: true,
          supported: true,
          enabled: false,
          reason: null,
        },
        "OpenRoseLoginLaunch.setEnabled": (development: boolean, enabled: boolean) => {
          calls.push({ development, enabled });
          return { installed: true, supported: true, enabled, reason: null };
        },
      },
    },
  });
  await app.waitFor("id:headset-status-card");
  await app.press("id:nav-tray");
  expect(app.shows("id:login-launch-row")).toBe(true);
  expect(app.accessibility("id:toggle-launch-at-login")?.checked).toBe("false");

  await app.press("id:toggle-launch-at-login");
  await app.waitUntil(() => app.accessibility("id:toggle-launch-at-login")?.checked === "true");
  expect(calls).toEqual([{ development: true, enabled: true }]);

  await app.press("id:toggle-launch-at-login");
  await app.waitUntil(() => app.accessibility("id:toggle-launch-at-login")?.checked === "false");
  expect(calls).toEqual([
    { development: true, enabled: true },
    { development: true, enabled: false },
  ]);

  const unsupported = await open("../../App.tsx", {
    platform: "linux",
    width: 880,
    height: 700,
    modules: {
      "openrose-native": {
        "OpenRoseTray.checkAvailability": TRAY_AVAILABLE,
        "OpenRoseLoginLaunch.status": {
          installed: true,
          supported: false,
          enabled: false,
          reason: "当前系统暂不支持登录时启动",
        },
      },
    },
  });
  await unsupported.waitFor("id:headset-status-card");
  await unsupported.press("id:nav-tray");
  expect(unsupported.shows("id:login-launch-row")).toBe(true);
  expect(unsupported.shows("text:当前系统暂不支持登录时启动")).toBe(true);
  expect(unsupported.accessibility("id:toggle-launch-at-login")?.disabled).toBe(true);
});

test("login launch starts in tray only when background control and tray are available, otherwise opens main window", async () => {
  const trayOnly = await open("../../App.tsx", {
    platform: "linux",
    width: 880,
    height: 700,
    modules: {
      "openrose-native": {
        "OpenRoseTray.checkAvailability": TRAY_AVAILABLE,
        "OpenRoseLoginLaunch.wasStartedAtLogin": true,
      },
    },
  });
  expect(trayOnly.windowIds()).not.toContain("main");
  expect(trayOnly.window("main").state()).toBeNull();
  expect(trayOnly.shows("id:headset-status-card")).toBe(false);

  await trayOnly.reopen();
  await trayOnly.waitFor("id:headset-status-card");
  expect(trayOnly.windowIds()).toContain("main");
  expect(trayOnly.window("main").state()?.visible).toBe(true);

  const backgroundOff = await open("../../App.tsx", {
    platform: "linux",
    width: 880,
    height: 700,
    storage: {
      "desktop.settings": JSON.stringify({ backgroundControl: false }),
    },
    modules: {
      "openrose-native": {
        "OpenRoseTray.checkAvailability": TRAY_AVAILABLE,
        "OpenRoseLoginLaunch.wasStartedAtLogin": true,
      },
    },
  });
  await backgroundOff.waitFor("id:headset-status-card");
  expect(backgroundOff.windowIds()).toContain("main");
  expect(backgroundOff.window("main").state()?.visible).toBe(true);

  const noTray = await open("../../App.tsx", {
    platform: "linux",
    width: 880,
    height: 700,
    modules: {
      "openrose-native": {
        "OpenRoseTray.checkAvailability": { available: false, reason: "没有已注册的系统托盘宿主" },
        "OpenRoseLoginLaunch.wasStartedAtLogin": true,
      },
    },
  });
  await noTray.waitFor("id:headset-status-card");
  expect(noTray.windowIds()).toContain("main");
  expect(noTray.window("main").state()?.visible).toBe(true);
});

test("a remote holder paired while this device is not connected leaves the local tray state in charge", async () => {
  const app = await openApp();
  await app.press("id:nav-circulation");
  await app.press("id:btn-circ-dev-add");
  await app.waitFor("id:circ-dev-peer");
  await app.press("id:btn-circ-dev-open");
  await app.waitFor("id:circ-dev-code");
  await app.waitFor("id:circ-nearby-0");
  const code = app.texts().find((text) => /^\d{6}$/.test(text));
  expect(code).toBeDefined();
  await app.press("id:btn-circ-select-0");
  await app.type("id:circ-code-input", code!);
  await app.press("id:btn-circ-pair");
  await app.waitFor("id:circ-remote-name-0");
  expect(app.shows("text:EarFree i5")).toBe(true);

  await openPanel(app);
  expect(app.shows("text:本机未连接")).toBe(true);
  expect(app.shows("id:tray-btn-reconnect")).toBe(true);

  await app.press("id:tray-btn-reconnect");
  await app.waitFor("id:tray-status-badge");
  expect(app.shows("text:已连接")).toBe(true);
});

test("tray panel shows current system output and pulls a remote headset to this machine", async () => {
  const app = await openApp();
  await app.press("id:nav-circulation");
  await app.press("id:btn-circ-dev-add");
  await app.waitFor("id:circ-dev-peer");
  await app.press("id:btn-circ-dev-open");
  await app.waitFor("id:circ-dev-code");
  await app.waitFor("id:circ-nearby-0");
  const code = app.texts().find((text) => /^\d{6}$/.test(text));
  expect(code).toBeDefined();
  await app.press("id:btn-circ-select-0");
  await app.type("id:circ-code-input", code!);
  await app.press("id:btn-circ-pair");
  await app.waitFor("id:btn-circ-pull-0");

  await openPanel(app);
  await app.waitFor("id:tray-current-output");
  expect(app.shows("text:当前输出：内置扬声器")).toBe(true);
  expect(app.shows("id:tray-remote-card")).toBe(true);
  expect(app.shows("id:tray-btn-pull")).toBe(true);

  await app.press("id:tray-btn-pull");
  await app.waitFor("text:已接到这台电脑");
  expect(app.shows("id:tray-remote-card")).toBe(false);
  await app.waitFor("id:tray-status-badge");
  expect(app.shows("text:已连接")).toBe(true);
  expect(app.shows("text:当前输出：EarFree i5")).toBe(true);
});
