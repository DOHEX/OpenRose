import type { Display } from "nsh/desktop";
import { expect, open, test } from "nsh/test";
import { computeTrayPanelPosition } from "./src/panelPosition";

test("main window reads Rust native module state and toggles ANC mode", async () => {
  const app = await open("./App.tsx", { platform: "linux", width: 560, height: 560 });
  expect(app.texts()).toContain("ROSE EarFree i5");
  expect(app.texts()).toContain("ROSE EarFree i5 · L85% R80% 盒95% · 降噪");

  await app.press("anc-主窗口-通透");
  expect(app.texts()).toContain("ROSE EarFree i5 · L85% R80% 盒95% · 通透");

  await app.press("btn-drain-battery");
  expect(app.texts()).toContain("ROSE EarFree i5 · L80% R75% 盒95% · 通透");
});

test("closing main window hides it instead of terminating the app", async () => {
  const app = await open("./App.tsx", { platform: "linux", width: 560, height: 560 });
  const main = app.window("main");
  expect(main.state()).toMatchObject({ visible: true });

  await main.pressClose();
  expect(main.state()).toMatchObject({ visible: false });
});

test("opens tray panel, closes it on Escape, and suppresses immediate reopen after blur", async () => {
  const app = await open("./App.tsx", { platform: "linux", width: 560, height: 560 });
  expect(app.window("tray-panel").state()).toBeNull();

  await app.press("btn-toggle-tray-panel");
  expect(app.window("tray-panel").state()).toMatchObject({ visible: true });

  await app.keyboard.press("Escape");
  expect(app.window("tray-panel").state()).toBeNull();

  await app.press("btn-toggle-tray-panel");
  expect(app.window("tray-panel").state()).toMatchObject({ visible: true });

  await app.press("btn-toggle-tray-panel");
  expect(app.window("tray-panel").state()).toBeNull();
});

test("anchors tray panel below top menu bar on macOS and above bottom taskbar on Windows", () => {
  const panelSize = { width: 320, height: 320 };
  const macDisplay: Display = {
    id: "mac-1",
    bounds: { origin: { x: 0, y: 0 }, size: { width: 1440, height: 900 } },
    visibleBounds: { origin: { x: 0, y: 25 }, size: { width: 1440, height: 875 } },
    primary: true,
    name: "Built-in",
    scaleFactor: 2,
    cutouts: [],
  };
  const macPos = computeTrayPanelPosition(
    { origin: { x: 1380, y: 0 }, size: { width: 24, height: 24 } },
    panelSize,
    [macDisplay],
  );
  expect(macPos).toEqual({ x: 1120, y: 30 });

  const winDisplay: Display = {
    id: "win-1",
    bounds: { origin: { x: 0, y: 0 }, size: { width: 1920, height: 1080 } },
    visibleBounds: { origin: { x: 0, y: 0 }, size: { width: 1920, height: 1040 } },
    primary: true,
    name: "Primary",
    scaleFactor: 1.5,
    cutouts: [],
  };
  const winPos = computeTrayPanelPosition(
    { origin: { x: 1750, y: 1044 }, size: { width: 24, height: 36 } },
    panelSize,
    [winDisplay],
  );
  expect(winPos).toEqual({ x: 1600, y: 718 });
});
