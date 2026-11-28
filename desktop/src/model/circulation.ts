import { cleanup, computed, signal } from "nsh";
import { OpenRoseCirculation } from "openrose-native";
import type { OpenRoseClient } from "openrose-native";
import { errorCategoryLabel, noiseModeLabel } from "./headset";

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

export interface CirculationSnapshot {
  localPeer: LocalPeerView;
  pairedPeers: PairedPeerView[];
  nearbyPeers: NearbyPeerView[];
  pairingOffer: PairingOfferView | null;
  remoteReadings: Record<string, RemoteReading>;
  pendingNoiseWrites: Record<string, string>;
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
  headsetName: string | null;
  batteryText: string | null;
  confirmedMode: string | null;
  modes: string[];
  pendingMode: string | null;
  canSwitch: boolean;
  session: RemoteSessionReference | null;
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
};

const CONTROL_FAILED_PREFIX = "ControlFailed:";

export function circulationErrorText(code: string): string {
  if (code.startsWith(CONTROL_FAILED_PREFIX)) {
    return `对端耳机控制失败：${errorCategoryLabel(code.slice(CONTROL_FAILED_PREFIX.length))}`;
  }
  return ERROR_TEXT[code] ?? `流转失败（${code}）`;
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
  const holds = peer.available && reading.headset_name !== null;
  return {
    peerId: peer.id,
    peerName: peer.name,
    available: peer.available,
    headsetName: reading.headset_name,
    batteryText: remoteBatteryText(reading.battery),
    confirmedMode: reading.noise_mode?.toLowerCase() ?? null,
    modes,
    pendingMode,
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

export function remoteNoiseFeedbackText(view: RemoteHeadsetView): string | null {
  return view.pendingMode ? `等待对端确认切换至${noiseModeLabel(view.pendingMode)}…` : null;
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
  selectNearbyPeer: (peerId: string | null) => void;
  setPairingCode: (code: string) => void;
  offerPairing: () => Promise<void>;
  pairSelected: () => Promise<void>;
  forget: (peerId: string) => Promise<void>;
  refreshPeer: (peerId: string) => Promise<void>;
  setRemoteNoiseMode: (peerId: string, mode: string) => Promise<void>;
  clearError: () => void;
  devAddPeer: () => Promise<void>;
  devOpenPeerPairing: () => Promise<void>;
  devSetPeerOnline: (online: boolean) => Promise<void>;
  devSetPeerLatencyMs: (millis: number) => void;
}

export function createCirculationController(
  client: OpenRoseClient,
  development: boolean,
): CirculationController {
  const circulation = new OpenRoseCirculation(client);
  const snapshot = signal<CirculationSnapshot>(circulation.snapshot() as CirculationSnapshot);
  const busy = signal(false);
  const actionError = signal<string | null>(null);
  const selectedPeerId = signal<string | null>(null);
  const pairingCode = signal("");
  const devPeer = signal<DevPeerStatus | null>(null);
  const devPairing = signal<DevPeerPairingInfo | null>(null);

  const stop = circulation.subscribe((next) => snapshot.set(next as CirculationSnapshot));
  cleanup(() => {
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
  };
}
