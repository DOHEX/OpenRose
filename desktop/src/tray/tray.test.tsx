import { expect, open, test } from "nsh/test";
import type { App } from "nsh/test";

const TRAY_AVAILABLE = { available: true, reason: null };

async function openApp(tray: { available: boolean; reason: string | null } = TRAY_AVAILABLE) {
  return open("../../App.tsx", {
    platform: "linux",
    width: 720,
    height: 640,
    modules: { "openrose-native": { "OpenRoseTray.checkAvailability": tray } },
  });
}

async function waitForQuit(app: App) {
  for (let attempt = 0; attempt < 60 && !app.quit(); attempt += 1) {
    await new Promise<void>((resolve) => setTimeout(resolve, 50));
  }
  expect(app.quit()).toBe(true);
}


async function switchModel(app: App, search: string, title: string) {
  await app.press("id:dev-model-select");
  await app.type("id:dev-model-search", search);
  await app.press(`text:${title}`);
  await app.press("id:btn-apply-dev");
  await app.waitFor("text:已连接");
}

test("tray panel guides to adding a headset, then shows state, battery, noise modes and default controls", async () => {
  const app = await openApp();
  expect(app.windowIds()).toEqual(["main"]);
  await app.press("id:btn-toggle-tray-panel");
  expect(app.windowIds()).toEqual(["main", "tray-panel"]);
  expect(app.shows("text:未添加耳机")).toBe(true);
  expect(app.shows("id:tray-btn-add-headset")).toBe(true);
  expect(app.shows("id:tray-noise-anc")).toBe(false);

  await app.press("id:btn-connect");
  await app.waitFor("id:tray-status-badge");
  expect(app.shows("id:tray-battery")).toBe(true);
  expect(app.shows("id:tray-noise-anc")).toBe(true);
  expect(app.shows("id:tray-control-game_mode")).toBe(true);
  expect(app.shows("id:tray-control-equalizer")).toBe(true);
  expect(app.shows("id:tray-control-spatial_audio")).toBe(false);
  expect(app.shows("id:tray-control-independent_mic")).toBe(false);
  expect(app.shows("id:tray-control-noise_level")).toBe(false);
});

test("disconnected panel reconnects the current headset", async () => {
  const app = await openApp();
  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:btn-toggle-tray-panel");
  await app.press("id:btn-disconnect");
  await app.waitFor("id:tray-btn-reconnect");
  expect(app.shows("id:tray-noise-anc")).toBe(false);
  await app.press("id:tray-btn-reconnect");
  await app.waitFor("id:tray-status-badge");
});

test("panel noise mode writes once and ignores choosing the confirmed mode again", async () => {
  const app = await openApp();
  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:latency-400");
  await app.press("id:btn-toggle-tray-panel");
  await app.press("id:tray-noise-normal");
  expect(app.shows("text:正在切换至关闭…")).toBe(true);
  await app.waitFor("text:已确认：关闭");
  expect(app.shows("text:正在切换至关闭…")).toBe(false);

  await app.press("id:tray-noise-normal");
  expect(app.shows("text:正在切换至关闭…")).toBe(false);
  expect(app.shows("text:当前：关闭")).toBe(true);
});

test("panel controls toggle and EQ presets are written through the control entry", async () => {
  const app = await openApp();
  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:btn-toggle-tray-panel");
  await app.press("id:tray-toggle-game_mode");
  await app.waitFor("id:tray-toggle-game_mode");
  expect(app.shows("id:tray-command-error")).toBe(false);
});

test("control settings enable, reorder and reset panel controls and intersect with the model", async () => {
  const app = await openApp();
  expect(app.shows("text:降噪／通透档位 · 游戏模式 · EQ 预设")).toBe(true);

  await app.press("id:tray-setting-up-equalizer");
  expect(app.shows("text:降噪／通透档位 · EQ 预设 · 游戏模式")).toBe(true);
  await app.press("id:tray-setting-toggle-spatial_audio");
  expect(app.shows("text:降噪／通透档位 · EQ 预设 · 游戏模式 · 空间音频")).toBe(true);
  await app.press("id:tray-setting-toggle-noise_level");
  expect(app.shows("text:EQ 预设 · 游戏模式 · 空间音频")).toBe(true);

  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:btn-toggle-tray-panel");
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
  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:btn-toggle-tray-panel");
  await app.window("main").pressClose();
  expect(app.quit()).toBe(false);
  expect(app.window("main").state()?.visible).toBe(false);
  expect(app.windowIds()).toContain("tray-panel");

  await app.reopen();
  expect(app.window("main").state()?.visible).toBe(true);
  expect(app.windowIds()).not.toContain("tray-panel");
  expect(app.shows("text:已连接")).toBe(true);
});

test("panel opens the main window and quit from the panel ends the app", async () => {
  const app = await openApp();
  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:btn-toggle-tray-panel");
  await app.window("main").pressClose();
  await app.press("id:tray-btn-open-main");
  expect(app.window("main").state()?.visible).toBe(true);
  expect(app.windowIds()).not.toContain("tray-panel");

  await app.press("id:btn-toggle-tray-panel");
  await app.press("id:tray-btn-quit");
  await waitForQuit(app);
});

test("without background control, or without a tray, closing the window quits", async () => {
  const off = await openApp();
  await off.press("id:toggle-background-control");
  await off.window("main").pressClose();
  await waitForQuit(off);

  const unavailable = await openApp({ available: false, reason: "没有已注册的系统托盘宿主" });
  expect(unavailable.shows("text:托盘不可用，关闭窗口将直接退出：没有已注册的系统托盘宿主")).toBe(true);
  await unavailable.window("main").pressClose();
  await waitForQuit(unavailable);
});
