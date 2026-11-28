import { expect, test } from "nsh/test";
import {
  circulationErrorText,
  firstRemoteHolder,
  pullOutcomeFeedback,
  pullProgressFeedback,
  remoteBatteryText,
  remoteHeadsetView,
} from "./circulation";
import type { CirculationSnapshot, PairedPeerView, RemoteReading } from "./circulation";

function reading(overrides: Partial<RemoteReading> = {}): RemoteReading {
  return {
    status: "Ready",
    headset_id: "sim:1",
    headset_name: "EarFree i5",
    profile: "I5_ZT",
    session: { peer_id: "p1", headset_id: "sim:1", token: "t" },
    battery: { left: 80, right: 78, case: 90, layout: "EarbudsAndCase" },
    noise_mode: "Anc",
    noise_modes: ["Normal", "Anc", "Transparency"],
    anc_level: null,
    anc_levels: [],
    transparency_level: null,
    transparency_levels: [],
    eq_preset: null,
    eq_presets: [],
    game_mode_supported: false,
    game_mode: null,
    blocked_reasons: {},
    ...overrides,
  };
}

function peer(id: string, name: string, overrides: Partial<PairedPeerView> = {}): PairedPeerView {
  return { id, name, available: true, reading: reading(), ...overrides };
}

function snapshot(pairedPeers: PairedPeerView[]): CirculationSnapshot {
  return {
    localPeer: { id: "me", name: "本机桌面", started: true, boundPort: 21871, startError: null },
    pairedPeers,
    nearbyPeers: [],
    pairingOffer: null,
    remoteReadings: {},
    pendingNoiseWrites: {},
    pullStage: null,
    lastError: null,
  };
}

test("error codes become Chinese text and unknown codes stay identifiable", () => {
  expect(circulationErrorText("PairingRejected")).toBe("配对码不正确");
  expect(circulationErrorText("PeerOffline")).toBe("对端不在线");
  expect(circulationErrorText("ControlFailed:Timeout")).toBe("对端耳机控制失败：等待耳机确认超时");
  expect(circulationErrorText("Surprise")).toBe("流转失败（Surprise）");
});

test("remote battery text follows the layout and shows missing values as dashes", () => {
  expect(remoteBatteryText(reading().battery)).toBe("左 80% · 右 78% · 盒 90%");
  expect(
    remoteBatteryText({ left: 55, right: null, case: null, layout: "EarbudsOnly" }),
  ).toBe("左 55% · 右 --");
  expect(remoteBatteryText({ left: null, right: null, case: 61, layout: "SingleUnit" })).toBe(
    "电量 61%",
  );
  expect(remoteBatteryText(null)).toBeNull();
});

test("a remote headset view carries name, battery, noise modes and the pending write", () => {
  const view = remoteHeadsetView(
    peer("p1", "手机"),
    snapshot([peer("p1", "手机")]).pendingNoiseWrites,
  );
  expect(view).toMatchObject({
    peerId: "p1",
    peerName: "手机",
    headsetName: "EarFree i5",
    batteryText: "左 80% · 右 78% · 盒 90%",
    confirmedMode: "anc",
    modes: ["normal", "anc", "transparency"],
    pendingMode: null,
    canSwitch: true,
  });

  const pending = remoteHeadsetView(peer("p1", "手机"), { p1: "transparency" });
  expect(pending.pendingMode).toBe("transparency");
  expect(pending.canSwitch).toBe(false);

  const offline = remoteHeadsetView(peer("p1", "手机", { available: false }), {});
  expect(offline.canSwitch).toBe(false);
});

test("the first holder in paired order is the remote headset shown by the tray", () => {
  const holders = snapshot([
    peer("a", "平板", { reading: reading({ status: "Idle", headset_name: null }) }),
    peer("b", "手机", { reading: reading({ headset_name: "EarFree i7" }) }),
    peer("c", "笔记本"),
  ]);
  expect(firstRemoteHolder(holders)).toMatchObject({ peerId: "b", headsetName: "EarFree i7" });
  expect(firstRemoteHolder(snapshot([peer("a", "平板", { available: false })]))).toBeNull();
  expect(firstRemoteHolder(snapshot([]))).toBeNull();
});

test("pull error and outcome codes map to expected Chinese feedback", () => {
  expect(circulationErrorText("YieldRefused:InCall")).toBe("对方正在通话，暂时无法流转");
  expect(circulationErrorText("YieldRefused:MicInUse")).toBe("麦克风正在使用，暂时无法流转");
  expect(circulationErrorText("YieldRefused:AudioStateUnreadable")).toBe("对端读不到耳机的音频状态，暂时无法流转");
  expect(circulationErrorText("YieldRefused:StillConnected")).toBe("对端让出后仍与耳机保持连接，请稍后重试");
  expect(circulationErrorText("AcquireFailed:PlatformUnavailable")).toBe("本机接入耳机失败");
  expect(circulationErrorText("AcquireFailed:MediaNotConnected")).toBe("耳机已连接，但没有接入音频，请重试");
  expect(circulationErrorText("SettingsNotInstalled")).toBe("未找到系统设置程序");
  expect(circulationErrorText("Disconnected")).toBe("耳机流转服务已关闭");
  expect(circulationErrorText("PullInProgress")).toBe("正在将耳机接到这台电脑，请稍候");
  expect(circulationErrorText("Timeout")).toBe("对端响应超时");

  expect(pullOutcomeFeedback("Acquired")).toEqual({
    kind: "success",
    tone: "success",
    title: "已接到这台电脑",
  });
  expect(pullOutcomeFeedback("AlreadyConnected")).toEqual({
    kind: "success",
    tone: "success",
    title: "耳机已连接到这台电脑",
  });
  expect(pullOutcomeFeedback("AcquiredWithPeersUnreachable")).toEqual({
    kind: "unreachable",
    tone: "warning",
    title: "已接到这台电脑",
    detail: "联系不上任何对端",
  });
});

test("the pull progress names each stage, and the offline stage says no peer could be reached", () => {
  expect(pullProgressFeedback(null)).toEqual({
    kind: "in_progress",
    tone: "accent",
    title: "正在将耳机接到这台电脑…",
  });
  expect(pullProgressFeedback("ContactingPeers").title).toBe("正在联系对端…");
  expect(pullProgressFeedback("Acquiring").title).toBe("对端已让出耳机，正在接入本机…");
  expect(pullProgressFeedback("AcquiringOffline").title).toBe(
    "联系不上任何对端，正在直接接入本机…",
  );
});

test("an unknown pull outcome is an error, not a success", () => {
  expect(pullOutcomeFeedback("Surprise")).toEqual({
    kind: "error",
    tone: "danger",
    title: "流转失败（Surprise）",
  });
});

test("acquire failure and timeout have their own text", () => {
  expect(circulationErrorText("AcquireFailed:InCall")).toBe("本机正在通话，无法接入耳机");
  expect(circulationErrorText("AcquireFailed:MicInUse")).toBe("本机麦克风正在使用，无法接入耳机");
  expect(circulationErrorText("AcquireFailed:Unavailable")).toBe("本机接入耳机失败");
  expect(circulationErrorText("Timeout")).toBe("对端响应超时");
});
