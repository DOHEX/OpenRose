import { colorScheme, mount, signal } from "nsh";
import { Window } from "nsh/desktop";
import { createHeadsetController, errorCategoryLabel, noiseModeLabel } from "./src/model/headset";
import type { HeadsetController } from "./src/model/headset";
import { createDesktopSettingsStore, createTrayAvailabilityStore } from "./src/model/trayPanelSettings";
import type { DesktopSettingsStore, TrayAvailabilityStore } from "./src/model/trayPanelSettings";
import { DevModePage } from "./src/pages/DevModePage";
import { MainPage } from "./src/pages/MainPage";
import { TraySettingsPage } from "./src/pages/settings/TrayControlsSettingsPage";
import { TrayHost } from "./src/tray/TrayHost";
import { createDesktopLifecycle } from "./src/tray/lifecycle";
import type { DesktopLifecycle } from "./src/tray/lifecycle";
import { Column, PageShell, ScrollArea, StatusBadge, TitleBar } from "./src/ui";
import type { MetricItem, SegmentOption, SelectFieldOption, ToneVariant } from "./src/ui";

const LATENCY_OPTIONS: SegmentOption[] = [
  { value: "40", label: "40 ms", testID: "latency-40" },
  { value: "160", label: "160 ms", testID: "latency-160" },
  { value: "400", label: "400 ms", testID: "latency-400" },
];

function DesktopApp(props: {
  controller: HeadsetController;
  settings: DesktopSettingsStore;
  tray: TrayAvailabilityStore;
  lifecycle: DesktopLifecycle;
}) {
  const controller = props.controller;
  const initialConfig = controller.devConfig();
  const selectedModelId = signal<string>(initialConfig?.modelId ?? "I5_ZT");
  const firmwareInput = signal<string>(initialConfig?.firmware ?? "125");
  const latencyValue = signal<string>(String(initialConfig?.replyLatencyMs ?? 160));

  const modelOptions: SelectFieldOption[] = controller.catalog.map((model) => ({
    value: model.id,
    title: model.versionName ? `${model.displayName} (${model.versionName})` : model.displayName,
    group: model.familyLabel,
  }));

  const selectedEntry = () => controller.catalogById.get(selectedModelId()) ?? null;

  const headsetTitle = () => {
    const model = controller.currentModel();
    if (!model) return "ROSE 耳机";
    return model.versionName ? `${model.displayName} · ${model.versionName}` : model.displayName;
  };

  const connectionSubtitle = () => {
    const phaseLabel = controller.connectionStatus().phaseLabel;
    const fw = controller.firmwareText();
    return fw ? `${phaseLabel} · 固件 ${fw}` : phaseLabel;
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

  return (
    <PageShell>
      <TitleBar
        title="OpenRose"
        subtitle={activeDevConfig() ? "开发模式" : "桌面端"}
        right={
          <StatusBadge
            testID="appearance-badge"
            tone="neutral"
            label={colorScheme() === "dark" ? "深色" : "浅色"}
          />
        }
      />
      <ScrollArea>
        <Column class="flex-col gap-4 p-4 sm:flex-row sm:items-start">
          <MainPage
            headsetTitle={headsetTitle()}
            connectionKind={controller.connectionStatus().kind}
            connectionLabel={controller.connectionStatus().label}
            connectionSubtitle={connectionSubtitle()}
            connectionBusy={controller.connectionBusy()}
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
            onConnect={() => void controller.connect()}
            onDisconnect={() => void controller.disconnect()}
            onSelectNoiseMode={(mode) => void controller.setNoiseMode(mode)}
          />
          {activeDevConfig() ? (
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
          ) : null}
          <TraySettingsPage
            settings={props.settings}
            availability={props.tray.availability()}
            highlightControls={props.lifecycle.requestedView() === "tray-controls"}
            panelOpen={props.lifecycle.panelOpen()}
            onTogglePanel={() => props.lifecycle.setPanelOpen(!props.lifecycle.panelOpen())}
          />
        </Column>
      </ScrollArea>
    </PageShell>
  );
}

function DesktopRoot() {
  const controller = createHeadsetController();
  const settings = createDesktopSettingsStore();
  const tray = createTrayAvailabilityStore();
  const lifecycle = createDesktopLifecycle({ controller, settings, tray });
  return (
    <>
      <TrayHost controller={controller} settings={settings} tray={tray} lifecycle={lifecycle} />
      <Window
        id="main"
        title="OpenRose"
        closeOnPress={false}
        onClosePress={lifecycle.closeMainWindow}
        defaultSize={{ width: 720, height: 640 }}
        minSize={{ width: 440, height: 520 }}
      >
        <DesktopApp controller={controller} settings={settings} tray={tray} lifecycle={lifecycle} />
      </Window>
    </>
  );
}

mount(() => <DesktopRoot />);
