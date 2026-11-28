import { colorScheme, effect, mount, signal } from "nsh";
import { Window } from "nsh/desktop";
import {
  createAddHeadsetModel,
  createDesktopViews,
  firstConnectionHint,
  scanBlockMessage,
} from "./src/model/desktopPages";
import { createHeadsetSettingsModel } from "./src/model/settings";
import { AddHeadsetPage } from "./src/pages/AddHeadsetPage";
import { HeadsetSettingsPage } from "./src/pages/settings/headset";
import { DiagnosticsPage } from "./src/pages/DiagnosticsPage";
import { HeadsetInfoPage } from "./src/pages/HeadsetInfoPage";
import { createHeadsetController } from "./src/model/headset";
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

  const views = createDesktopViews(controller, addModel);

  const modelOptions: SelectFieldOption[] = controller.catalog.map((model) => ({
    value: model.id,
    title: model.versionName ? `${model.displayName} (${model.versionName})` : model.displayName,
    group: model.familyLabel,
  }));

  const selectedEntry = () => controller.catalogById.get(selectedModelId()) ?? null;

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
    void controller.connect(controller.currentHeadset()?.id ?? null);
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
            <MutedText testID="sidebar-current-headset">{views.headsetTitle()}</MutedText>
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
                  hasCurrentHeadset={Boolean(controller.currentHeadset())}
                  bondedHeadsets={views.bondedHeadsets()}
                  firstConnectionHint={firstConnectionHint(controller.snapshot().platform.host_platform)}
                  headsetTitle={views.headsetTitle()}
                  modelConfigCaption={views.modelConfigCaption()}
                  connectionKind={controller.connectionStatus().kind}
                  connectionLabel={controller.connectionStatus().label}
                  connectionSubtitle={views.connectionSubtitle()}
                  connectionBusy={controller.connectionBusy()}
                  blockReason={views.blockReason()}
                  connectionErrorTitle={views.connectionError()?.title ?? null}
                  connectionErrorDetail={views.connectionError()?.detail ?? null}
                  modelConfigLabel={views.modelConfigLabel()}
                  batteryItems={views.batteryItems()}
                  noiseOptions={views.noiseOptions()}
                  confirmedNoiseLabel={views.confirmedNoiseLabel()}
                  activeNoiseValue={views.activeNoiseValue()}
                  noiseDisabled={
                    !controller.canControlNoiseMode() ||
                    controller.noiseFeedback().kind === "in_progress"
                  }
                  noiseStatus={views.noiseStatus()}
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
                scanning={controller.snapshot().scan === "Scanning"}
                scanError={views.addScanError()}
                blockReason={scanBlockMessage(controller.snapshot())}
                busy={addModel.addBusy()}
                addedControlSuccess={views.addedControlSuccess()}
                supportedCandidates={views.addSupportedCandidates()}
                unsupportedCandidates={views.addUnsupportedCandidates()}
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
                {...views.info()}
                onDisconnect={() => void controller.disconnect()}
                onOpenDiagnostics={() => navigateTo("diagnostics")}
                onOpenAddHeadset={() => navigateTo("add-headset")}
              />
            ) : (
              <DiagnosticsPage
                {...views.diagnostics()}
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
  const circulation = createCirculationController(controller, controller.devConfig() !== null);
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
      <TrayHost
        controller={controller}
        circulation={circulation}
        settings={settings}
        tray={tray}
        lifecycle={lifecycle}
      />
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
