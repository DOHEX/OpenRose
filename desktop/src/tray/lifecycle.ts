import { cleanup, signal } from "nsh";
import { Application, Window } from "nsh/desktop";
import { shouldStartMainWindowAtLaunch } from "../model/loginLaunch";
import { closesMainWindowToTray } from "../model/tray";
import type { TrayHeadsetController } from "../model/tray";
import type { DesktopSettingsStore, TrayAvailabilityStore } from "../model/trayPanelSettings";

export type MainViewRequest = "add-headset" | "tray-controls";

const QUIT_DISCONNECT_TIMEOUT_MS = 2000;

export interface DesktopLifecycle {
  mainOpen: () => boolean;
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
  startedAtLogin?: boolean;
}): DesktopLifecycle {
  const startsImmediately =
    !options.startedAtLogin || options.tray.availability().status === "unavailable";
  const mainOpen = signal(startsImmediately);
  const panelOpen = signal(false);
  const requestedView = signal<MainViewRequest | null>(null);
  let disposed = false;

  if (!startsImmediately) {
    void options.settings.whenLoaded.then(() => {
      if (disposed || mainOpen()) return;
      if (
        shouldStartMainWindowAtLaunch({
          startedAtLogin: true,
          backgroundControl: options.settings.backgroundControl(),
          availability: options.tray.refresh(),
        })
      ) {
        mainOpen.set(true);
      }
    });
  }

  const raiseMainWindow = (view?: MainViewRequest) => {
    panelOpen.set(false);
    requestedView.set(view ?? null);
    mainOpen.set(true);
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
    disposed = true;
    stopReopen();
    stopSecondInstance();
  });

  return {
    mainOpen,
    panelOpen,
    setPanelOpen: (open) => panelOpen.set(open),
    requestedView,
    raiseMainWindow,
    closeMainWindow,
    quit,
  };
}
