import { signal } from "nsh";
import { OpenRoseHeadsetFlow } from "openrose-native";
import type { OpenRoseClient } from "openrose-native";
import {
  errorCategoryLabel,
  noiseModeLabel,
} from "./headset";
import type {
  ConnectionPhase,
  ControlError,
  ControlSnapshot,
  FieldReading,
  ModelCatalogEntry,
  SettingOperation,
  SettingStage,
} from "./headset";
export type HostPlatform = "Unknown" | "Android" | "Windows" | "Linux" | "MacOs";
export type PermissionState = "NotRequired" | "Required" | "Granted" | "Denied";
export type PlatformApplicability = "Unknown" | "NotApplicable" | "Applicable";
export type ReconnectGuidance = "OpenCaseAutoConnect" | "WaitSystemBluetooth";
export type TransportRecoveryReason =
  | "ServiceDiscovery"
  | "ServicesUnavailable"
  | "Subscription"
  | "WriteFailed"
  | "ServicesChanged"
  | "NoInitialResponse";
export type TransportSelectionReason =
  | "ModelBleOnly"
  | "ForcedBle"
  | "SppUnavailable"
  | "ClassicAddressUnavailable"
  | "AudioProfileUnknown"
  | "LeAudio"
  | "ClassicAudio";

export type TransportUnavailableReason =
  | { UnsupportedPlatform: { platform: string | null } }
  | "BleUnsupported"
  | "PermissionRequired"
  | "NoAdapter"
  | "AdapterPoweredOff"
  | "AdapterStateUnknown"
  | "AccessDenied"
  | "ServiceUnavailable"
  | { MissingDependency: { detail: string | null } }
  | "Initializing"
  | "Recovering"
  | "ShuttingDown"
  | "Closed"
  | { BackendUnavailable: { detail: string | null } };

export type TransportAvailability =
  | "Available"
  | "Disabled"
  | { Unavailable: { reason: TransportUnavailableReason } };

export type ConnectionBlockReason =
  | "BluetoothPermissionRequired"
  | "BluetoothDisabled"
  | { TransportUnavailable: { reason: TransportUnavailableReason } };

export interface PlatformState {
  permission: PermissionState;
  transport: TransportAvailability;
  host_platform: HostPlatform;
  user_unlocked: boolean;
  scan_permission: PermissionState;
  control_transport_applicability: PlatformApplicability;
}

export interface HeadsetCandidate {
  id: string;
  name: string | null;
  rssi: number | null;
  profile: string | null;
  manufacturer_data_hex: string;
  classic_address: string | null;
}

export type ScanState = "Idle" | "Scanning" | { Failed: ControlError };

export interface ConnectionRecovery {
  session_id: number;
  reason: TransportRecoveryReason;
}

export type TransportEndpoint =
  | { BleGatt: { service_uuid: string; write_uuid: string; notify_uuid: string } }
  | { SppRfcomm: { address: string; service_uuid: string } };

export interface TransportSelection {
  endpoint: TransportEndpoint;
  reason: TransportSelectionReason;
}

export interface HeadsetDiagnostics {
  field_errors: Record<string, ControlError>;
  raw_notifications: string[];
  unknown_notifications: string[];
  warnings: string[];
}

export interface ExtendedFieldValue {
  Number?: number;
  Toggle?: boolean;
  Text?: string;
  IntegerList?: number[];
  Firmware?: { parts: number[]; display_text: string };
  TouchKeys?: Array<{ side: string; gesture: string; action: string | null; raw_action: number }>;
  TouchAssignment?: { side: string; gesture: string; action: string };
  NoiseCycle?: Record<string, boolean | null>;
}

export interface ExtendedHeadsetState {
  headset_id: string;
  session_id: number;
  readings: {
    profile: string;
    capabilities: {
      readable_fields: string[];
      writable_settings: string[];
      noise_control: {
        modes: string[];
        cycle_modes: string[];
        minimum_cycle_selections: number;
      } | null;
    };
    fields: Record<string, FieldReading>;
  };
  diagnostics: HeadsetDiagnostics;
  classic_address: string | null;
  transport_selection: TransportSelection | null;
}

export interface FullControlSnapshot {
  revision: number;
  platform: PlatformState;
  settings: { force_ble: boolean };
  connection:
    | "Disconnected"
    | { Connecting: { headset_id: string; session_id: number; phase: ConnectionPhase } }
    | { Connected: { headset_id: string; session_id: number; profile: string } };
  bonded_headsets: HeadsetCandidate[];
  candidates: HeadsetCandidate[];
  scan: ScanState;
  headset: ExtendedHeadsetState | null;
  last_error: ControlError | null;
  setting_operations: SettingOperation[];
  audio_headset_id: string | null;
  connection_target: HeadsetCandidate | null;
  selected_headset_id: string | null;
  connection_recovery: ConnectionRecovery | null;
  connection_block_reason: ConnectionBlockReason | null;
  reconnect_guidance: ReconnectGuidance | null;
}

export interface HeadsetController {
  client: OpenRoseClient;
  catalogById: Map<string, ModelCatalogEntry>;
  snapshot: () => ControlSnapshot;
}

const PHASE_TEXT: Record<ConnectionPhase, string> = {
  Connecting: "正在建立蓝牙连接…",
  Discovering: "正在发现耳机服务…",
  Subscribing: "正在订阅状态通知…",
  Initializing: "正在初始化并读取状态…",
  Ready: "已连接",
};

const RECOVERY_REASON_TEXT: Record<TransportRecoveryReason, string> = {
  ServiceDiscovery: "蓝牙服务发现暂不可用",
  ServicesUnavailable: "耳机控制服务尚未就绪",
  Subscription: "设备状态订阅未完成",
  WriteFailed: "首次状态读取发送未完成",
  ServicesChanged: "耳机的蓝牙服务已变化",
  NoInitialResponse: "首次读取尚未收到可用状态",
};

const SELECTION_REASON_TEXT: Record<TransportSelectionReason, string> = {
  ModelBleOnly: "此型号使用 BLE 控制",
  ForcedBle: "本次连接使用强制 BLE 偏好",
  SppUnavailable: "当前平台未提供蓝牙串口控制",
  ClassicAddressUnavailable: "尚无可用的蓝牙串口地址",
  AudioProfileUnknown: "无法确认音频连接类型，使用 BLE",
  LeAudio: "已检测到 LE Audio 音频连接，使用 BLE",
  ClassicAudio: "已确认传统蓝牙音频连接，可使用串口控制",
};

const STAGE_TEXT: Record<SettingStage, string> = {
  Submitted: "已提交，等待发送",
  PartiallyWritten: "部分指令已写入",
  Written: "已写入，等待耳机确认",
  Confirmed: "已确认",
  NotApplied: "耳机未应用该设置",
  TimedOut: "等待确认超时",
  Disconnected: "连接已断开",
  Failed: "操作失败",
};

const FIELD_LABELS: Record<string, string> = {
  LeftBattery: "左耳电量",
  RightBattery: "右耳电量",
  CaseBattery: "充电盒电量",
  LeftCharging: "左耳充电状态",
  RightCharging: "右耳充电状态",
  LeftInCase: "左耳在盒状态",
  RightInCase: "右耳在盒状态",
  CaseFlag: "充电盒状态",
  Firmware: "固件版本",
  NoiseMode: "降噪模式",
  Eq: "均衡器模式",
  EqSlot: "均衡器槽位",
  GameMode: "游戏模式",
  AudioMode: "音频编解码",
  Multipoint: "双设备连接",
  TouchKeys: "触控按键映射",
  TapEnabled: "敲击控制开关",
  TapKeys: "敲击按键映射",
  TouchEnabled: "触控开关",
  NoiseCycle: "降噪切换顺序",
  EarDetection: "佩戴检测",
  AncLevel: "降噪档位",
  TransparencyLevel: "通透档位",
  PromptVolume: "提示音音量",
  CustomEq: "自定义均衡器曲线",
  Gain: "低音增强档位",
  HearingProtection: "听力保护",
  PromptLanguage: "提示音语言",
  FindState: "寻机状态",
  SpatialAudio: "空间音频",
  HideSearch: "隐藏蓝牙搜索",
  SpatialMode: "空间音频模式",
  SpatialContent: "空间音频内容",
  EarAdaptive: "耳道自适应",
  IndependentMicActivation: "独立麦克风激活",
  IndependentMicMode: "独立麦克风模式",
};

const SETTING_LABELS: Record<string, string> = {
  NoiseMode: "降噪模式",
  AncLevel: "降噪档位",
  TransparencyLevel: "通透档位",
  NoiseCycle: "降噪切换顺序",
  Eq: "均衡器模式",
  GameMode: "游戏模式",
  AudioMode: "音频编解码",
  Multipoint: "双设备连接",
  TouchEnabled: "触控开关",
  EarDetection: "佩戴检测",
  TouchBinding: "触控操作",
  TapEnabled: "敲击控制开关",
  TapBinding: "敲击操作",
  PromptVolume: "提示音音量",
  CustomEq: "自定义均衡器曲线",
  Gain: "低音增强",
  HearingProtection: "听力保护",
  BassBoost: "低音增强",
  PromptLanguage: "提示音语言",
  SpatialAudio: "空间音频",
  SpatialMode: "空间音频模式",
  SpatialContent: "空间音频内容",
  EarAdaptive: "耳道自适应",
  IndependentMicActivation: "独立麦克风激活",
  IndependentMicMode: "独立麦克风模式",
};

const SEMANTIC_VALUE_LABELS: Record<string, string> = {
  anc: "主动降噪",
  normal: "关闭",
  wind: "抗风噪",
  transparency: "通透",
  auto: "自适应",
  deep: "深度降噪",
  dynamic: "动态降噪",
  aac_sbc: "AAC / SBC",
  ldac: "LDAC",
  ldac_lhdc: "LDAC + LHDC",
  lhdc: "LHDC",
  lc3: "LC3",
  chinese: "中文",
  english: "英文",
  pop: "流行",
  hifi: "Hi-Fi",
  rock: "摇滚",
  light: "轻音乐",
  custom: "自定义",
  off: "关闭",
  head_tracking: "头部跟踪",
  fixed: "固定",
  tv: "电视",
  music: "音乐",
  movie: "电影",
  game: "游戏",
};

const PERMISSION_LABELS: Record<PermissionState, string> = {
  NotRequired: "无需权限",
  Required: "需要授予",
  Granted: "已授予",
  Denied: "已拒绝",
};

const HOST_PLATFORM_LABELS: Record<HostPlatform, string> = {
  Unknown: "未知平台",
  Android: "Android",
  Windows: "Windows",
  Linux: "Linux",
  MacOs: "macOS",
};

const APPLICABILITY_LABELS: Record<PlatformApplicability, string> = {
  Unknown: "未知",
  NotApplicable: "不适用",
  Applicable: "适用",
};

export function fullSnapshotOf(controller: HeadsetController): FullControlSnapshot {
  return controller.snapshot() as unknown as FullControlSnapshot;
}

export function currentHeadsetOf(controller: HeadsetController): HeadsetCandidate | null {
  controller.snapshot();
  return (OpenRoseHeadsetFlow.currentHeadset(controller.client) as HeadsetCandidate | null) ?? null;
}

export function modelConfigurationLabel(
  profileId: string | null | undefined,
  catalogById: Map<string, ModelCatalogEntry>,
): string {
  if (!profileId) return "未识别型号";
  const model = catalogById.get(profileId);
  if (!model) return profileId;
  return model.versionName ? `${model.displayName} · ${model.versionName}` : model.displayName;
}

export function modelConfigurationCaption(
  profileId: string | null | undefined,
  catalogById: Map<string, ModelCatalogEntry>,
): string | null {
  if (!profileId) return null;
  const model = catalogById.get(profileId);
  if (!model) return profileId;
  return model.versionName ? `${model.versionName} · ${model.familyLabel}` : model.familyLabel;
}

export function candidateDisplayName(
  candidate: HeadsetCandidate,
  catalogById: Map<string, ModelCatalogEntry>,
): string {
  const rawName = candidate.name?.trim();
  if (rawName) return rawName;
  if (candidate.profile) {
    const model = catalogById.get(candidate.profile);
    if (model) return model.displayName;
  }
  return candidate.id;
}

export function transportUnavailableText(
  reason: TransportUnavailableReason,
  hostPlatform: HostPlatform = "Unknown",
): { title: string; detail: string | null } {
  if (typeof reason === "object") {
    if ("UnsupportedPlatform" in reason) {
      const plat = reason.UnsupportedPlatform.platform?.trim();
      return {
        title: "当前平台控制传输不可用",
        detail: plat ? `${plat} 控制传输尚未实现` : "平台控制传输尚未实现",
      };
    }
    if ("MissingDependency" in reason) {
      const detail = reason.MissingDependency.detail?.trim() || null;
      return {
        title: "蓝牙传输不可用：缺少系统依赖",
        detail: detail ? `BlueZ 运行环境缺少依赖：${detail}` : "BlueZ 运行环境缺少依赖",
      };
    }
    if ("BackendUnavailable" in reason) {
      const detail = reason.BackendUnavailable.detail?.trim() || null;
      const prefix =
        hostPlatform === "Linux"
          ? "BlueZ 传输不可用"
          : hostPlatform === "Windows"
            ? "Windows 蓝牙传输不可用"
            : hostPlatform === "MacOs"
              ? "macOS 蓝牙传输不可用"
              : "蓝牙传输不可用";
      return {
        title: prefix,
        detail,
      };
    }
  }
  switch (reason) {
    case "BleUnsupported":
      return {
        title: "蓝牙传输不可用",
        detail:
          hostPlatform === "Windows"
            ? "Windows 蓝牙适配器不支持 BLE"
            : "此设备不支持所需的 BLE 硬件",
      };
    case "PermissionRequired":
      return {
        title: "需要蓝牙权限",
        detail: "授予蓝牙权限后可检测蓝牙并发现耳机",
      };
    case "NoAdapter":
      return {
        title: "蓝牙传输不可用",
        detail: "未找到可用的蓝牙适配器",
      };
    case "AdapterPoweredOff":
      return {
        title: "蓝牙已关闭",
        detail: "蓝牙适配器已关闭，请先开启系统蓝牙",
      };
    case "AdapterStateUnknown":
      return {
        title: "蓝牙传输不可用",
        detail:
          hostPlatform === "Windows"
            ? "Windows 蓝牙适配器状态未知"
            : "蓝牙适配器状态未知",
      };
    case "AccessDenied":
      return {
        title: "蓝牙传输不可用",
        detail:
          hostPlatform === "Linux" ? "D-Bus 拒绝访问系统蓝牙" : "系统拒绝访问蓝牙适配器",
      };
    case "ServiceUnavailable":
      return {
        title: "蓝牙传输不可用",
        detail: hostPlatform === "Linux" ? "BlueZ 服务不可用" : "系统蓝牙服务不可用",
      };
    case "Initializing":
      return {
        title: "蓝牙传输不可用",
        detail:
          hostPlatform === "Linux"
            ? "正在初始化 BlueZ 传输"
            : "正在初始化蓝牙传输",
      };
    case "Recovering":
      return {
        title: "蓝牙传输不可用",
        detail: "正在恢复蓝牙传输状态",
      };
    case "ShuttingDown":
      return {
        title: "蓝牙传输不可用",
        detail: "应用正在关闭",
      };
    case "Closed":
      return {
        title: "蓝牙传输不可用",
        detail: hostPlatform === "Linux" ? "BlueZ 传输已关闭" : "蓝牙传输已关闭",
      };
  }
}

export function connectionBlockMessage(
  reason: ConnectionBlockReason | null,
  hostPlatform: HostPlatform = "Unknown",
): { title: string; detail: string | null } | null {
  if (!reason) return null;
  if (reason === "BluetoothPermissionRequired") {
    return {
      title: "需要蓝牙权限",
      detail: "需要授予蓝牙权限才能扫描和连接这副耳机",
    };
  }
  if (reason === "BluetoothDisabled") {
    return {
      title: "蓝牙已关闭",
      detail: "系统蓝牙已关闭，请先在系统设置中开启蓝牙",
    };
  }
  return transportUnavailableText(reason.TransportUnavailable.reason, hostPlatform);
}

export function scanBlockMessage(
  snap: FullControlSnapshot,
): { title: string; detail: string | null } | null {
  if (snap.platform.scan_permission === "Required" || snap.platform.scan_permission === "Denied") {
    return {
      title: "需要蓝牙权限",
      detail: "需要授予蓝牙扫描权限才能搜索附近的 ROSE 耳机",
    };
  }
  return connectionBlockMessage(snap.connection_block_reason, snap.platform.host_platform);
}

export function reconnectGuidanceText(
  guidance: ReconnectGuidance | null,
  failed: boolean,
  includeStatus = true,
): string {
  if (failed) {
    return guidance === "OpenCaseAutoConnect"
      ? "请确认耳机已开盖并在附近"
      : "请确认耳机已开启并在附近";
  }
  const base =
    guidance === "OpenCaseAutoConnect"
      ? "耳机开盖后将自动连接"
      : "系统蓝牙连上耳机后将自动连接";
  return includeStatus ? `未连接 · ${base}` : base;
}

export function firstConnectionHint(hostPlatform: HostPlatform): string {
  if (hostPlatform === "Android") {
    return "打开耳机并靠近手机，必要时让耳机进入配对模式。";
  }
  if (hostPlatform === "Windows" || hostPlatform === "Linux" || hostPlatform === "MacOs") {
    return "打开耳机并靠近电脑，必要时让耳机进入配对模式。";
  }
  return "打开耳机并靠近设备，必要时让耳机进入配对模式。";
}

export function connectionPhaseLabel(phase: ConnectionPhase): string {
  return PHASE_TEXT[phase] ?? phase;
}

export function recoveryReasonLabel(reason: TransportRecoveryReason): string {
  return RECOVERY_REASON_TEXT[reason] ?? reason;
}

export function controlTransportSummary(
  selection: TransportSelection | null | undefined,
  connectedOrConnecting: boolean,
): { transportLabel: string; detail: string | null } {
  if (!selection) {
    return {
      transportLabel: connectedOrConnecting ? "确定中" : "未建立",
      detail: null,
    };
  }
  const endpointLabel =
    "BleGatt" in selection.endpoint
      ? "BLE GATT"
      : `蓝牙串口（SPP · ${selection.endpoint.SppRfcomm.address}）`;
  return {
    transportLabel: endpointLabel,
    detail: SELECTION_REASON_TEXT[selection.reason] ?? selection.reason,
  };
}

export function fieldDisplayLabel(field: string): string {
  return FIELD_LABELS[field] ?? field;
}

export function settingDisplayLabel(setting: string): string {
  return SETTING_LABELS[setting] ?? setting;
}

export function settingStageLabel(stage: SettingStage): string {
  return STAGE_TEXT[stage] ?? stage;
}

export function formatKnownValue(field: string | null, rawValue: unknown): string {
  if (!rawValue || typeof rawValue !== "object") return String(rawValue);
  const val = rawValue as ExtendedFieldValue;
  if ("Number" in val && val.Number !== undefined) {
    if (
      field === "LeftBattery" ||
      field === "RightBattery" ||
      field === "CaseBattery"
    ) {
      return `${val.Number}%`;
    }
    return String(val.Number);
  }
  if ("Toggle" in val && val.Toggle !== undefined) {
    if (field === "LeftCharging" || field === "RightCharging") {
      return val.Toggle ? "充电中" : "未充电";
    }
    if (field === "LeftInCase" || field === "RightInCase") {
      return val.Toggle ? "在盒内" : "在盒外";
    }
    if (field === "CaseFlag") {
      return val.Toggle ? "是" : "否";
    }
    return val.Toggle ? "开启" : "关闭";
  }
  if ("Text" in val && val.Text !== undefined) {
    return SEMANTIC_VALUE_LABELS[val.Text] ?? val.Text;
  }
  if ("Firmware" in val && val.Firmware) {
    return val.Firmware.display_text;
  }
  if ("IntegerList" in val && val.IntegerList) {
    return val.IntegerList.join(", ");
  }
  if ("TouchKeys" in val && val.TouchKeys) {
    return val.TouchKeys.map(
      (b) =>
        `${b.side}/${b.gesture}: ${b.action ? (SEMANTIC_VALUE_LABELS[b.action] ?? b.action) : `未识别动作 ${b.raw_action}`}`,
    ).join("；");
  }
  if ("TouchAssignment" in val && val.TouchAssignment) {
    const a = val.TouchAssignment;
    return `${a.side}/${a.gesture} → ${SEMANTIC_VALUE_LABELS[a.action] ?? a.action}`;
  }
  if ("NoiseCycle" in val && val.NoiseCycle) {
    return Object.entries(val.NoiseCycle)
      .filter(([, enabled]) => enabled === true)
      .map(([mode]) => noiseModeLabel(mode))
      .join(" / ");
  }
  return JSON.stringify(rawValue);
}

export function fieldReadingDisplay(
  field: string,
  reading: FieldReading | undefined,
  initializing: boolean,
  error: ControlError | undefined,
): string {
  if (!reading || reading === "Loading") {
    if (error?.code === "Timeout") return "读取超时";
    if (error) return `读取失败：${error.message}`;
    return initializing ? "读取中…" : "尚未读取";
  }
  if (typeof reading === "object") {
    if ("NotProvided" in reading) return "设备未提供";
    if ("Unknown" in reading) return "未知";
    if ("Known" in reading) {
      return formatKnownValue(field, reading.Known.value);
    }
  }
  return "未知";
}

export function fieldRawHex(reading: FieldReading | undefined): string {
  if (!reading || reading === "Loading") return "";
  if ("NotProvided" in reading) return reading.NotProvided.raw_hex;
  if ("Unknown" in reading) return reading.Unknown.raw_hex;
  if ("Known" in reading) return reading.Known.raw_hex;
  return "";
}

export function platformStateSummaryRows(
  snap: FullControlSnapshot,
): Array<{ label: string; value: string; detail?: string | null; tone?: "neutral" | "success" | "warning" | "danger" }> {
  const plat = snap.platform;
  const block = connectionBlockMessage(snap.connection_block_reason, plat.host_platform);
  const transportText =
    plat.transport === "Available"
      ? { title: "可用", detail: null, tone: "success" as const }
      : plat.transport === "Disabled"
        ? { title: "已关闭", detail: "系统蓝牙处于关闭状态", tone: "warning" as const }
        : {
            ...transportUnavailableText(plat.transport.Unavailable.reason, plat.host_platform),
            tone: "danger" as const,
          };
  const scanText =
    snap.scan === "Scanning"
      ? "正在扫描"
      : snap.scan === "Idle"
        ? "空闲"
        : `失败：${errorCategoryLabel(snap.scan.Failed.code)} (${snap.scan.Failed.message})`;
  return [
    {
      label: "宿主平台",
      value: HOST_PLATFORM_LABELS[plat.host_platform] ?? plat.host_platform,
    },
    {
      label: "蓝牙权限",
      value: PERMISSION_LABELS[plat.permission] ?? plat.permission,
      tone: plat.permission === "Denied" || plat.permission === "Required" ? "danger" : "neutral",
    },
    {
      label: "扫描权限",
      value: PERMISSION_LABELS[plat.scan_permission] ?? plat.scan_permission,
      tone:
        plat.scan_permission === "Denied" || plat.scan_permission === "Required"
          ? "danger"
          : "neutral",
    },
    {
      label: "蓝牙传输",
      value: transportText.title,
      detail: transportText.detail,
      tone: transportText.tone,
    },
    {
      label: "控制传输适用性",
      value:
        APPLICABILITY_LABELS[plat.control_transport_applicability] ??
        plat.control_transport_applicability,
    },
    {
      label: "用户解锁状态",
      value: plat.user_unlocked ? "已解锁" : "未解锁",
    },
    {
      label: "连接拦截原因",
      value: block ? block.title : "无",
      detail: block?.detail ?? null,
      tone: block ? "danger" : "success",
    },
    {
      label: "系统音频耳机",
      value: snap.audio_headset_id ?? "未连接",
    },
    {
      label: "当前选定耳机",
      value: snap.selected_headset_id ?? "未手动指定",
    },
    {
      label: "已配对 ROSE 耳机数",
      value: `${snap.bonded_headsets.length} 副`,
    },
    {
      label: "扫描状态",
      value: scanText,
    },
    {
      label: "强制使用 BLE",
      value: snap.settings.force_ble ? "开启" : "关闭",
    },
  ];
}

export function operationRequestedText(op: SettingOperation): string {
  return formatKnownValue(op.setting, op.requested_value);
}

export function createAddHeadsetModel(controller: HeadsetController) {
  const selectedId = signal<string | null>(null);
  const addBusy = signal(false);
  const addError = signal<{ id: string; message: string } | null>(null);
  const addedControlId = signal<string | null>(null);

  async function startScan() {
    addError.set(null);
    addedControlId.set(null);
    try {
      await controller.client.startScan();
    } catch (error) {
      const snapErr = fullSnapshotOf(controller).last_error;
      addError.set({
        id: "__scan__",
        message: snapErr?.message ?? (error instanceof Error ? error.message : String(error)),
      });
    }
  }

  async function stopScan() {
    await controller.client.stopScan();
  }

  async function connectCandidate(headsetId: string) {
    if (addBusy()) return;
    selectedId.set(headsetId);
    addBusy.set(true);
    addError.set(null);
    addedControlId.set(null);
    try {
      await OpenRoseHeadsetFlow.connectForAddHeadset(controller.client, headsetId);
      await controller.client.stopScan();
      const snap = fullSnapshotOf(controller);
      if (snap.connection !== "Disconnected" && "Connected" in snap.connection) {
        if (snap.connection.Connected.headset_id === headsetId) {
          addedControlId.set(headsetId);
        }
      }
    } catch (error) {
      const snapErr = fullSnapshotOf(controller).last_error;
      addError.set({
        id: headsetId,
        message: snapErr?.message ?? (error instanceof Error ? error.message : String(error)),
      });
    } finally {
      addBusy.set(false);
    }
  }

  async function cancelConnection() {
    await OpenRoseHeadsetFlow.cancelAddHeadset(controller.client);
    addBusy.set(false);
    selectedId.set(null);
  }

  function clearAddedState() {
    addedControlId.set(null);
    addError.set(null);
  }

  return {
    selectedId,
    addBusy,
    addError,
    addedControlId,
    startScan,
    stopScan,
    connectCandidate,
    cancelConnection,
    clearAddedState,
  };
}
