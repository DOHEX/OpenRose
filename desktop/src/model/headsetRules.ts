import { OpenRoseSettings } from "openrose-native";
import { semanticLabel } from "./labels";
import type { FieldReading, HeadsetCapabilities } from "./headset";

export interface ProfileLabels {
  versionName: string | null;
  equalizerPresetLabels: Record<string, string>;
}

export interface VisibleLevel {
  setting: "AncLevel" | "TransparencyLevel";
  levels: number[];
}

const PRESET_CODES: Record<string, string> = {
  pop: "POP",
  hifi: "HIFI",
  rock: "ROCK",
  light: "LIGHT",
  eap: "EAP",
};

const EMPTY_PROFILE_LABELS: ProfileLabels = { versionName: null, equalizerPresetLabels: {} };

const profileLabelsCache = new Map<string, ProfileLabels>();

export function profileLabelsOf(profileId: string | null): ProfileLabels {
  if (!profileId) return EMPTY_PROFILE_LABELS;
  const cached = profileLabelsCache.get(profileId);
  if (cached) return cached;
  const loaded = OpenRoseSettings.profileLabels(profileId) as ProfileLabels;
  profileLabelsCache.set(profileId, loaded);
  return loaded;
}

export function equalizerPresetLabel(profileId: string | null, preset: string): string {
  const code = PRESET_CODES[preset];
  const custom = profileLabelsOf(profileId).equalizerPresetLabels[preset];
  if (!code) return custom ?? semanticLabel(preset);
  if (!custom || custom.toLowerCase() === code.toLowerCase()) return code;
  return `${custom} · ${code}`;
}

export function hasEqualizerPresets(capabilities: HeadsetCapabilities): boolean {
  return (
    capabilities.writable_settings.includes("Eq") && (capabilities.equalizer?.presets.length ?? 0) > 0
  );
}

export function visibleLevels(
  capabilities: HeadsetCapabilities,
  noiseMode: string | null,
): VisibleLevel[] {
  const control = capabilities.noise_control;
  if (!control || noiseMode === null) return [];
  const candidates = [
    { setting: "AncLevel", levels: control.anc_level },
    { setting: "TransparencyLevel", levels: control.transparency_level },
  ] as const;
  return candidates.flatMap((candidate) =>
    capabilities.writable_settings.includes(candidate.setting) &&
    candidate.levels !== null &&
    candidate.levels.levels.length > 0 &&
    candidate.levels.visible_modes.includes(noiseMode)
      ? [{ setting: candidate.setting, levels: [...candidate.levels.levels].sort((a, b) => a - b) }]
      : [],
  );
}

export function unmetPrerequisites(fields: Record<string, FieldReading>): Record<string, string> {
  return OpenRoseSettings.settingPrerequisites(fields) as Record<string, string>;
}
