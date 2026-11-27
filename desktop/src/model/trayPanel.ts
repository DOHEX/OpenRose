import { signal } from "nsh";
import { OpenRoseTray } from "openrose-native";
import { errorCategoryLabel, readKnownNumber, readKnownText, readKnownToggle } from "./headset";
import type { HeadsetState, SettingOperation } from "./headset";
import { computeTraySummary } from "./tray";
import type { TrayHeadsetController, TraySummary } from "./tray";
import { supportedTrayControls } from "./trayPanelSettings";
import type { TrayPanelControl, TrayPanelSettings } from "./trayPanelSettings";

export interface TrayChoice {
  value: string;
  label: string;
  testID: string;
}

export type TrayField =
  | {
      kind: "segments";
      testID: string;
      label: string;
      options: TrayChoice[];
      value: string | null;
      disabled: boolean;
      onChange: (value: string) => void;
    }
  | {
      kind: "toggle";
      testID: string;
      label: string;
      checked: boolean;
      disabled: boolean;
      caption: string | null;
      onChange: (checked: boolean) => void;
    }
  | {
      kind: "select";
      testID: string;
      label: string;
      items: { value: string; title: string }[];
      value: string | null;
      disabled: boolean;
      hint: string | null;
      onChange: (value: string) => void;
    };

export interface TrayControlGroup {
  id: string;
  fields: TrayField[];
}

export interface TrayPanelModel {
  summary: () => TraySummary;
  controlGroups: () => TrayControlGroup[];
  commandError: () => string | null;
}

const PREREQUISITE_TEXT: Record<string, string> = {
  SpatialModeRequired: "需先开启空间模式",
  IndependentMicActivationRequired: "需先激活独立麦克风",
};

const SPATIAL_LABELS: Record<string, string> = {
  off: "关闭",
  head_tracking: "头部跟踪",
  fixed: "固定",
  tv: "电视",
  music: "音乐",
  movie: "电影",
  game: "游戏",
};

function spatialLabel(value: string): string {
  return SPATIAL_LABELS[value] ?? value;
}

const PENDING_STAGES = ["Submitted", "PartiallyWritten", "Written"];

function pendingOperation(
  operations: SettingOperation[],
  headset: HeadsetState,
  setting: string,
): SettingOperation | null {
  const pending = operations.filter(
    (op) =>
      op.session_id === headset.session_id &&
      op.setting === setting &&
      PENDING_STAGES.includes(op.stage),
  );
  return pending.length > 0 ? pending[pending.length - 1] : null;
}

export function createTrayPanelModel(
  controller: TrayHeadsetController,
  settings: () => TrayPanelSettings,
): TrayPanelModel {
  const commandError = signal<string | null>(null);

  const run = async (command: Record<string, unknown>, sessionId: number) => {
    commandError.set(null);
    try {
      await controller.executeCommand(JSON.stringify(command), sessionId);
    } catch (error) {
      const last = controller.snapshot().last_error;
      commandError.set(
        last
          ? `${errorCategoryLabel(last.code)}：${last.message}`
          : error instanceof Error
            ? error.message
            : String(error),
      );
    }
  };

  const toggleField = (
    headset: HeadsetState,
    operations: SettingOperation[],
    spec: { testID: string; label: string; setting: string; field: string },
  ): TrayField => {
    const value = readKnownToggle(headset.readings.fields, spec.field);
    const prerequisite = OpenRoseTray.settingPrerequisite(headset.readings, spec.setting);
    const pending = pendingOperation(operations, headset, spec.setting);
    const pendingValue = pending && "Toggle" in pending.requested_value ? pending.requested_value.Toggle : null;
    const caption = prerequisite
      ? (PREREQUISITE_TEXT[prerequisite] ?? prerequisite)
      : pendingValue === null
        ? value === null
          ? "当前状态未知"
          : null
        : pendingValue
          ? "正在开启…"
          : "正在关闭…";
    return {
      kind: "toggle",
      testID: spec.testID,
      label: spec.label,
      checked: pendingValue ?? value ?? false,
      disabled: prerequisite !== null || pending !== null || value === null,
      caption,
      onChange: (checked) => void run({ [spec.setting]: checked }, headset.session_id),
    };
  };

  const selectField = (
    headset: HeadsetState,
    operations: SettingOperation[],
    spec: {
      testID: string;
      label: string;
      setting: string;
      field: string;
      items: { value: string; title: string }[];
      titleOf: (value: string) => string;
    },
  ): TrayField => {
    const confirmed = readKnownText(headset.readings.fields, spec.field);
    const pending = pendingOperation(operations, headset, spec.setting);
    const pendingValue = pending && "Text" in pending.requested_value ? pending.requested_value.Text : null;
    return {
      kind: "select",
      testID: spec.testID,
      label: spec.label,
      items: spec.items,
      value: pendingValue ?? confirmed,
      disabled: pending !== null,
      hint: pendingValue ? `正在切换至${spec.titleOf(pendingValue)}…` : null,
      onChange: (value) => {
        if (value === confirmed) return;
        void run({ [spec.setting]: value }, headset.session_id);
      },
    };
  };

  const levelField = (headset: HeadsetState, operations: SettingOperation[]): TrayField | null => {
    const capabilities = headset.readings.capabilities;
    const mode = readKnownText(headset.readings.fields, "NoiseMode");
    const control = capabilities.noise_control;
    if (!control || !mode) return null;
    const candidates = [
      { setting: "AncLevel", label: "降噪档位", levels: control.anc_level },
      { setting: "TransparencyLevel", label: "通透档位", levels: control.transparency_level },
    ];
    const match = candidates.find(
      (candidate) =>
        capabilities.writable_settings.includes(candidate.setting) &&
        candidate.levels !== null &&
        candidate.levels.levels.length > 0 &&
        candidate.levels.visible_modes.includes(mode),
    );
    if (!match || !match.levels) return null;
    const current = readKnownNumber(headset.readings.fields, match.setting);
    const pending = pendingOperation(operations, headset, match.setting);
    const pendingLevel = pending && "Number" in pending.requested_value ? pending.requested_value.Number : null;
    return {
      kind: "segments",
      testID: `tray-level-${match.setting}`,
      label: match.label,
      options: [...match.levels.levels]
        .sort((a, b) => a - b)
        .map((level) => ({
          value: String(level),
          label: `${level} 档`,
          testID: `tray-level-${match.setting}-${level}`,
        })),
      value: pendingLevel !== null ? String(pendingLevel) : current !== null ? String(current) : null,
      disabled: pending !== null,
      onChange: (value) => {
        const level = Number(value);
        if (level === current) return;
        void run({ [match.setting]: level }, headset.session_id);
      },
    };
  };

  const controlFields = (
    control: TrayPanelControl,
    headset: HeadsetState,
    operations: SettingOperation[],
  ): TrayField[] => {
    const capabilities = headset.readings.capabilities;
    const writable = capabilities.writable_settings;
    if (control === "game_mode") {
      return [
        toggleField(headset, operations, {
          testID: "tray-toggle-game_mode",
          label: "游戏模式",
          setting: "GameMode",
          field: "GameMode",
        }),
      ];
    }
    if (control === "equalizer") {
      const profile = headset.readings.profile;
      const titleOf = (preset: string) => OpenRoseTray.equalizerPresetLabel(profile, preset) ?? preset;
      return [
        selectField(headset, operations, {
          testID: "tray-select-equalizer",
          label: "均衡器",
          setting: "Eq",
          field: "Eq",
          items: (capabilities.equalizer?.presets ?? []).map((preset) => ({
            value: preset,
            title: titleOf(preset),
          })),
          titleOf,
        }),
      ];
    }
    if (control === "spatial_audio") {
      if (!writable.includes("SpatialMode")) {
        return [
          toggleField(headset, operations, {
            testID: "tray-toggle-spatial_audio",
            label: "空间音频",
            setting: "SpatialAudio",
            field: "SpatialAudio",
          }),
        ];
      }
      const fields = [
        selectField(headset, operations, {
          testID: "tray-select-spatial-mode",
          label: "空间模式",
          setting: "SpatialMode",
          field: "SpatialMode",
          items: capabilities.spatial_modes.map((mode) => ({ value: mode, title: spatialLabel(mode) })),
          titleOf: spatialLabel,
        }),
      ];
      if (
        writable.includes("SpatialContent") &&
        OpenRoseTray.settingPrerequisite(headset.readings, "SpatialContent") === null
      ) {
        fields.push(
          selectField(headset, operations, {
            testID: "tray-select-spatial-content",
            label: "空间音频内容",
            setting: "SpatialContent",
            field: "SpatialContent",
            items: capabilities.spatial_contents.map((item) => ({
              value: item,
              title: spatialLabel(item),
            })),
            titleOf: spatialLabel,
          }),
        );
      }
      return fields;
    }
    return [
      toggleField(headset, operations, {
        testID: "tray-toggle-independent_mic",
        label: "独立麦克风模式",
        setting: "IndependentMicMode",
        field: "IndependentMicMode",
      }),
    ];
  };

  const controlGroups = (): TrayControlGroup[] => {
    const snapshot = controller.snapshot();
    const headset = snapshot.headset;
    if (!headset || !computeTraySummary(controller).connected) return [];
    const operations = snapshot.setting_operations;
    const groups: TrayControlGroup[] = [];
    const level = settings().showNoiseLevels ? levelField(headset, operations) : null;
    if (level) groups.push({ id: "noise_level", fields: [level] });
    for (const control of supportedTrayControls(settings(), headset.readings.capabilities)) {
      groups.push({ id: control, fields: controlFields(control, headset, operations) });
    }
    return groups;
  };

  return {
    summary: () => computeTraySummary(controller),
    controlGroups,
    commandError: () => commandError(),
  };
}
