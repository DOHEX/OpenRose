import { expect, open, test } from "nsh/test";

const WINDOW = { platform: "linux", width: 880, height: 700 } as const;

async function openApp() {
  const app = await open("./App.tsx", WINDOW);
  await app.waitFor("id:headset-status-card");
  return app;
}

test("unconnected dashboard shows the current headset, its model profile and reconnect guidance", async () => {
  const app = await openApp();
  expect(app.shows("id:connection-badge")).toBe(true);
  expect(app.shows("text:未连接")).toBe(true);
  expect(app.shows("text:EarFree i5")).toBe(true);
  expect(app.texts().some((text) => text.includes("耳机开盖后将自动连接"))).toBe(true);
  expect(app.shows("id:model-config-caption")).toBe(true);
  expect(app.shows("id:bonded-headset-row-0")).toBe(true);
  expect(app.shows("text:当前耳机")).toBe(true);
  expect(app.shows("id:connection-phase")).toBe(true);
});

test("switching between bonded headsets selects the current headset through the core", async () => {
  const app = await openApp();
  await app.press("id:nav-dev");
  await app.press("id:bonded-two");
  await app.press("id:nav-headset");
  await app.waitFor("id:bonded-headset-row-1");
  expect(app.shows("id:current-headset-badge-0")).toBe(true);
  expect(app.shows("id:btn-switch-headset-1")).toBe(true);

  await app.press("id:btn-switch-headset-1");
  await app.waitFor("id:current-headset-badge-1");
  expect(app.shows("id:btn-switch-headset-0")).toBe(true);
  expect(app.shows("id:current-headset-badge-0")).toBe(false);
});

test("nothing bonded guides into the add-headset flow", async () => {
  const app = await openApp();
  await app.press("id:nav-dev");
  await app.press("id:bonded-none");
  await app.press("id:nav-headset");
  await app.waitFor("id:empty-bonded-onboarding");
  expect(app.shows("id:headset-status-card")).toBe(false);

  await app.press("id:btn-go-add-headset");
  await app.waitFor("id:add-headset-card");
  expect(app.shows("id:btn-scan-toggle")).toBe(true);
});

test("add headset scans supported headsets and succeeds when control is ready, not when audio is paired", async () => {
  const app = await openApp();
  await app.press("id:nav-dev");
  await app.press("id:bonded-none");
  await app.press("id:nav-add-headset");
  await app.press("id:btn-scan-toggle");
  await app.waitFor("text:停止搜索");

  await app.press("id:nav-dev");
  await app.press("id:btn-dev-advertise-supported");
  await app.press("id:btn-dev-advertise-unsupported");
  await app.press("id:nav-add-headset");
  await app.waitFor("id:btn-add-connect-0");
  expect(app.shows("text:HeadFeel")).toBe(true);
  expect(app.shows("id:add-unsupported-card")).toBe(true);
  expect(app.shows("text:Other BLE")).toBe(true);
  expect(app.shows("id:add-control-ready")).toBe(false);

  await app.press("id:btn-add-connect-0");
  await app.waitFor("id:add-control-ready");
  expect(app.shows("id:add-control-ready")).toBe(true);
  expect(app.shows("id:badge-add-ready-0")).toBe(true);

  await app.press("id:btn-add-done");
  await app.waitFor("id:headset-status-card");
  expect(app.shows("text:已连接")).toBe(true);
});

test("missing Bluetooth permission blocks connecting and scanning with its own text", async () => {
  const app = await openApp();
  await app.press("id:nav-dev");
  await app.press("id:platform-permission");
  await app.press("id:nav-headset");
  await app.waitFor("id:connection-block-banner");
  expect(app.shows("text:需要蓝牙权限")).toBe(true);

  await app.press("id:nav-add-headset");
  await app.waitFor("id:add-block-banner");
  expect(app.shows("text:需要蓝牙权限")).toBe(true);
});

test("Bluetooth turned off blocks connecting with its own text", async () => {
  const app = await openApp();
  await app.press("id:nav-dev");
  await app.press("id:platform-bluetooth-off");
  await app.press("id:nav-headset");
  await app.waitFor("id:connection-block-banner");
  expect(app.shows("text:蓝牙已关闭")).toBe(true);
});

test("unavailable transport shows its detail and recovers when the platform is fine again", async () => {
  const app = await openApp();
  await app.press("id:nav-dev");
  await app.press("id:platform-transport-unavailable");
  await app.press("id:nav-headset");
  await app.waitFor("id:connection-block-banner");
  expect(app.shows("text:BlueZ 运行环境缺少依赖：bluez")).toBe(true);

  await app.press("id:nav-dev");
  await app.press("id:platform-ok");
  await app.press("id:nav-headset");
  await app.waitFor("id:btn-connect");
  expect(app.shows("id:connection-block-banner")).toBe(false);
});

test("headset info page shows model profile, firmware and control transport", async () => {
  const app = await openApp();
  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:nav-info");
  await app.waitFor("id:info-model-profile");
  expect(app.shows("text:EarFree i5")).toBe(true);
  expect(app.shows("text:125")).toBe(true);
  expect(app.shows("text:BLE GATT")).toBe(true);
  expect(app.shows("text:此型号使用 BLE 控制")).toBe(true);
});

test("diagnostics page shows platform state, connection phase, operation log and raw readings", async () => {
  const app = await openApp();
  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  await app.press("id:noise-mode-normal");
  await app.waitFor("text:已确认：关闭");

  await app.press("id:nav-diagnostics");
  await app.waitFor("id:diag-platform-card");
  expect(app.shows("text:Linux")).toBe(true);
  expect(app.shows("id:diag-conn-phase")).toBe(true);
  expect(app.shows("id:diag-operations-card")).toBe(true);
  expect(app.shows("text:降噪模式 · #1")).toBe(true);
  expect(app.shows("id:diag-readings-card")).toBe(true);

  await app.press("id:btn-toggle-raw-data");
  await app.waitFor("id:diag-raw-data-panel");
});

test("every flow is keyboard operable and controls have readable names", async () => {
  const app = await openApp();
  expect(app.shows("label:主导航")).toBe(true);
  expect(app.shows("label:连接当前耳机")).toBe(true);
  expect(app.shows("label:进入添加耳机流程")).toBe(true);

  await app.press("id:nav-add-headset");
  expect(app.shows("label:开始搜索附近耳机")).toBe(true);
  await app.press("id:nav-headset");
  await app.keyboard.press("tab");
  await app.keyboard.press("down");
  await app.keyboard.press("enter");
  await app.waitFor("id:add-headset-card");
});
