import { expect, open, test } from "nsh/test";

test("connects simulated i5, reads battery, and shows noise mode in-progress then confirmed", async () => {
  const app = await open("./App.tsx", { platform: "linux", width: 720, height: 640 });
  expect(app.shows("text:未连接")).toBe(true);
  expect(app.shows("id:battery-empty")).toBe(true);

  await app.press("id:btn-connect");
  await app.waitFor("text:已连接");
  expect(app.shows("text:100%")).toBe(true);
  expect(app.shows("text:98%")).toBe(true);
  expect(app.shows("text:当前：抗风噪")).toBe(true);

  await app.press("id:latency-400");
  await app.press("id:noise-mode-normal");
  expect(app.shows("text:正在切换至关闭…")).toBe(true);

  await app.waitFor("text:已确认：关闭");
  expect(app.shows("text:当前：关闭")).toBe(true);
});

test("dev mode validates firmware and switches simulated headset model", async () => {
  const app = await open("./App.tsx", { platform: "linux", width: 720, height: 640 });

  await app.clear("id:dev-firmware-input");
  await app.type("id:dev-firmware-input", "1160");
  await app.press("id:btn-apply-dev");
  await app.waitFor("id:dev-firmware-input-error");

  await app.press("id:dev-model-select");
  await app.type("id:dev-model-search", "HeadFeel");
  await app.press("text:HeadFeel");

  await app.press("id:btn-apply-dev");
  await app.waitFor("text:已连接");
  expect(app.shows("id:battery-unit")).toBe(true);
  expect(app.shows("text:80%")).toBe(true);
});

test("follows system dark appearance", async () => {
  const app = await open("./App.tsx", {
    platform: "linux",
    colorScheme: "dark",
    width: 720,
    height: 640,
  });
  expect(app.shows("text:深色")).toBe(true);
});
