import { cleanup, signal } from "nsh";
import { Application, Window } from "nsh/desktop";
import { closesMainWindowToTray } from "../model/tray";
import type { TrayHeadsetController } from "../model/tray";
import type { DesktopSettingsStore, TrayAvailabilityStore } from "../model/trayPanelSettings";

export type MainViewRequest = "add-headset" | "tray-controls";

const QUIT_DISCONNECT_TIMEOUT_MS = 2000;

export interface DesktopLifecycle {
  panelOpen: () => boolean;
  setPanelOpen: (open: boolean) => void;
  requestedView: () => MainViewRequest | null;
  raiseMainWindow: (view?: MainViewRequest) => void;
  closeMainWindow: () => void;
  quit: () => Promise<void>;
}

export function createDesktopLifecycle(options: {
  controller: TrayHeadsetController;
  settings: DesktopSettingsStore;
  tray: TrayAvailabilityStore;
}): DesktopLifecycle {
  const panelOpen = signal(false);
  const requestedView = signal<MainViewRequest | null>(null);

  const raiseMainWindow = (view?: MainViewRequest) => {
    panelOpen.set(false);
    requestedView.set(view ?? null);
    const main = Window.get("main");
    main?.show();
    main?.focus();
  };

  const quit = async () => {
    panelOpen.set(false);
    const timeout = new Promise<void>((resolve) => setTimeout(resolve, QUIT_DISCONNECT_TIMEOUT_MS));
    try {
      await Promise.race([options.controller.disconnect(), timeout]);
    } finally {
      Application.quit();
    }
  };

  const closeMainWindow = () => {
    if (closesMainWindowToTray(options.settings.backgroundControl(), options.tray.refresh())) {
      Window.get("main")?.hide();
      return;
    }
    void quit();
  };

  const stopReopen = Application.on("reopen", () => raiseMainWindow());
  const stopSecondInstance = Application.on("secondInstance", () => raiseMainWindow());
  cleanup(() => {
    stopReopen();
    stopSecondInstance();
  });

  return {
    panelOpen,
    setPanelOpen: (open) => panelOpen.set(open),
    requestedView,
    raiseMainWindow,
    closeMainWindow,
    quit,
  };
}
