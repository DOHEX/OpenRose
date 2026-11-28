import { expect, open, test } from "nsh/test";
import type { App } from "nsh/test";

const WINDOW = { platform: "linux", width: 880, height: 900 } as const;

async function openCirculation() {
  const app = await open("./App.tsx", WINDOW);
  await app.waitFor("id:headset-status-card");
  await app.press("id:nav-circulation");
  await app.waitFor("id:circulation-card");
  return app;
}

function sixDigitCode(app: App): string {
  const code = app.texts().find((text) => /^\d{6}$/.test(text));
  expect(code).toBeDefined();
  return code!;
}

async function pairSimulatedPeer(app: App) {
  await app.press("id:btn-circ-dev-add");
  await app.waitFor("id:circ-dev-peer");
  await app.press("id:btn-circ-dev-open");
  await app.waitFor("id:circ-dev-code");
  await app.waitFor("id:circ-nearby-0");
  const code = sixDigitCode(app);
  await app.press("id:btn-circ-select-0");
  await app.type("id:circ-code-input", code);
  await app.press("id:btn-circ-pair");
  await app.waitFor("id:circ-peer-0");
}

test("starting pairing shows the code and its QR code", async () => {
  const app = await openCirculation();
  expect(app.shows("id:circ-offer")).toBe(false);
  await app.press("id:btn-circ-offer");
  await app.waitFor("id:circ-offer-qr");
  expect(app.shows("id:circ-offer-code")).toBe(true);
  expect(sixDigitCode(app)).toMatch(/^\d{6}$/);
});

test("pairing a nearby peer by its code lists it with the headset it holds", async () => {
  const app = await openCirculation();
  expect(app.shows("id:circ-peers-empty")).toBe(true);
  await pairSimulatedPeer(app);
  expect(app.shows("id:circ-peers-empty")).toBe(false);
  expect(app.shows("text:模拟手机")).toBe(true);
  await app.waitFor("id:circ-remote-battery-0");
  expect(app.shows("id:circ-peer-status-0")).toBe(true);
  expect(app.shows("text:在线")).toBe(true);
  expect(app.shows("text:EarFree i5")).toBe(true);
});

test("a wrong pairing code is refused with its own text and pairs nothing", async () => {
  const app = await openCirculation();
  await app.press("id:btn-circ-dev-add");
  await app.waitFor("id:circ-dev-peer");
  await app.press("id:btn-circ-dev-open");
  await app.waitFor("id:circ-nearby-0");
  await app.press("id:btn-circ-select-0");
  const wrong = sixDigitCode(app) === "000000" ? "111111" : "000000";
  await app.type("id:circ-code-input", wrong);
  await app.press("id:btn-circ-pair");
  await app.waitFor("id:circ-error");
  expect(app.shows("text:配对码不正确")).toBe(true);
  expect(app.shows("id:circ-peer-0")).toBe(false);

  await app.press("id:btn-circ-clear-error");
  expect(app.shows("id:circ-error")).toBe(false);
});

test("a remote noise switch stays in progress until the holder confirms it", async () => {
  const app = await openCirculation();
  await pairSimulatedPeer(app);
  await app.waitFor("id:circ-noise-0-transparency");
  await app.press("id:btn-circ-dev-slow");

  await app.press("id:circ-noise-0-transparency");
  await app.waitFor("id:circ-noise-status-0");
  expect(app.shows("text:等待对端确认切换至通透…")).toBe(true);
  expect(app.shows("text:当前：通透")).toBe(false);

  await app.waitFor("text:当前：通透");
  expect(app.shows("id:circ-noise-status-0")).toBe(false);
});

test("an unavailable peer is marked and read again when it is back", async () => {
  const app = await openCirculation();
  await pairSimulatedPeer(app);
  await app.waitFor("id:circ-remote-battery-0");

  await app.press("id:btn-circ-dev-offline");
  await app.waitFor("text:不在线");
  expect(app.shows("id:circ-peer-offline-0")).toBe(true);
  expect(app.shows("id:circ-remote-battery-0")).toBe(false);

  await app.press("id:btn-circ-dev-online");
  await app.waitFor("id:circ-remote-battery-0");
  expect(app.shows("text:在线")).toBe(true);
  expect(app.shows("id:circ-peer-offline-0")).toBe(false);
});

test("unpairing removes the peer from the list", async () => {
  const app = await openCirculation();
  await pairSimulatedPeer(app);
  await app.waitFor("id:circ-remote-battery-0");
  await app.press("id:btn-circ-forget-0");
  await app.waitFor("id:circ-peers-empty");
  expect(app.shows("id:circ-peer-0")).toBe(false);
});

test("in call and mic in use refusals keep the peer holding and a clear pull moves the headset here", async () => {
  const app = await openCirculation();
  await pairSimulatedPeer(app);
  await app.waitFor("id:btn-circ-pull-0");

  await app.press("id:btn-circ-dev-in-call");
  await app.press("id:btn-circ-pull-0");
  await app.waitFor("text:对方正在通话，暂时无法切换");
  expect(app.shows("id:btn-circ-pull-0")).toBe(true);

  await app.press("id:btn-circ-dev-mic-in-use");
  await app.press("id:btn-circ-pull-0");
  await app.waitFor("text:麦克风正在使用，暂时无法切换");
  expect(app.shows("id:btn-circ-pull-0")).toBe(true);

  await app.press("id:btn-circ-dev-allow-yield");
  await app.press("id:btn-circ-pull-0");
  await app.waitFor("text:已接到这台电脑");
  expect(app.shows("id:btn-circ-pull-0")).toBe(false);

  await app.press("id:nav-headset");
  await app.waitFor("id:headset-status-card");
  await app.waitFor("text:已连接");
});

test("pulling when no paired peer is reachable reports that peers are unreachable", async () => {
  const app = await openCirculation();
  await pairSimulatedPeer(app);
  await app.waitFor("id:btn-circ-pull-0");

  await app.press("id:btn-circ-dev-offline");
  await app.waitFor("text:不在线");
  await app.waitFor("id:btn-circ-pull-offline");
  await app.press("id:btn-circ-pull-offline");
  await app.waitFor("text:联系不上任何对端");

  await app.press("id:nav-headset");
  await app.waitFor("id:headset-status-card");
  await app.waitFor("text:已连接");
});

test("a pull names the contacting stage while a peer has not answered, then the timeout", async () => {
  const app = await openCirculation();
  await pairSimulatedPeer(app);
  await app.waitFor("id:btn-circ-pull-0");

  await app.press("id:btn-circ-dev-no-answer");
  await app.press("id:btn-circ-pull-0");
  await app.waitFor("text:正在联系对端…");
  await app.waitFor("text:对端响应超时");
  expect(app.shows("id:btn-circ-pull-0")).toBe(true);
});

test("a refused acquire on this machine says why and a retry takes the headset", async () => {
  const app = await openCirculation();
  await pairSimulatedPeer(app);
  await app.waitFor("id:btn-circ-pull-0");

  await app.press("id:btn-circ-dev-acquire-in-call");
  await app.press("id:btn-circ-pull-0");
  await app.waitFor("text:本机正在通话，无法接入耳机");

  await app.press("id:btn-circ-dev-allow-yield");
  await app.waitFor("id:btn-circ-pull-offline");
  await app.press("id:btn-circ-pull-offline");
  await app.waitFor("text:已接到这台电脑");
});
