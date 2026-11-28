import { colorScheme, effect, mount, signal } from "nsh";
import { Window } from "nsh/desktop";
import {
  candidateDisplayName,
  connectionBlockMessage,
  connectionPhaseLabel,
  controlTransportSummary,
  createAddHeadsetModel,
  currentHeadsetOf,
  fieldDisplayLabel,
  fieldRawHex,
  fieldReadingDisplay,
  firstConnectionHint,
  fullSnapshotOf,
  modelConfigurationCaption,
  modelConfigurationLabel,
  operationRequestedText,
  platformStateSummaryRows,
  reconnectGuidanceText,
  recoveryReasonLabel,
  scanBlockMessage,
  settingDisplayLabel,
  settingStageLabel,
} from "./src/model/desktopPages";
import { createHeadsetSettingsModel } from "./src/model/settings";
import type {
  AddHeadsetCandidateView,
  UnsupportedCandidateView,
} from "./src/pages/AddHeadsetPage";
import { AddHeadsetPage } from "./src/pages/AddHeadsetPage";
import { HeadsetSettingsPage } from "./src/pages/settings/headset";
import type {
  DiagnosticsOperationItem,
  DiagnosticsReadingItem,
} from "./src/pages/DiagnosticsPage";
import { DiagnosticsPage } from "./src/pages/DiagnosticsPage";
import { HeadsetInfoPage } from "./src/pages/HeadsetInfoPage";
import {
  createHeadsetController,
  errorCategoryLabel,
  noiseModeLabel,
} from "./src/model/headset";
import type { HeadsetController } from "./src/model/headset";
import { createDesktopSettingsStore, createTrayAvailabilityStore } from "./src/model/trayPanelSettings";
import type { DesktopSettingsStore, TrayAvailabilityStore } from "./src/model/trayPanelSettings";
import { createLoginLaunchStore, wasStartedAtLogin } from "./src/model/loginLaunch";
import type { LoginLaunchStore } from "./src/model/loginLaunch";
import { TraySettingsPage } from "./src/pages/settings/TrayControlsSettingsPage";
import { TrayHost } from "./src/tray/TrayHost";
import { createDesktopLifecycle } from "./src/tray/lifecycle";
import type { DesktopLifecycle } from "./src/tray/lifecycle";
import { createCirculationController } from "./src/model/circulation";
import type { CirculationController } from "./src/model/circulation";
import { CirculationPage } from "./src/pages/CirculationPage";
import { DevModePage } from "./src/pages/DevModePage";
import { DevScenariosPage } from "./src/pages/DevScenariosPage";
import { createDevScenarioModel } from "./src/model/devScenarios";
import type { BondedScenario, PlatformScenario } from "./src/model/devScenarios";
import { MainPage } from "./src/pages/MainPage";
import type { BondedHeadsetItemView } from "./src/pages/MainPage";
import {
  Column,
  MutedText,
  PageShell,
  Row,
  ScrollArea,
  SidebarNav,
  StatusBadge,
  TitleBar,
} from "./src/ui";
import type {
  MetricItem,
  SegmentOption,
  SelectFieldOption,
  SidebarNavItem,
  ToneVariant,
} from "./src/ui";

type DesktopPageId =
  | "headset"
  | "add-headset"
  | "settings"
  | "info"
  | "circulation"
  | "diagnostics"
  | "tray"
  | "dev";

const LATENCY_OPTIONS: SegmentOption[] = [
  { value: "40", label: "40 ms", testID: "latency-40" },
  { value: "160", label: "160 ms", testID: "latency-160" },
  { value: "400", label: "400 ms", testID: "latency-400" },
];

const NAV_ITEMS: SidebarNavItem[] = [
  { id: "headset", label: "我的耳机", testID: "nav-headset", accessibilityLabel: "我的耳机" },
  {
    id: "add-headset",
    label: "添加耳机",
    testID: "nav-add-headset",
    accessibilityLabel: "添加耳机",
  },
  {
    id: "settings",
    label: "耳机设置",
    testID: "nav-settings",
    accessibilityLabel: "耳机设置",
  },
  { id: "info", label: "耳机信息", testID: "nav-info", accessibilityLabel: "耳机信息" },
  {
    id: "circulation",
    label: "耳机流转",
    testID: "nav-circulation",
    accessibilityLabel: "耳机流转",
  },
  {
    id: "diagnostics",
    label: "诊断与记录",
    testID: "nav-diagnostics",
    accessibilityLabel: "诊断与记录",
  },
  { id: "tray", label: "托盘与后台", testID: "nav-tray", accessibilityLabel: "托盘与后台" },
];

const DEV_NAV_ITEM: SidebarNavItem = {
  id: "dev",
  label: "开发模式",
  testID: "nav-dev",
  accessibilityLabel: "开发模式",
};

const BONDED_OPTIONS: SegmentOption[] = [
  { value: "none", label: "无", testID: "bonded-none" },
  { value: "one", label: "1 副", testID: "bonded-one" },
  { value: "two", label: "2 副", testID: "bonded-two" },
];

const PLATFORM_OPTIONS: SegmentOption[] = [
  { value: "ok", label: "正常", testID: "platform-ok" },
  { value: "permission", label: "缺权限", testID: "platform-permission" },
  { value: "bluetooth-off", label: "蓝牙关闭", testID: "platform-bluetooth-off" },
  { value: "transport-unavailable", label: "传输不可用", testID: "platform-transport-unavailable" },
];

function DesktopApp(props: {
  controller: HeadsetController;
  settings: DesktopSettingsStore;
  tray: TrayAvailabilityStore;
  lifecycle: DesktopLifecycle;
  loginLaunch: LoginLaunchStore;
  circulation: CirculationController;
}) {
  const controller = props.controller;
  const addModel = createAddHeadsetModel(controller);
  const settingsModel = createHeadsetSettingsModel(controller);
  const scenarios = createDevScenarioModel(controller);
  const initialConfig = controller.devConfig();
  const activePage = signal<DesktopPageId>("headset");
  effect(() => {
    const requested = props.lifecycle.requestedView();
    if (requested === "add-headset") activePage.set("add-headset");
    if (requested === "tray-controls") activePage.set("tray");
  });
  const rawDataOpen = signal(false);
  const selectedModelId = signal<string>(initialConfig?.modelId ?? "I5_ZT");
  const firmwareInput = signal<string>(initialConfig?.firmware ?? "125");
  const latencyValue = signal<string>(String(initialConfig?.replyLatencyMs ?? 160));

  const fullSnapshot = () => fullSnapshotOf(controller);
  const currentHeadset = () => currentHeadsetOf(controller);

  const modelOptions: SelectFieldOption[] = controller.catalog.map((model) => ({
    value: model.id,
    title: model.versionName ? `${model.displayName} (${model.versionName})` : model.displayName,
    group: model.familyLabel,
  }));

  const selectedEntry = () => controller.catalogById.get(selectedModelId()) ?? null;

  const activeProfileId = () =>
    controller.connectionStatus().profileId ??
    currentHeadset()?.profile ??
    controller.devConfig()?.modelId ??
    null;

  const headsetTitle = () => {
    const snap = fullSnapshot();
    const current = currentHeadset();
    if (snap.connection !== "Disconnected" || current) {
      const profileId = activeProfileId();
      if (profileId) {
        const entry = controller.catalogById.get(profileId);
        if (entry) {
          return entry.versionName ? `${entry.displayName} · ${entry.versionName}` : entry.displayName;
        }
      }
      if (current) {
        return candidateDisplayName(current, controller.catalogById);
      }
    }
    return "ROSE 耳机";
  };

  const modelConfigCaption = () => {
    const profileId = activeProfileId();
    return modelConfigurationCaption(profileId, controller.catalogById);
  };

  const connectionSubtitle = () => {
    const status = controller.connectionStatus();
    if (status.kind === "disconnected") {
      const snap = fullSnapshot();
      const failed = Boolean(controller.connectionError() || snap.last_error);
      return reconnectGuidanceText(snap.reconnect_guidance, failed);
    }
    const fw = controller.firmwareText();
    return fw ? `${status.phaseLabel} · 固件 ${fw}` : status.phaseLabel;
  };

  const blockReason = () => {
    const snap = fullSnapshot();
    return connectionBlockMessage(snap.connection_block_reason, snap.platform.host_platform);
  };

  const bondedHeadsetViews = (): BondedHeadsetItemView[] => {
    const snap = fullSnapshot();
    const current = currentHeadset();
    return snap.bonded_headsets.map((candidate) => ({
      id: candidate.id,
      displayName: candidateDisplayName(candidate, controller.catalogById),
      configCaption: modelConfigurationCaption(candidate.profile, controller.catalogById),
      isCurrent: current?.id === candidate.id,
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
        detail: `阶段：${feedback.stage}`,
      };
    }
    if (feedback.kind === "confirmed") {
      return {
        tone: "success",
        title: `已确认：${noiseModeLabel(feedback.mode)}`,
      };
    }
    if (feedback.kind === "failed") {
      return {
        tone: "danger",
        title: `切换失败：${errorCategoryLabel(feedback.code)} (${feedback.code})`,
        detail: feedback.message || null,
      };
    }
    return null;
  };

  const activeNoiseValue = () => {
    const feedback = controller.noiseFeedback();
    if (feedback.kind === "in_progress") return feedback.mode;
    return controller.confirmedNoiseMode();
  };

  const confirmedNoiseLabel = () => {
    const mode = controller.confirmedNoiseMode();
    return mode ? noiseModeLabel(mode) : null;
  };

  const connErr = () => controller.connectionError();
  const activeDevConfig = () => controller.devConfig();

  const handleModelChange = (value: string | null) => {
    if (!value) return;
    selectedModelId.set(value);
    const entry = controller.catalogById.get(value);
    if (entry) {
      firmwareInput.set(entry.defaultFirmware);
    }
  };

  const applySimulator = () => {
    void controller.switchSimulatedHeadset(selectedModelId(), firmwareInput());
  };

  const handleLatencyChange = (value: string) => {
    latencyValue.set(value);
    controller.setSimulatedLatencyMs(Number(value));
  };

  const navigateTo = (page: DesktopPageId) => {
    activePage.set(page);
  };

  const connectCurrent = () => {
    const current = currentHeadset();
    void controller.connect(current?.id ?? null);
  };

  const addSupportedViews = (): AddHeadsetCandidateView[] => {
    const snap = fullSnapshot();
    const err = addModel.addError();
    const selected = addModel.selectedId();
    return snap.candidates
      .filter((c) => c.profile !== null)
      .map((candidate) => {
        const conn = snap.connection;
        const isConnectingThis =
          typeof conn === "object" &&
          "Connecting" in conn &&
          conn.Connecting.headset_id === candidate.id;
        const isConnectedThis =
          typeof conn === "object" &&
          "Connected" in conn &&
          conn.Connected.headset_id === candidate.id;
        return {
          id: candidate.id,
          displayName: candidateDisplayName(candidate, controller.catalogById),
          configCaption: modelConfigurationCaption(candidate.profile, controller.catalogById),
          rssiText: candidate.rssi !== null ? `${candidate.rssi} dBm` : null,
          connectingPhaseLabel:
            typeof conn === "object" && "Connecting" in conn && isConnectingThis
              ? connectionPhaseLabel(conn.Connecting.phase)
              : addModel.addBusy() && selected === candidate.id
                ? "正在建立控制连接…"
                : null,
          isControlConnected: isConnectedThis,
          errorMessage: err && err.id === candidate.id ? err.message : null,
        };
      });
  };

  const addUnsupportedViews = (): UnsupportedCandidateView[] =>
    fullSnapshot()
      .candidates.filter((c) => c.profile === null)
      .map((candidate) => ({
        id: candidate.id,
        name: candidate.name?.trim() || "未命名蓝牙设备",
        rssiText: candidate.rssi !== null ? `${candidate.rssi} dBm` : null,
      }));

  const addScanErrorText = () => {
    const snap = fullSnapshot();
    if (typeof snap.scan === "object" && "Failed" in snap.scan) {
      return `${errorCategoryLabel(snap.scan.Failed.code)}：${snap.scan.Failed.message}`;
    }
    const err = addModel.addError();
    if (err && err.id === "__scan__") return err.message;
    return null;
  };

  const addedControlSuccess = () => {
    const addedId = addModel.addedControlId();
    if (!addedId) return false;
    const conn = fullSnapshot().connection;
    return (
      typeof conn === "object" &&
      "Connected" in conn &&
      conn.Connected.headset_id === addedId
    );
  };

  const infoView = () => {
    const snap = fullSnapshot();
    const headset = snap.headset;
    const current = currentHeadset();
    const conn = snap.connection;
    const profileId =
      headset?.readings.profile ??
      (typeof conn === "object" && "Connected" in conn
        ? conn.Connected.profile
        : current?.profile) ??
      null;
    const entry = profileId ? controller.catalogById.get(profileId) ?? null : null;
    const isConnected = typeof conn === "object" && "Connected" in conn;
    const isConnecting = typeof conn === "object" && "Connecting" in conn;
    const transport = controlTransportSummary(
      headset?.transport_selection,
      isConnected || isConnecting,
    );
    const initializing =
      typeof conn === "object" &&
      "Connecting" in conn &&
      conn.Connecting.phase === "Initializing";
    const fwReading = headset?.readings.fields.Firmware;
    const fwError = headset?.diagnostics.field_errors.Firmware;
    const fwText = headset
      ? fieldReadingDisplay("Firmware", fwReading, initializing, fwError)
      : "未连接";
    return {
      hasHeadset: Boolean(headset || current),
      isConnected,
      isConnecting,
      modelConfigLabel: modelConfigurationLabel(profileId, controller.catalogById),
      protocolFamilyLabel: entry?.familyLabel ?? "未识别协议",
      firmwareText: fwText,
      controlTransportLabel: transport.transportLabel,
      controlTransportDetail: transport.detail,
      headsetId: headset?.headset_id ?? current?.id ?? null,
      classicAddress: headset?.classic_address ?? current?.classic_address ?? null,
    };
  };

  const diagnosticsView = () => {
    const snap = fullSnapshot();
    const headset = snap.headset;
    const conn = snap.connection;
    const isConnected = typeof conn === "object" && "Connected" in conn;
    const isConnecting = typeof conn === "object" && "Connecting" in conn;
    const currentSessionId =
      typeof conn !== "object"
        ? null
        : "Connecting" in conn
          ? conn.Connecting.session_id
          : conn.Connected.session_id;
    const phaseText =
      typeof conn !== "object"
        ? "未连接"
        : "Connecting" in conn
          ? connectionPhaseLabel(conn.Connecting.phase)
          : connectionPhaseLabel("Ready");
    const transport = controlTransportSummary(
      headset?.transport_selection,
      isConnected || isConnecting,
    );
    const recoveryText = snap.connection_recovery
      ? `会话 #${snap.connection_recovery.session_id} · ${recoveryReasonLabel(snap.connection_recovery.reason)}`
      : null;
    const lastErrorText = snap.last_error
      ? `${errorCategoryLabel(snap.last_error.code)} (${snap.last_error.code})：${snap.last_error.message}`
      : null;
    const operations: DiagnosticsOperationItem[] = [...snap.setting_operations]
      .reverse()
      .map((op) => {
        const tone: ToneVariant =
          op.stage === "Confirmed"
            ? "success"
            : op.stage === "Submitted" ||
                op.stage === "PartiallyWritten" ||
                op.stage === "Written"
              ? "warning"
              : "danger";
        const detailParts = [`请求值：${operationRequestedText(op)}`];
        if (op.total_frames > 0) {
          detailParts.push(`发送进度：${op.written_frames}/${op.total_frames}`);
        }
        if (op.confirmed_fields.length > 0) {
          detailParts.push(
            `已确认：${op.confirmed_fields.map(fieldDisplayLabel).join("、")}`,
          );
        }
        if (op.error) {
          detailParts.push(op.error.message);
        }
        if (currentSessionId !== null && op.session_id !== currentSessionId) {
          detailParts.push(`历史会话 #${op.session_id}`);
        }
        return {
          id: op.id,
          title: `${settingDisplayLabel(op.setting)} · #${op.id}`,
          stageLabel: settingStageLabel(op.stage),
          detail: detailParts.join(" · "),
          tone,
        };
      });
    const initializing =
      typeof conn === "object" &&
      "Connecting" in conn &&
      conn.Connecting.phase === "Initializing";
    const readings: DiagnosticsReadingItem[] = headset
      ? [...headset.readings.capabilities.readable_fields]
          .sort()
          .map((field) => ({
            field,
            label: fieldDisplayLabel(field),
            valueText: fieldReadingDisplay(
              field,
              headset.readings.fields[field],
              initializing,
              headset.diagnostics.field_errors[field],
            ),
            rawHex: fieldRawHex(headset.readings.fields[field]),
          }))
      : [];
    const fieldErrors = headset
      ? Object.entries(headset.diagnostics.field_errors).map(([field, err]) => ({
          label: fieldDisplayLabel(field),
          detail: `${errorCategoryLabel(err.code)} (${err.code}) ${err.message}`,
        }))
      : [];
    return {
      platformRows: platformStateSummaryRows(snap),
      connectionSummary: headsetTitle(),
      connectionPhaseText: phaseText,
      sessionIdText: currentSessionId !== null ? `#${currentSessionId}` : "无活动会话",
      controlTransportText: transport.transportLabel,
      controlTransportDetail: transport.detail,
      recoveryText,
      lastErrorText,
      operations,
      readings,
      rawSessionHeader: headset
        ? `会话 #${headset.session_id} · ${headset.headset_id}`
        : null,
      fieldErrors,
      warnings: headset?.diagnostics.warnings ?? [],
      rawNotifications: headset?.diagnostics.raw_notifications ?? [],
      unknownNotifications: headset?.diagnostics.unknown_notifications ?? [],
    };
  };

  return (
    <PageShell>
      <TitleBar title="OpenRose" subtitle={activeDevConfig() ? "开发模式" : "桌面端"} />
      <Row class="flex-1">
        <Column class="w-44 shrink-0 justify-between gap-3 border-r border-sidebar-border bg-sidebar p-3">
          <SidebarNav
            accessibilityLabel="主导航"
            items={activeDevConfig() ? [...NAV_ITEMS, DEV_NAV_ITEM] : NAV_ITEMS}
            value={activePage()}
            onChange={(id) => navigateTo(id as DesktopPageId)}
          />
          <Column class="gap-1.5 rounded-(--radius) border border-sidebar-border bg-card p-2.5">
            <MutedText>当前耳机</MutedText>
            <MutedText testID="sidebar-current-headset">{headsetTitle()}</MutedText>
            <StatusBadge
              testID="sidebar-connection-badge"
              tone={
                controller.connectionStatus().kind === "connected"
                  ? "success"
                  : controller.connectionStatus().kind === "connecting"
                    ? "warning"
                    : "neutral"
              }
              label={controller.connectionStatus().label}
            />
            <StatusBadge
              testID="appearance-badge"
              tone="neutral"
              label={colorScheme() === "dark" ? "深色" : "浅色"}
            />
          </Column>
        </Column>

        <ScrollArea>
          <Column class="gap-4 p-4">
            {activePage() === "headset" ? (
              <Column class="flex-col gap-4 sm:flex-row sm:items-start">
                <MainPage
                  hasCurrentHeadset={Boolean(currentHeadset())}
                  bondedHeadsets={bondedHeadsetViews()}
                  firstConnectionHint={firstConnectionHint(fullSnapshot().platform.host_platform)}
                  headsetTitle={headsetTitle()}
                  modelConfigCaption={modelConfigCaption()}
                  connectionKind={controller.connectionStatus().kind}
                  connectionLabel={controller.connectionStatus().label}
                  connectionSubtitle={connectionSubtitle()}
                  connectionBusy={controller.connectionBusy()}
                  blockReason={blockReason()}
                  connectionErrorTitle={
                    connErr()
                      ? `连接失败：${errorCategoryLabel(connErr()!.code)} (${connErr()!.code})`
                      : null
                  }
                  connectionErrorDetail={connErr()?.message ?? null}
                  familyLabel={controller.currentModel()?.familyLabel ?? "未识别协议"}
                  batteryItems={batteryItems()}
                  noiseOptions={noiseOptions()}
                  confirmedNoiseLabel={confirmedNoiseLabel()}
                  activeNoiseValue={activeNoiseValue()}
                  noiseDisabled={
                    !controller.canControlNoiseMode() ||
                    controller.noiseFeedback().kind === "in_progress"
                  }
                  noiseStatus={noiseStatus()}
                  onConnect={connectCurrent}
                  onDisconnect={() => void controller.disconnect()}
                  onSelectCurrentHeadset={(id) => controller.selectCurrentHeadset(id)}
                  onSelectNoiseMode={(mode) => void controller.setNoiseMode(mode)}
                  onOpenAddHeadset={() => navigateTo("add-headset")}
                  onOpenHeadsetInfo={() => navigateTo("info")}
                  onOpenDiagnostics={() => navigateTo("diagnostics")}
                />
              </Column>
            ) : activePage() === "dev" && activeDevConfig() ? (
              <Column class="gap-4 sm:flex-row sm:items-start">
                <DevScenariosPage
                  bondedOptions={BONDED_OPTIONS}
                  bondedValue={scenarios.bonded()}
                  platformOptions={PLATFORM_OPTIONS}
                  platformValue={scenarios.platform()}
                  error={scenarios.error()}
                  onChangeBonded={(value) => void scenarios.applyBonded(value as BondedScenario)}
                  onChangePlatform={(value) => scenarios.applyPlatform(value as PlatformScenario)}
                  onAdvertiseSupported={scenarios.advertiseSupported}
                  onAdvertiseUnsupported={scenarios.advertiseUnsupported}
                />
                <DevModePage
                  modelOptions={modelOptions}
                  selectedModelId={selectedModelId()}
                  firmwareInput={firmwareInput()}
                  defaultFirmwarePlaceholder={selectedEntry()?.defaultFirmware ?? "125"}
                  familyHint={
                    selectedEntry() ? `协议族：${selectedEntry()!.familyLabel}` : undefined
                  }
                  firmwareHint={selectedEntry()?.firmwareHint}
                  devError={controller.devError()}
                  devBusy={controller.devBusy()}
                  latencyOptions={LATENCY_OPTIONS}
                  latencyValue={latencyValue()}
                  activeSummary={`${activeDevConfig()!.displayName} · ${activeDevConfig()!.firmware} (${activeDevConfig()!.family})`}
                  onSelectModel={handleModelChange}
                  onChangeFirmware={(val) => firmwareInput.set(val)}
                  onApplySimulator={applySimulator}
                  onChangeLatency={handleLatencyChange}
                />
              </Column>
            ) : activePage() === "circulation" ? (
              <CirculationPage controller={props.circulation} />
            ) : activePage() === "tray" ? (
              <TraySettingsPage
                settings={props.settings}
                loginLaunch={props.loginLaunch}
                availability={props.tray.availability()}
                panelOpen={props.lifecycle.panelOpen()}
                onTogglePanel={() => props.lifecycle.setPanelOpen(!props.lifecycle.panelOpen())}
              />
            ) : activePage() === "add-headset" ? (
              <AddHeadsetPage
                scanning={fullSnapshot().scan === "Scanning"}
                scanError={addScanErrorText()}
                blockReason={scanBlockMessage(fullSnapshot())}
                busy={addModel.addBusy()}
                addedControlSuccess={addedControlSuccess()}
                supportedCandidates={addSupportedViews()}
                unsupportedCandidates={addUnsupportedViews()}
                onStartScan={() => void addModel.startScan()}
                onStopScan={() => void addModel.stopScan()}
                onConnectCandidate={(id) => void addModel.connectCandidate(id)}
                onCancelConnection={() => void addModel.cancelConnection()}
                onDone={() => {
                  addModel.clearAddedState();
                  navigateTo("headset");
                }}
                onOpenDiagnostics={() => navigateTo("diagnostics")}
              />
            ) : activePage() === "settings" ? (
              <HeadsetSettingsPage model={settingsModel} />
            ) : activePage() === "info" ? (
              <HeadsetInfoPage
                {...infoView()}
                onDisconnect={() => void controller.disconnect()}
                onOpenDiagnostics={() => navigateTo("diagnostics")}
                onOpenAddHeadset={() => navigateTo("add-headset")}
              />
            ) : (
              <DiagnosticsPage
                {...diagnosticsView()}
                rawDataOpen={rawDataOpen()}
                onToggleRawData={() => rawDataOpen.set(!rawDataOpen())}
              />
            )}
          </Column>
        </ScrollArea>
      </Row>
    </PageShell>
  );
}

function DesktopRoot() {
  const controller = createHeadsetController();
  const circulation = createCirculationController(controller.client, controller.devConfig() !== null);
  const settings = createDesktopSettingsStore();
  const tray = createTrayAvailabilityStore();
  const loginLaunch = createLoginLaunchStore();
  const lifecycle = createDesktopLifecycle({
    controller,
    settings,
    tray,
    startedAtLogin: wasStartedAtLogin(),
  });
  return (
    <>
      <TrayHost controller={controller} settings={settings} tray={tray} lifecycle={lifecycle} />
      <Window
        id="main"
        open={lifecycle.mainOpen()}
        title="OpenRose"
        closeOnPress={false}
        onClosePress={lifecycle.closeMainWindow}
        defaultSize={{ width: 880, height: 640 }}
        minSize={{ width: 640, height: 520 }}
      >
        <DesktopApp
          controller={controller}
          settings={settings}
          tray={tray}
          lifecycle={lifecycle}
          loginLaunch={loginLaunch}
          circulation={circulation}
        />
      </Window>
    </>
  );
}

mount(() => <DesktopRoot />);
