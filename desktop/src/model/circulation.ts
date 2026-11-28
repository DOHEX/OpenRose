import { cleanup, computed, signal } from "nsh";
import { OpenRoseCirculation } from "openrose-native";
import { errorCategoryLabel, noiseModeLabel } from "./labels";
import type { HeadsetController } from "./headset";

export type RemoteControlStatus =
  | "Waiting"
  | "Idle"
  | "Recovering"
  | "Initializing"
  | "Ready"
  | "Unavailable";

export interface RemoteSessionReference {
  peer_id: string;
  headset_id: string;
  token: string;
}

export interface RemoteBattery {
  left: number | null;
  right: number | null;
  case: number | null;
  layout: "EarbudsAndCase" | "EarbudsOnly" | "SingleUnit";
}

export interface RemoteReading {
  status: RemoteControlStatus;
  headset_id: string | null;
  headset_name: string | null;
  profile: string | null;
  session: RemoteSessionReference | null;
  battery: RemoteBattery | null;
  noise_mode: string | null;
  noise_modes: string[];
  anc_level: number | null;
  anc_levels: number[];
  transparency_level: number | null;
  transparency_levels: number[];
  eq_preset: string | null;
  eq_presets: string[];
  game_mode_supported: boolean;
  game_mode: boolean | null;
  blocked_reasons: Record<string, string>;
}

export interface LocalPeerView {
  id: string;
  name: string;
  started: boolean;
  boundPort: number | null;
  startError: string | null;
}

export interface PairedPeerView {
  id: string;
  name: string;
  available: boolean;
  reading: RemoteReading;
}

export interface NearbyPeerView {
  id: string;
  name: string;
  addresses: string[];
  port: number;
  paired: boolean;
}

export interface PairingOfferView {
  code: string;
  qr: string;
  validForMillis: number;
  qrSize: number;
  qrModules: string;
}

export type PullStage = "ContactingPeers" | "Acquiring" | "AcquiringOffline";

export interface CirculationSnapshot {
  localPeer: LocalPeerView;
  pairedPeers: PairedPeerView[];
  nearbyPeers: NearbyPeerView[];
  pairingOffer: PairingOfferView | null;
  remoteReadings: Record<string, RemoteReading>;
  pendingNoiseWrites: Record<string, string>;
  pullStage: PullStage | null;
  lastError: string | null;
}

export interface DevPeerStatus {
  peerId: string;
  name: string;
  online: boolean;
  pairingCode: string | null;
  headsetName: string;
  noiseMode: string | null;
  replyLatencyMs: number;
}

export interface DevPeerPairingInfo {
  peerId: string;
  name: string;
  code: string;
  qr: string;
  validForMillis: number;
}

export interface RemoteHeadsetView {
  peerId: string;
  peerName: string;
  available: boolean;
  holds: boolean;
  headsetId: string | null;
  headsetName: string | null;
  batteryText: string | null;
  confirmedMode: string | null;
  confirmedModeLabel: string;
  modes: string[];
  modeOptions: { value: string; label: string }[];
  pendingMode: string | null;
  pendingText: string | null;
  canSwitch: boolean;
  session: RemoteSessionReference | null;
}

export interface PullFeedback {
  kind: "in_progress" | "success" | "unreachable" | "error";
  tone: "accent" | "success" | "warning" | "danger";
  title: string;
  detail?: string;
}

const ERROR_TEXT: Record<string, string> = {
  UnsupportedVersion: "对端的 OpenRose 版本不兼容，请把两端更新到同一版本",
  NotPaired: "尚未与该对端配对，或对端已取消配对",
  PeerOffline: "对端不在线",
  ServiceClosed: "耳机流转服务已关闭",
  OwnerInactive: "对端当前没有接入耳机",
  PairingNotOpen: "对端尚未开始配对，请先在对端点击“开始配对”",
  PairingExpired: "配对码已过期，请在对端重新开始配对",
  PairingAttemptsExceeded: "配对码尝试次数过多，请在对端重新开始配对",
  PairingRejected: "配对码不正确",
  InvalidPairingCode: "请输入 6 位数字配对码",
  InvalidQrPayload: "二维码内容无效",
  SessionExpired: "对端耳机的连接已变化，请刷新后重试",
  SessionNotReady: "对端耳机尚未就绪，请稍后重试",
  UnsupportedOperation: "对端不支持该操作",
  UnsupportedSetting: "对端耳机不支持该设置",
  InvalidArgument: "请求参数无效",
  Protocol: "与对端通信出错",
  Timeout: "对端响应超时",
  IdentityError: "无法读取本机的流转身份",
  StorageError: "无法保存配对信息",
  DiscoveryError: "无法在局域网中发现对端，请检查网络",
  "YieldRefused:InCall": "对方正在通话，暂时无法切换",
  "YieldRefused:MicInUse": "麦克风正在使用，暂时无法切换",
  "YieldRefused:Unavailable": "对端暂时无法让出耳机",
  "AcquireFailed:InCall": "本机正在通话，无法接入耳机",
  "AcquireFailed:MicInUse": "本机麦克风正在使用，无法接入耳机",
  "AcquireFailed:Unavailable": "本机接入耳机失败",
  PullInProgress: "正在将耳机接到这台电脑，请稍候",
};

const PULL_STAGE_TITLES: Record<PullStage | "Starting", string> = {
  Starting: "正在将耳机接到这台电脑…",
  ContactingPeers: "正在联系对端…",
  Acquiring: "对端已让出耳机，正在接入本机…",
  AcquiringOffline: "联系不上任何对端，正在直接接入本机…",
};

const CONTROL_FAILED_PREFIX = "ControlFailed:";

export function circulationErrorText(code: string): string {
  if (code.startsWith(CONTROL_FAILED_PREFIX)) {
    return `对端耳机控制失败：${errorCategoryLabel(code.slice(CONTROL_FAILED_PREFIX.length))}`;
  }
  return ERROR_TEXT[code] ?? `流转失败（${code}）`;
}

export function pullProgressFeedback(stage: PullStage | null): PullFeedback {
  return { kind: "in_progress", tone: "accent", title: PULL_STAGE_TITLES[stage ?? "Starting"] };
}

export function pullOutcomeFeedback(code: string): PullFeedback {
  switch (code) {
    case "Acquired":
      return { kind: "success", tone: "success", title: "已接到这台电脑" };
    case "AlreadyConnected":
      return { kind: "success", tone: "success", title: "耳机已连接到这台电脑" };
    case "AcquiredWithPeersUnreachable":
      return {
        kind: "unreachable",
        tone: "warning",
        title: "已接到这台电脑",
        detail: "联系不上任何对端",
      };
    default:
      return { kind: "error", tone: "danger", title: circulationErrorText(code) };
  }
}

function slotText(label: string, value: number | null): string {
  return `${label} ${value === null ? "--" : `${value}%`}`;
}

export function remoteBatteryText(battery: RemoteBattery | null): string | null {
  if (battery === null) return null;
  if (battery.layout === "SingleUnit") return slotText("电量", battery.case);
  const slots = [slotText("左", battery.left), slotText("右", battery.right)];
  if (battery.layout === "EarbudsAndCase") slots.push(slotText("盒", battery.case));
  return slots.join(" · ");
}

export function remoteHeadsetView(
  peer: PairedPeerView,
  pendingNoiseWrites: Record<string, string>,
): RemoteHeadsetView {
  const reading = peer.reading;
  const pendingMode = pendingNoiseWrites[peer.id] ?? null;
  const modes = reading.noise_modes.map((mode) => mode.toLowerCase());
  const confirmedMode = reading.noise_mode?.toLowerCase() ?? null;
  const holds = peer.available && reading.headset_name !== null;
  return {
    peerId: peer.id,
    peerName: peer.name,
    available: peer.available,
    holds,
    headsetId: reading.headset_id,
    headsetName: reading.headset_name,
    batteryText: remoteBatteryText(reading.battery),
    confirmedMode,
    confirmedModeLabel: confirmedMode ? `当前：${noiseModeLabel(confirmedMode)}` : "降噪模式",
    modes,
    modeOptions: modes.map((mode) => ({ value: mode, label: noiseModeLabel(mode) })),
    pendingMode,
    pendingText: pendingMode ? `等待对端确认切换至${noiseModeLabel(pendingMode)}…` : null,
    canSwitch: holds && reading.session !== null && modes.length > 0 && pendingMode === null,
    session: reading.session,
  };
}

export function firstRemoteHolder(snapshot: CirculationSnapshot): RemoteHeadsetView | null {
  const holder = snapshot.pairedPeers.find(
    (peer) => peer.available && peer.reading.headset_name !== null,
  );
  return holder ? remoteHeadsetView(holder, snapshot.pendingNoiseWrites) : null;
}

export interface CirculationController {
  snapshot: () => CirculationSnapshot;
  development: boolean;
  busy: () => boolean;
  errorText: () => string | null;
  remoteHeadsets: () => RemoteHeadsetView[];
  selectedPeerId: () => string | null;
  pairingCode: () => string;
  devPeer: () => DevPeerStatus | null;
  devPairing: () => DevPeerPairingInfo | null;
  pullingAddress: () => string | null;
  pullFeedback: () => PullFeedback | null;
  offlinePullAddress: () => string | null;
  currentOutput: () => string | null;
  soundSettingsAvailable: () => boolean;
  selectNearbyPeer: (peerId: string | null) => void;
  setPairingCode: (code: string) => void;
  offerPairing: () => Promise<void>;
  pairSelected: () => Promise<void>;
  forget: (peerId: string) => Promise<void>;
  refreshPeer: (peerId: string) => Promise<void>;
  setRemoteNoiseMode: (peerId: string, mode: string) => Promise<void>;
  pull: (address: string) => Promise<void>;
  clearPullFeedback: () => void;
  refreshAudioState: () => Promise<void>;
  openSoundSettings: () => void;
  clearError: () => void;
  devAddPeer: () => Promise<void>;
  devOpenPeerPairing: () => Promise<void>;
  devSetPeerOnline: (online: boolean) => Promise<void>;
  devSetPeerLatencyMs: (millis: number) => void;
  devSetPeerYieldRefusal: (reason: string | null, peerId?: string | null) => void;
  devSetPeerNoAnswer: (noAnswer: boolean, peerId?: string | null) => void;
  devSetLocalAcquireRefusal: (reason: string | null) => void;
  devSetSoundSettingsAvailable: (available: boolean) => void;
}

const AUDIO_REFRESH_MS = 3000;

function findRemoteAddress(snap: CirculationSnapshot): string | null {
  const holder = snap.pairedPeers.find((peer) => peer.available && peer.reading.headset_id !== null);
  return holder?.reading.headset_id ?? null;
}

export function createCirculationController(
  headset: HeadsetController,
  development: boolean,
): CirculationController {
  const circulation = new OpenRoseCirculation(headset.client);
  const initialSnapshot = circulation.snapshot() as CirculationSnapshot;
  const snapshot = signal<CirculationSnapshot>(initialSnapshot);
  const busy = signal(false);
  const actionError = signal<string | null>(null);
  const selectedPeerId = signal<string | null>(null);
  const pairingCode = signal("");
  const devPeer = signal<DevPeerStatus | null>(null);
  const devPairing = signal<DevPeerPairingInfo | null>(null);
  const pullingAddress = signal<string | null>(null);
  const pullResult = signal<PullFeedback | null>(null);
  const lastRemoteAddress = signal<string | null>(findRemoteAddress(initialSnapshot));
  const currentOutput = signal<string | null>(null);
  const soundSettingsAvailable = signal<boolean>(circulation.soundSettingsAvailable());
  let disposed = false;

  const stop = circulation.subscribe((next) => {
    const typed = next as CirculationSnapshot;
    snapshot.set(typed);
    const seenAddress = findRemoteAddress(typed);
    if (seenAddress !== null) {
      lastRemoteAddress.set(seenAddress);
    }
  });
  cleanup(() => {
    disposed = true;
    stop();
    circulation.dispose();
  });

  const errorText = computed(() => {
    const code = actionError() ?? snapshot().lastError;
    return code ? circulationErrorText(code) : null;
  });

  const remoteHeadsets = computed(() =>
    snapshot().pairedPeers.map((peer) => remoteHeadsetView(peer, snapshot().pendingNoiseWrites)),
  );

  const pullFeedback = computed<PullFeedback | null>(
    () =>
      pullResult() ??
      (pullingAddress() === null ? null : pullProgressFeedback(snapshot().pullStage)),
  );

  const offlinePullAddress = computed(() => {
    if (headset.connectionStatus().kind === "connected") return null;
    if (remoteHeadsets().some((view) => view.holds)) return null;
    if (snapshot().pairedPeers.length === 0) return null;
    return lastRemoteAddress() ?? headset.currentHeadset()?.id ?? null;
  });

  async function run(action: () => Promise<unknown>) {
    actionError.set(null);
    busy.set(true);
    try {
      await action();
    } catch (cause) {
      actionError.set(cause instanceof Error ? cause.message : String(cause));
    } finally {
      busy.set(false);
    }
  }

  async function refreshAudioState() {
    if (disposed) return;
    soundSettingsAvailable.set(circulation.soundSettingsAvailable());
    try {
      const output = await circulation.currentOutput();
      if (!disposed) {
        currentOutput.set(output ?? null);
      }
    } catch {
      if (!disposed) {
        currentOutput.set(null);
      }
    }
  }

  void refreshAudioState();
  const audioRefreshTimer = setInterval(() => void refreshAudioState(), AUDIO_REFRESH_MS);
  cleanup(() => clearInterval(audioRefreshTimer));

  function refreshDevPeer() {
    devPeer.set((circulation.devPeerStatus() as DevPeerStatus | null) ?? null);
  }

  return {
    snapshot: () => snapshot(),
    development,
    busy: () => busy(),
    errorText: () => errorText(),
    remoteHeadsets: () => remoteHeadsets(),
    selectedPeerId: () => selectedPeerId(),
    pairingCode: () => pairingCode(),
    devPeer: () => devPeer(),
    devPairing: () => devPairing(),
    pullingAddress: () => pullingAddress(),
    pullFeedback: () => pullFeedback(),
    offlinePullAddress: () => offlinePullAddress(),
    currentOutput: () => currentOutput(),
    soundSettingsAvailable: () => soundSettingsAvailable(),
    selectNearbyPeer: (peerId) => selectedPeerId.set(peerId),
    setPairingCode: (code) => pairingCode.set(code),
    offerPairing: () => run(() => circulation.offerPairing()),
    pairSelected: () =>
      run(async () => {
        const peerId = selectedPeerId();
        if (peerId === null) return;
        await circulation.pair(peerId, pairingCode());
        selectedPeerId.set(null);
        pairingCode.set("");
      }),
    forget: (peerId) => run(() => circulation.forget(peerId)),
    refreshPeer: (peerId) => run(() => circulation.refreshPeer(peerId)),
    setRemoteNoiseMode: (peerId, mode) =>
      run(async () => {
        const session = snapshot().pairedPeers.find((peer) => peer.id === peerId)?.reading.session;
        if (!session) return;
        await circulation.setRemoteNoiseMode(peerId, session, mode);
      }),
    pull: async (address) => {
      if (pullingAddress() !== null) {
        pullResult.set({
          kind: "error",
          tone: "warning",
          title: circulationErrorText("PullInProgress"),
        });
        return;
      }
      pullingAddress.set(address);
      pullResult.set(null);
      try {
        const outcome = await circulation.pull(address);
        if (!disposed) {
          pullResult.set(pullOutcomeFeedback(outcome));
          void refreshAudioState();
        }
      } catch (cause) {
        if (!disposed) {
          const code = cause instanceof Error ? cause.message : String(cause);
          pullResult.set({
            kind: "error",
            tone: "danger",
            title: circulationErrorText(code),
          });
        }
      } finally {
        if (!disposed) {
          pullingAddress.set(null);
        }
      }
    },
    clearPullFeedback: () => pullResult.set(null),
    refreshAudioState,
    openSoundSettings: () => {
      actionError.set(null);
      try {
        circulation.openSoundSettings();
      } catch (cause) {
        actionError.set(cause instanceof Error ? cause.message : String(cause));
      }
    },
    clearError: () => {
      actionError.set(null);
      circulation.clearError();
    },
    devAddPeer: () =>
      run(async () => {
        await circulation.devAddSimulatedPeer();
        refreshDevPeer();
      }),
    devOpenPeerPairing: () =>
      run(async () => {
        devPairing.set((await circulation.devOpenPeerPairing()) as DevPeerPairingInfo);
        refreshDevPeer();
      }),
    devSetPeerOnline: (online) =>
      run(async () => {
        await circulation.devSetPeerOnline(online);
        refreshDevPeer();
      }),
    devSetPeerLatencyMs: (millis) => circulation.devSetPeerLatencyMs(millis),
    devSetPeerYieldRefusal: (reason, peerId = null) =>
      circulation.devSetPeerYieldRefusal(reason, peerId),
    devSetPeerNoAnswer: (noAnswer, peerId = null) =>
      circulation.devSetPeerNoAnswer(noAnswer, peerId),
    devSetLocalAcquireRefusal: (reason) => circulation.devSetLocalAcquireRefusal(reason),
    devSetSoundSettingsAvailable: (available) => {
      circulation.devSetSoundSettingsAvailable(available);
      soundSettingsAvailable.set(circulation.soundSettingsAvailable());
    },
  };
}
