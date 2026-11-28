import type { Bounds, Point, Size } from "nsh";
import { cleanup, computed, effect, platform, signal } from "nsh";
import type { MenuItem } from "nsh/desktop";
import { Tray, Window, displays } from "nsh/desktop";
import { firstRemoteHolder } from "../model/circulation";
import type { CirculationController } from "../model/circulation";
import {
  TRAY_PANEL_TITLE,
  buildTrayMenuEntries,
  computeTraySummary,
  shouldCloseTrayPanelOnBlur,
  trayIconState,
  trayTooltip,
  usesNativeCloseOnBlur,
} from "../model/tray";
import type { TrayHeadsetController } from "../model/tray";
import { createTrayPanelModel } from "../model/trayPanel";
import type { DesktopSettingsStore, TrayAvailabilityStore } from "../model/trayPanelSettings";
import type { DesktopLifecycle } from "./lifecycle";
import { TrayPanel } from "./TrayPanel";
import { computeTrayPanelPosition } from "./panelPosition";
import trayConnected from "./tray-connected.svg";
import trayDisconnected from "./tray-disconnected.svg";
import trayRemote from "./tray-remote.svg";

const TRAY_PANEL_SIZE: Size = { width: 340, height: 460 };
const BLUR_TOGGLE_SUPPRESS_MS = 250;

export interface TrayHostProps {
  controller: TrayHeadsetController;
  circulation: CirculationController;
  settings: DesktopSettingsStore;
  tray: TrayAvailabilityStore;
  lifecycle: DesktopLifecycle;
}

export function TrayHost(props: TrayHostProps) {
  const lifecycle = props.lifecycle;
  const summary = computed(() =>
    computeTraySummary(props.controller, firstRemoteHolder(props.circulation.snapshot()), {
      currentOutput: props.circulation.currentOutput(),
      soundSettingsAvailable: props.circulation.soundSettingsAvailable(),
    }),
  );
  const panelModel = createTrayPanelModel(props.controller, props.settings.trayPanel, summary);
  const tooltipStarted = signal(false);
  const panelPosition = signal<Point | "center">("center");
  const lastTrayBounds = signal<Bounds | null>(null);
  const openPopups = signal(0);
  let pointerEntered = false;
  let lastBlurClosedAt = 0;

  const tooltipTimer = setTimeout(() => tooltipStarted.set(true), 0);

  const resetPanelPointer = () => {
    pointerEntered = false;
    openPopups.set(0);
  };

  const openPanel = (bounds: Bounds | null) => {
    if (bounds) lastTrayBounds.set(bounds);
    const anchor = bounds ?? lastTrayBounds();
    panelPosition.set(
      platform.os !== "linux" && anchor
        ? computeTrayPanelPosition(anchor, TRAY_PANEL_SIZE, displays())
        : "center",
    );
    resetPanelPointer();
    void props.circulation.refreshAudioState();
    lifecycle.setPanelOpen(true);
    Window.get("tray-panel")?.focus();
  };
  const togglePanel = (bounds: Bounds | null) => {
    if (lifecycle.panelOpen()) {
      resetPanelPointer();
      lifecycle.setPanelOpen(false);
      return;
    }
    if (Date.now() - lastBlurClosedAt < BLUR_TOGGLE_SUPPRESS_MS) {
      lastBlurClosedAt = 0;
      return;
    }
    openPanel(bounds);
  };

  const closePanel = (blurred: boolean) => {
    resetPanelPointer();
    if (blurred) lastBlurClosedAt = Date.now();
    lifecycle.setPanelOpen(false);
  };

  effect(() => {
    if (props.tray.availability().status === "unavailable") {
      Tray.set(null);
      return;
    }
    const current = summary();
    const entries = buildTrayMenuEntries(current, lifecycle.panelOpen(), {
      togglePanel: () => togglePanel(null),
      openMain: () => lifecycle.raiseMainWindow(),
      openAddHeadset: () => lifecycle.raiseMainWindow("add-headset"),
      openSoundSettings: () => props.circulation.openSoundSettings(),
      reconnect: (headsetId) => void props.controller.connect(headsetId),
      selectNoiseMode: (mode, sessionId) => void props.controller.setNoiseMode(mode, sessionId),
      quit: () => void lifecycle.quit(),
    });
    const menu: MenuItem[] = entries.map((entry) => {
      if (entry.kind === "separator") return { type: "separator" };
      if (entry.kind === "label") return { label: entry.label ?? "", disabled: true };
      if (entry.kind === "noise_mode") {
        return {
          label: entry.label ?? "",
          checked: entry.checked,
          disabled: entry.disabled,
          onSelect: entry.onSelect,
        };
      }
      return { label: entry.label ?? "", onSelect: entry.onSelect };
    });
    Tray.set({
      icon: { connected: trayConnected, remote: trayRemote, disconnected: trayDisconnected }[
        trayIconState(current)
      ],
      tooltip: trayTooltip(tooltipStarted(), current),
      onClick: ({ bounds }) => togglePanel(bounds),
      menu,
    });
  });

  cleanup(() => {
    clearTimeout(tooltipTimer);
    Tray.set(null);
  });

  return (
    <Window
      id="tray-panel"
      open={lifecycle.panelOpen()}
      kind="popup"
      title={TRAY_PANEL_TITLE}
      defaultSize={TRAY_PANEL_SIZE}
      defaultPosition={panelPosition()}
      resizable={false}
      maximizable={false}
      minimizable={false}
      closeOnEscape={true}
      closeOnBlur={usesNativeCloseOnBlur(platform.os)}
      onFocusChange={({ focused }) => {
        if (
          lifecycle.panelOpen() &&
          shouldCloseTrayPanelOnBlur({
            os: platform.os,
            focused,
            pointerEntered,
            popupsOpen: openPopups(),
          })
        ) {
          closePanel(true);
        }
      }}
      onOpenChange={({ open, reason }) => {
        if (!open) closePanel(reason === "blur");
      }}
    >
      <TrayPanel
        model={panelModel}
        pulling={props.circulation.pullingAddress() !== null}
        pullFeedback={props.circulation.pullFeedback()}
        onPull={(address) => void props.circulation.pull(address)}
        onOpenSoundSettings={() => props.circulation.openSoundSettings()}
        onConnect={(headsetId) => void props.controller.connect(headsetId)}
        onDisconnect={() => void props.controller.disconnect()}
        onSelectNoiseMode={(mode, sessionId) => void props.controller.setNoiseMode(mode, sessionId)}
        onOpenMain={() => lifecycle.raiseMainWindow()}
        onOpenAddHeadset={() => lifecycle.raiseMainWindow("add-headset")}
        onOpenControlSettings={() => lifecycle.raiseMainWindow("tray-controls")}
        onQuit={() => void lifecycle.quit()}
        onPointerEnter={() => {
          pointerEntered = true;
        }}
        onPopupOpenChange={(open) => openPopups.update((count) => Math.max(0, count + (open ? 1 : -1)))}
      />
    </Window>
  );
}
