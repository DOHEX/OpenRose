import { expect, open, test } from "nsh/test";
import type { App } from "nsh/test";

async function openConnected(): Promise<App> {
  const app = await open("./App.tsx", { platform: "linux", width: 1400, height: 3200 });
  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:nav-settings");
  return app;
}

async function applySimulator(app: App, displayName: string, firmware: string) {
  await app.press("id:nav-dev");
  await app.clear("id:dev-firmware-input");
  await app.type("id:dev-firmware-input", firmware);
  await app.press("id:btn-apply-dev");
  await app.waitUntil(() =>
    app.texts().some((text) => text.startsWith(`${displayName} · ${firmware} (`)),
  );
  await app.waitFor("text:已连接");
  await app.press("id:nav-settings");
}

async function switchModel(app: App, search: string, displayName: string, firmware: string) {
  await app.press("id:nav-dev");
  await app.press("id:dev-model-select");
  await app.type("id:dev-model-search", search);
  await app.press(`text:${displayName}`);
  await app.waitUntil(() => app.shows("id:dev-firmware-input"));
  await applySimulator(app, displayName, firmware);
}

test("settings page asks to connect before showing capability-gated settings", async () => {
  const app = await open("./App.tsx", { platform: "linux", width: 1400, height: 3200 });
  await app.press("id:nav-settings");
  expect(app.shows("id:settings-disconnected")).toBe(true);
  expect(app.shows("id:setting-game-mode")).toBe(false);
});

test("segmented settings show in-progress then confirmed and depend on the confirmed noise mode", async () => {
  const app = await openConnected();
  expect(app.shows("id:setting-anc-level-3")).toBe(false);

  await app.press("id:nav-dev");
  await app.press("id:latency-400");
  await app.press("id:nav-settings");
  await app.press("id:setting-noise-mode-anc");
  expect(app.shows("text:降噪模式写入中…")).toBe(true);
  await app.waitFor("text:降噪模式已确认");

  await app.press("id:setting-anc-level-3");
  expect(app.shows("text:降噪档位写入中…")).toBe(true);
  await app.waitFor("text:降噪档位已确认");

  await app.press("id:setting-prompt-volume-2");
  await app.waitFor("text:提示音音量已确认");
});

test("switch, checkbox, radio and select settings write and confirm", async () => {
  const app = await openConnected();

  await app.press("id:setting-game-mode");
  await app.waitFor("text:游戏模式已确认");

  await app.press("id:setting-noise-cycle-anc");
  await app.waitFor("text:降噪轮换已确认");

  await app.press("id:setting-eq-preset-rock");
  await app.waitFor("text:预设均衡器已确认");

  await app.press("id:setting-touch-slot-left-single");
  await app.press("text:下一曲");
  await app.waitFor("text:触控手势已确认");
});

test("custom equalizer writes on release and only for firmware that supports it", async () => {
  const app = await openConnected();
  await switchModel(app, "HeadFeel", "HeadFeel", "125");
  expect(app.shows("id:setting-eq-preset-hifi")).toBe(true);
  expect(app.shows("id:setting-eq-band-0")).toBe(false);
  expect(app.shows("id:setting-gain-1")).toBe(false);

  await applySimulator(app, "HeadFeel", "130");
  await app.press("id:setting-eq-preset-custom");
  await app.waitFor("id:setting-eq-band-0");
  await app.drag("id:setting-eq-band-0", { x: 40 });
  await app.waitFor("text:自定义均衡器已确认");

  await app.press("id:setting-gain-2");
  await app.waitFor("text:增益档位已确认");
});

test("spatial content stays blocked until a spatial mode is on", async () => {
  const app = await openConnected();
  await switchModel(app, "Astro", "Astro X", "152");
  expect(app.enabled("id:setting-spatial-content-movie")).toBe(false);
  expect(app.shows("text:请先开启空间模式")).toBe(true);

  await app.press("id:setting-spatial-mode-head_tracking");
  await app.waitFor("text:空间模式已确认");
  expect(app.enabled("id:setting-spatial-content-movie")).toBe(true);
  await app.press("id:setting-spatial-content-movie");
  await app.waitFor("text:空间音频内容已确认");

  await app.press("id:setting-tap-enabled");
  await app.waitFor("text:轻拍开关已确认");
});

test("independent microphone mode needs activation first", async () => {
  const app = await openConnected();
  await switchModel(app, "SPEAKFEEL", "SPEAKFEEL (speakfeel2)", "125");
  expect(app.enabled("id:setting-independent-mic-mode")).toBe(false);
  expect(app.shows("text:请先激活独立麦克风")).toBe(true);

  await app.press("id:setting-independent-mic-activation");
  await app.waitFor("text:激活独立麦克风已确认");
  expect(app.enabled("id:setting-independent-mic-mode")).toBe(true);
  await app.press("id:setting-independent-mic-mode");
  await app.waitFor("text:独立麦克风模式已确认");
});

test("hearing protection thresholds appear from the supporting firmware on", async () => {
  const app = await openConnected();
  await switchModel(app, "i7", "EarFeel i7", "212");
  expect(app.shows("id:setting-hearing-protection")).toBe(false);
  expect(app.shows("id:setting-gain-2")).toBe(false);

  await applySimulator(app, "EarFeel i7", "304");
  await app.press("id:setting-hearing-protection");
  await app.press("text:90 dB");
  await app.waitFor("text:听力保护阈值已确认");
});

test("F8 and RS Common models offer prompt language and transparency levels", async () => {
  const app = await openConnected();
  await switchModel(app, "EXPLORER", "EXPLORER", "125");
  await app.press("id:setting-prompt-volume-3");
  await app.waitFor("text:提示音音量已确认");

  await switchModel(app, "Cambrian", "Cambrian", "125");
  await app.press("id:setting-noise-mode-transparency");
  await app.waitFor("text:降噪模式已确认");
  await app.press("id:setting-transparency-level-3");
  await app.waitFor("text:通透档位已确认");
});
