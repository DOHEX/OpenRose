import { appInfo, signal } from "nsh";
import { OpenRoseLoginLaunch } from "openrose-native";
import { closesMainWindowToTray } from "./tray";
import type { TrayAvailability } from "./trayPanelSettings";

export type LoginLaunchStatus =
  | { kind: "hidden"; reason: string }
  | { kind: "unsupported"; reason: string }
  | { kind: "ready"; enabled: boolean };

export interface LoginLaunchStatusReport {
  installed: boolean;
  supported: boolean;
  enabled: boolean;
  reason?: string | null;
}

export interface LoginLaunchStore {
  status: () => LoginLaunchStatus;
  busy: () => boolean;
  error: () => string | null;
  refresh: () => LoginLaunchStatus;
  setEnabled: (enabled: boolean) => Promise<void>;
}

const DEFAULT_HIDDEN_REASON = "仅安装版可设置登录时启动";
const DEFAULT_UNSUPPORTED_REASON = "当前系统暂不支持登录时启动";

function sameStatus(a: LoginLaunchStatus, b: LoginLaunchStatus): boolean {
  if (a.kind !== b.kind) return false;
  if (a.kind === "ready" && b.kind === "ready") return a.enabled === b.enabled;
  if (a.kind !== "ready" && b.kind !== "ready") return a.reason === b.reason;
  return false;
}

export function normalizeLoginLaunchStatus(report: LoginLaunchStatusReport): LoginLaunchStatus {
  if (!report.installed) {
    return {
      kind: "hidden",
      reason: report.reason?.trim() || DEFAULT_HIDDEN_REASON,
    };
  }
  if (!report.supported) {
    return {
      kind: "unsupported",
      reason: report.reason?.trim() || DEFAULT_UNSUPPORTED_REASON,
    };
  }
  return {
    kind: "ready",
    enabled: Boolean(report.enabled),
  };
}

export function shouldStartMainWindowAtLaunch(options: {
  startedAtLogin: boolean;
  backgroundControl: boolean;
  availability: TrayAvailability;
}): boolean {
  if (!options.startedAtLogin) return true;
  return !closesMainWindowToTray(options.backgroundControl, options.availability);
}

export function wasStartedAtLogin(): boolean {
  try {
    return Boolean(OpenRoseLoginLaunch.wasStartedAtLogin());
  } catch {
    return false;
  }
}

function readLoginLaunchStatus(): LoginLaunchStatus {
  try {
    const report = OpenRoseLoginLaunch.status(appInfo().development) as LoginLaunchStatusReport;
    return normalizeLoginLaunchStatus(report);
  } catch (error) {
    return {
      kind: "unsupported",
      reason: error instanceof Error ? error.message : DEFAULT_UNSUPPORTED_REASON,
    };
  }
}

export function createLoginLaunchStore(): LoginLaunchStore {
  const status = signal<LoginLaunchStatus>(readLoginLaunchStatus());
  const busy = signal(false);
  const error = signal<string | null>(null);

  const setStatus = (next: LoginLaunchStatus): LoginLaunchStatus => {
    if (!sameStatus(status(), next)) {
      status.set(next);
    }
    return next;
  };

  const refresh = (): LoginLaunchStatus => setStatus(readLoginLaunchStatus());

  const setEnabled = async (enabled: boolean): Promise<void> => {
    if (busy()) return;
    busy.set(true);
    error.set(null);
    try {
      const report = (await Promise.resolve(
        OpenRoseLoginLaunch.setEnabled(appInfo().development, enabled),
      )) as LoginLaunchStatusReport;
      setStatus(normalizeLoginLaunchStatus(report));
    } catch (err) {
      error.set(err instanceof Error ? err.message : String(err));
      refresh();
    } finally {
      busy.set(false);
    }
  };

  return {
    status,
    busy,
    error,
    refresh,
    setEnabled,
  };
}
