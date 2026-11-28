import { signal } from "nsh";
import { OpenRoseHeadsetFlow } from "openrose-native";
import { controlErrorOf, headsetDisplayName, isPendingStage } from "./headset";
import type {
  ConnectionBlockReason,
  ControlError,
  ControlSnapshot,
  FieldReading,
  FieldValue,
  HeadsetController,
  HostPlatform,
  ModelCatalogEntry,
  PermissionState,
  PlatformApplicability,
  ReconnectGuidance,
  SettingOperation,
  SettingStage,
  TransportRecoveryReason,
  TransportSelection,
  TransportSelectionReason,
  TransportUnavailableReason,
} from "./headset";
import {
  connectionPhaseLabel,
  controlErrorText,
  controlErrorTitle,
  errorCategoryLabel,
  fieldLabel,
  noiseModeLabel,
  semanticLabel,
  settingLabel,
} from "./labels";
import type { MetricItem, SegmentOption, ToneVariant } from "../ui";

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
  snap: ControlSnapshot,
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

export function settingStageLabel(stage: SettingStage): string {
  return STAGE_TEXT[stage] ?? stage;
}

export function formatKnownValue(field: string | null, rawValue: unknown): string {
  if (!rawValue || typeof rawValue !== "object") return String(rawValue);
  const val = rawValue as FieldValue;
  if ("Number" in val) {
    if (field === "LeftBattery" || field === "RightBattery" || field === "CaseBattery") {
      return `${val.Number}%`;
    }
    return String(val.Number);
  }
  if ("Toggle" in val) {
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
  if ("Text" in val) return semanticLabel(val.Text);
  if ("Firmware" in val) return val.Firmware.display_text;
  if ("IntegerList" in val) return val.IntegerList.join(", ");
  if ("TouchKeys" in val) {
    return val.TouchKeys.map(
      (b) =>
        `${b.side}/${b.gesture}: ${b.action ? semanticLabel(b.action) : `未识别动作 ${b.raw_action}`}`,
    ).join("；");
  }
  if ("TouchAssignment" in val) {
    const a = val.TouchAssignment;
    return `${a.side}/${a.gesture} → ${semanticLabel(a.action)}`;
  }
  if ("NoiseCycle" in val) {
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
  snap: ControlSnapshot,
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

export interface AddHeadsetError {
  id: string;
  message: string;
}

export interface AddHeadsetModel {
  selectedId: () => string | null;
  addBusy: () => boolean;
  addError: () => AddHeadsetError | null;
  addedControlId: () => string | null;
  startScan: () => Promise<void>;
  stopScan: () => Promise<void>;
  connectCandidate: (headsetId: string) => Promise<void>;
  cancelConnection: () => Promise<void>;
  clearAddedState: () => void;
}

export function createAddHeadsetModel(controller: HeadsetController): AddHeadsetModel {
  const selectedId = signal<string | null>(null);
  const addBusy = signal(false);
  const addError = signal<AddHeadsetError | null>(null);
  const addedControlId = signal<string | null>(null);

  async function startScan() {
    addError.set(null);
    addedControlId.set(null);
    try {
      await controller.client.startScan();
    } catch (error) {
      addError.set({ id: "__scan__", message: errorCategoryLabel(controlErrorOf(error).code) });
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
      const snap = controller.snapshot();
      if (snap.connection !== "Disconnected" && "Connected" in snap.connection) {
        if (snap.connection.Connected.headset_id === headsetId) {
          addedControlId.set(headsetId);
        }
      }
    } catch (error) {
      addError.set({ id: headsetId, message: errorCategoryLabel(controlErrorOf(error).code) });
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

export interface BondedHeadsetItemView {
  id: string;
  displayName: string;
  configCaption: string | null;
  isCurrent: boolean;
}

export interface AddHeadsetCandidateView {
  id: string;
  displayName: string;
  configCaption: string | null;
  rssiText: string | null;
  connectingPhaseLabel: string | null;
  isControlConnected: boolean;
  errorMessage: string | null;
}

export interface UnsupportedCandidateView {
  id: string;
  name: string;
  rssiText: string | null;
}

export interface DiagnosticsPlatformRow {
  label: string;
  value: string;
  detail?: string | null;
  tone?: ToneVariant;
}

export interface DiagnosticsOperationItem {
  id: number;
  title: string;
  stageLabel: string;
  detail: string;
  tone: ToneVariant;
}

export interface DiagnosticsReadingItem {
  field: string;
  label: string;
  valueText: string;
  rawHex: string;
}

const UNNAMED_HEADSET_TITLE = "ROSE 耳机";

export function createDesktopViews(controller: HeadsetController, addModel: AddHeadsetModel) {
  const catalogById = controller.catalogById;

  const activeProfileId = () =>
    controller.connectionStatus().profileId ??
    controller.currentHeadset()?.profile ??
    controller.devConfig()?.modelId ??
    null;

  const headsetTitle = () => {
    const current = controller.currentHeadset();
    if (controller.connectionStatus().kind === "disconnected" && !current) {
      return UNNAMED_HEADSET_TITLE;
    }
    const profileId = activeProfileId();
    const entry = profileId ? catalogById.get(profileId) : undefined;
    if (entry) {
      return entry.versionName ? `${entry.displayName} · ${entry.versionName}` : entry.displayName;
    }
    return headsetDisplayName(current, catalogById) ?? UNNAMED_HEADSET_TITLE;
  };

  const connectionSubtitle = () => {
    const status = controller.connectionStatus();
    if (status.kind === "disconnected") {
      const snap = controller.snapshot();
      const failed = Boolean(controller.connectionError() || snap.last_error);
      return reconnectGuidanceText(snap.reconnect_guidance, failed);
    }
    const fw = controller.firmwareText();
    return fw ? `${status.phaseLabel} · 固件 ${fw}` : status.phaseLabel;
  };

  const blockReason = () => {
    const snap = controller.snapshot();
    return connectionBlockMessage(snap.connection_block_reason, snap.platform.host_platform);
  };

  const connectionError = () => {
    const error = controller.connectionError();
    return error
      ? { title: `连接失败：${controlErrorTitle(error.code)}`, detail: error.message || null }
      : null;
  };

  const bondedHeadsets = (): BondedHeadsetItemView[] => {
    const currentId = controller.currentHeadset()?.id;
    return controller.snapshot().bonded_headsets.map((candidate) => ({
      id: candidate.id,
      displayName: headsetDisplayName(candidate, catalogById) ?? candidate.id,
      configCaption: modelConfigurationCaption(candidate.profile, catalogById),
      isCurrent: currentId === candidate.id,
    }));
  };

  const batteryItems = (): MetricItem[] =>
    controller.batterySlots().map((slot) => ({
      id: slot.id,
      testID: `battery-${slot.id}`,
      label: slot.label,
      valueText: slot.valueText,
      caption: slot.caption,
      low: slot.low,
    }));

  const noiseOptions = (): SegmentOption[] =>
    controller.noiseModes().map((mode) => ({
      value: mode,
      label: noiseModeLabel(mode),
      testID: `noise-mode-${mode}`,
    }));

  const noiseStatus = (): { tone: ToneVariant; title: string; detail?: string | null } | null => {
    const feedback = controller.noiseFeedback();
    if (feedback.kind === "in_progress") {
      return {
        tone: "warning",
        title: `正在切换至${noiseModeLabel(feedback.mode)}…`,
        detail: `阶段：${settingStageLabel(feedback.stage)}`,
      };
    }
    if (feedback.kind === "confirmed") {
      return { tone: "success", title: `已确认：${noiseModeLabel(feedback.mode)}` };
    }
    if (feedback.kind === "failed") {
      return {
        tone: "danger",
        title: `切换失败：${controlErrorTitle(feedback.code)}`,
        detail: feedback.message || null,
      };
    }
    return null;
  };

  const activeNoiseValue = () => {
    const feedback = controller.noiseFeedback();
    return feedback.kind === "in_progress" ? feedback.mode : controller.confirmedNoiseMode();
  };

  const confirmedNoiseLabel = () => {
    const mode = controller.confirmedNoiseMode();
    return mode ? noiseModeLabel(mode) : null;
  };

  const addSupportedCandidates = (): AddHeadsetCandidateView[] => {
    const status = controller.connectionStatus();
    const err = addModel.addError();
    const selected = addModel.selectedId();
    return controller
      .snapshot()
      .candidates.filter((candidate) => candidate.profile !== null)
      .map((candidate) => {
        const isConnectingThis = status.kind === "connecting" && status.headsetId === candidate.id;
        return {
          id: candidate.id,
          displayName: headsetDisplayName(candidate, catalogById) ?? candidate.id,
          configCaption: modelConfigurationCaption(candidate.profile, catalogById),
          rssiText: candidate.rssi !== null ? `${candidate.rssi} dBm` : null,
          connectingPhaseLabel: isConnectingThis
            ? status.phaseLabel
            : addModel.addBusy() && selected === candidate.id
              ? "正在建立控制连接…"
              : null,
          isControlConnected: status.kind === "connected" && status.headsetId === candidate.id,
          errorMessage: err && err.id === candidate.id ? err.message : null,
        };
      });
  };

  const addUnsupportedCandidates = (): UnsupportedCandidateView[] =>
    controller
      .snapshot()
      .candidates.filter((candidate) => candidate.profile === null)
      .map((candidate) => ({
        id: candidate.id,
        name: candidate.name?.trim() || "未命名蓝牙设备",
        rssiText: candidate.rssi !== null ? `${candidate.rssi} dBm` : null,
      }));

  const addScanError = () => {
    const scan = controller.snapshot().scan;
    if (typeof scan === "object" && "Failed" in scan) return controlErrorText(scan.Failed);
    const err = addModel.addError();
    return err && err.id === "__scan__" ? err.message : null;
  };

  const addedControlSuccess = () => {
    const addedId = addModel.addedControlId();
    if (!addedId) return false;
    const status = controller.connectionStatus();
    return status.kind === "connected" && status.headsetId === addedId;
  };

  const info = () => {
    const snap = controller.snapshot();
    const headset = snap.headset;
    const current = controller.currentHeadset();
    const status = controller.connectionStatus();
    const profileId = headset?.readings.profile ?? status.profileId ?? current?.profile ?? null;
    const entry = profileId ? (catalogById.get(profileId) ?? null) : null;
    const transport = controlTransportSummary(
      headset?.transport_selection,
      status.kind !== "disconnected",
    );
    const fwText = headset
      ? fieldReadingDisplay(
          "Firmware",
          headset.readings.fields.Firmware,
          status.phase === "Initializing",
          headset.diagnostics.field_errors.Firmware,
        )
      : "未连接";
    return {
      hasHeadset: Boolean(headset || current),
      isConnected: status.kind === "connected",
      isConnecting: status.kind === "connecting",
      modelConfigLabel: modelConfigurationLabel(profileId, catalogById),
      protocolFamilyLabel: entry?.familyLabel ?? "未识别协议",
      firmwareText: fwText,
      controlTransportLabel: transport.transportLabel,
      controlTransportDetail: transport.detail,
      headsetId: headset?.headset_id ?? current?.id ?? null,
      classicAddress: headset?.classic_address ?? current?.classic_address ?? null,
    };
  };

  const diagnostics = () => {
    const snap = controller.snapshot();
    const headset = snap.headset;
    const status = controller.connectionStatus();
    const currentSessionId = status.sessionId;
    const transport = controlTransportSummary(
      headset?.transport_selection,
      status.kind !== "disconnected",
    );
    const operations: DiagnosticsOperationItem[] = [...snap.setting_operations]
      .reverse()
      .map((op) => {
        const tone: ToneVariant =
          op.stage === "Confirmed" ? "success" : isPendingStage(op.stage) ? "warning" : "danger";
        const detailParts = [`请求值：${operationRequestedText(op)}`];
        if (op.total_frames > 0) {
          detailParts.push(`发送进度：${op.written_frames}/${op.total_frames}`);
        }
        if (op.confirmed_fields.length > 0) {
          detailParts.push(`已确认：${op.confirmed_fields.map(fieldLabel).join("、")}`);
        }
        if (op.error) {
          detailParts.push(op.error.message);
        }
        if (currentSessionId !== null && op.session_id !== currentSessionId) {
          detailParts.push(`历史会话 #${op.session_id}`);
        }
        return {
          id: op.id,
          title: `${settingLabel(op.setting)} · #${op.id}`,
          stageLabel: settingStageLabel(op.stage),
          detail: detailParts.join(" · "),
          tone,
        };
      });
    const readings: DiagnosticsReadingItem[] = headset
      ? [...headset.readings.capabilities.readable_fields].sort().map((field) => ({
          field,
          label: fieldLabel(field),
          valueText: fieldReadingDisplay(
            field,
            headset.readings.fields[field],
            status.phase === "Initializing",
            headset.diagnostics.field_errors[field],
          ),
          rawHex: fieldRawHex(headset.readings.fields[field]),
        }))
      : [];
    return {
      platformRows: platformStateSummaryRows(snap),
      connectionSummary: headsetTitle(),
      connectionPhaseText:
        status.kind === "disconnected"
          ? "未连接"
          : status.kind === "connecting"
            ? status.phaseLabel
            : connectionPhaseLabel("Ready"),
      sessionIdText: currentSessionId !== null ? `#${currentSessionId}` : "无活动会话",
      controlTransportText: transport.transportLabel,
      controlTransportDetail: transport.detail,
      recoveryText: snap.connection_recovery
        ? `会话 #${snap.connection_recovery.session_id} · ${recoveryReasonLabel(snap.connection_recovery.reason)}`
        : null,
      lastErrorText: snap.last_error ? controlErrorText(snap.last_error) : null,
      operations,
      readings,
      rawSessionHeader: headset ? `会话 #${headset.session_id} · ${headset.headset_id}` : null,
      fieldErrors: headset
        ? Object.entries(headset.diagnostics.field_errors).map(([field, err]) => ({
            label: fieldLabel(field),
            detail: controlErrorText(err),
          }))
        : [],
      warnings: headset?.diagnostics.warnings ?? [],
      rawNotifications: headset?.diagnostics.raw_notifications ?? [],
      unknownNotifications: headset?.diagnostics.unknown_notifications ?? [],
    };
  };

  return {
    headsetTitle,
    modelConfigCaption: () => modelConfigurationCaption(activeProfileId(), catalogById),
    modelConfigLabel: () => modelConfigurationLabel(activeProfileId(), catalogById),
    connectionSubtitle,
    blockReason,
    connectionError,
    bondedHeadsets,
    batteryItems,
    noiseOptions,
    noiseStatus,
    activeNoiseValue,
    confirmedNoiseLabel,
    addSupportedCandidates,
    addUnsupportedCandidates,
    addScanError,
    addedControlSuccess,
    info,
    diagnostics,
  };
}
