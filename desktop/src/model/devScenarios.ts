import { signal } from "nsh";
import { OpenRoseHeadsetFlow } from "openrose-native";
import { fullSnapshotOf } from "./desktopPages";
import type { HeadsetController } from "./desktopPages";

export type BondedScenario = "none" | "one" | "two";
export type PlatformScenario = "ok" | "permission" | "bluetooth-off" | "transport-unavailable";

const I5_ENTRY = { id: "AA:BB:CC:00:00:05", modelId: "I5_ZT" };
const I7_ENTRY = { id: "A1:B2:C3:D4:E5:F6", modelId: "I7_RS_V2" };

const BONDED_ENTRIES: Record<BondedScenario, Array<{ id: string; modelId: string }>> = {
  none: [],
  one: [I5_ENTRY],
  two: [I5_ENTRY, I7_ENTRY],
};

const PLATFORM_OVERRIDES: Record<PlatformScenario, Record<string, unknown>> = {
  ok: { permission: "Granted", scan_permission: "NotRequired", transport: "Available" },
  permission: { permission: "Required", scan_permission: "Required", transport: "Available" },
  "bluetooth-off": { permission: "Granted", scan_permission: "NotRequired", transport: "Disabled" },
  "transport-unavailable": {
    permission: "Granted",
    scan_permission: "NotRequired",
    transport: { Unavailable: { reason: { MissingDependency: { detail: "bluez" } } } },
  },
};

export function createDevScenarioModel(controller: HeadsetController) {
  const bonded = signal<BondedScenario>("one");
  const platform = signal<PlatformScenario>("ok");
  const error = signal<string | null>(null);

  async function applyBonded(next: BondedScenario) {
    error.set(null);
    try {
      await OpenRoseHeadsetFlow.devSetBondedHeadsets(
        controller.client,
        JSON.stringify(BONDED_ENTRIES[next]),
      );
      bonded.set(next);
    } catch (cause) {
      error.set(cause instanceof Error ? cause.message : String(cause));
    }
  }

  function applyPlatform(next: PlatformScenario) {
    error.set(null);
    try {
      const base = fullSnapshotOf(controller).platform;
      OpenRoseHeadsetFlow.devSetPlatformState(
        controller.client,
        JSON.stringify({ ...base, ...PLATFORM_OVERRIDES[next] }),
      );
      platform.set(next);
    } catch (cause) {
      error.set(cause instanceof Error ? cause.message : String(cause));
    }
  }

  function advertiseSupported() {
    error.set(null);
    try {
      OpenRoseHeadsetFlow.devAdvertiseScanCandidate(
        controller.client,
        "AA:BB:CC:00:00:77",
        "HEADFEEL_ZT",
        null,
        -48,
      );
    } catch (cause) {
      error.set(cause instanceof Error ? cause.message : String(cause));
    }
  }

  function advertiseUnsupported() {
    error.set(null);
    try {
      OpenRoseHeadsetFlow.devAdvertiseScanCandidate(
        controller.client,
        "11:22:33:44:55:66",
        null,
        "Other BLE",
        -70,
      );
    } catch (cause) {
      error.set(cause instanceof Error ? cause.message : String(cause));
    }
  }

  if (controller.client.devConfig()) {
    void applyBonded("one");
  }
  return { bonded, platform, error, applyBonded, applyPlatform, advertiseSupported, advertiseUnsupported };
}
