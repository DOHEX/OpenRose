import { computed, effect, signal } from "nsh";
import { OpenRoseSettings } from "openrose-native";
import {
  controlErrorOf,
  isFailedStage,
  isPendingStage,
  readKnownIntegerList,
  readKnownNoiseCycle,
  readKnownNumber,
  readKnownText,
  readKnownToggle,
  readKnownTouchKeys,
} from "./headset";
import type {
  ControlError,
  ControlSnapshot,
  CustomEqualizerCapabilities,
  FieldReading,
  HeadsetController,
  SettingOperation,
  SettingStage,
  TouchControlCapabilities,
  TouchSlot,
} from "./headset";
import {
  equalizerPresetLabel,
  hasEqualizerPresets,
  profileLabelsOf,
  unmetPrerequisites,
  visibleLevels,
} from "./headsetRules";
import type { ProfileLabels } from "./headsetRules";
import {
  errorCategoryLabel,
  noiseModeLabel,
  prerequisiteLabel,
  semanticLabel,
  settingLabel,
  sortNoiseModes,
} from "./labels";

export type SettingFeedback =
  | { kind: "idle" }
  | { kind: "in_progress"; setting: string; label: string; stage: SettingStage }
  | { kind: "confirmed"; setting: string; label: string }
  | {
      kind: "failed";
      setting: string;
      label: string;
      code: string;
      reason: string;
      message: string;
    };

export interface Choice {
  value: string;
  label: string;
}

export interface LevelChoice {
  levels: number[];
  confirmed: number | null;
}

export interface TextChoice {
  options: Choice[];
  confirmed: string | null;
}

export interface ActionChoice {
  value: string;
  title: string;
}

export interface NoiseCycleView {
  known: boolean;
  minimum: number;
  atMinimum: boolean;
  options: { mode: string; label: string; checked: boolean; disabled: boolean }[];
  summary: string;
}

export interface NoiseSectionView {
  hasMode: boolean;
  modes: Choice[];
  confirmedMode: string | null;
  ancLevel: LevelChoice | null;
  transparencyLevel: LevelChoice | null;
  cycle: NoiseCycleView | null;
}

export interface CustomCurveView {
  capabilities: CustomEqualizerCapabilities;
  confirmedCurve: number[] | null;
  displayedCurve: number[];
  isFlat: boolean;
  isDrafting: boolean;
}

export interface EqualizerSectionView {
  hasPresets: boolean;
  presetOptions: Choice[];
  confirmedMode: string | null;
  activeMode: string | null;
  custom: CustomCurveView | null;
  bassBoost: LevelChoice | null;
}

export interface AudioSectionView {
  audioMode: TextChoice | null;
  coupledFootnote: string | null;
  multipoint: boolean | null | undefined;
  gameMode: boolean | null | undefined;
  spatialAudio: boolean | null | undefined;
  spatialMode: TextChoice | null;
  spatialContent: (TextChoice & { blockedReason: string | null }) | null;
  earAdaptive: boolean | null | undefined;
  independentMicActivation: boolean | null | undefined;
  independentMicMode: { value: boolean | null; blockedReason: string | null } | null;
}

export interface BoundSlot {
  side: string;
  gesture: string;
  label: string;
  action: string | null;
}

export interface TouchBindingView {
  title: string;
  sides: Choice[];
  activeSide: string;
  actions: ActionChoice[];
  slots: BoundSlot[];
}

export interface TapView {
  enabled: boolean | null | undefined;
  actions: ActionChoice[];
  slots: BoundSlot[];
}

export interface TouchSectionView {
  touchEnabled: boolean | null | undefined;
  earDetection: boolean | null | undefined;
  touchBinding: TouchBindingView | null;
  tap: TapView | null;
}

export interface PromptSectionView {
  promptLanguage: TextChoice | null;
  promptVolume: LevelChoice | null;
  gain: LevelChoice | null;
  hearingProtection: LevelChoice | null;
}

export interface HeadsetSettingsModel {
  connected: () => boolean;
  profileLabels: () => ProfileLabels;
  noiseSection: () => NoiseSectionView | null;
  equalizerSection: () => EqualizerSectionView | null;
  audioSection: () => AudioSectionView | null;
  touchSection: () => TouchSectionView | null;
  promptAndHearingSection: () => PromptSectionView | null;
  hasAnySection: () => boolean;
  canWrite: (setting: string) => boolean;
  feedback: (...settings: string[]) => SettingFeedback;
  selectTouchSide: (side: string) => void;
  setNoiseMode: (mode: string) => void;
  setAncLevel: (level: number) => void;
  setTransparencyLevel: (level: number) => void;
  toggleNoiseCycleMode: (mode: string, nextChecked: boolean) => void;
  selectEqualizerPreset: (preset: string) => void;
  previewCustomBand: (bandIndex: number, gain: number) => void;
  commitCustomBand: (bandIndex: number, gain: number) => void;
  resetCustomCurve: () => void;
  setBassBoost: (level: number) => void;
  setGameMode: (enabled: boolean) => void;
  setAudioMode: (mode: string) => void;
  setMultipoint: (enabled: boolean) => void;
  setSpatialAudio: (enabled: boolean) => void;
  setSpatialMode: (mode: string) => void;
  setSpatialContent: (content: string) => void;
  setEarAdaptive: (enabled: boolean) => void;
  setIndependentMicActivation: (enabled: boolean) => void;
  setIndependentMicMode: (enabled: boolean) => void;
  setTouchEnabled: (enabled: boolean) => void;
  setEarDetection: (enabled: boolean) => void;
  setTouchBinding: (side: string, gesture: string, action: string) => void;
  setTapEnabled: (enabled: boolean) => void;
  setTapBinding: (side: string, gesture: string, action: string) => void;
  setPromptLanguage: (language: string) => void;
  setPromptVolume: (level: number) => void;
  setGain: (level: number) => void;
  setHearingProtection: (level: number) => void;
  formatBandHz: (hz: number) => string;
  formatGainDb: (gain: number) => string;
  promptVolumeLabel: (level: number) => string;
  gainLevelLabel: (level: number) => string;
  hearingProtectionLabel: (level: number) => string;
}

type EqChoice =
  | { kind: "preset"; preset: string }
  | { kind: "curve"; gains: number[] };

const PRESET_ORDER = ["pop", "hifi", "rock", "light", "eap"] as const;

const WRITE_GROUP: Record<string, string> = {
  AncLevel: "NoiseMode",
  TransparencyLevel: "NoiseMode",
  NoiseCycle: "NoiseMode",
  CustomEq: "Eq",
  TouchBinding: "TouchEnabled",
  TapBinding: "TapEnabled",
  SpatialMode: "SpatialAudio",
  SpatialContent: "SpatialAudio",
  IndependentMicMode: "IndependentMicActivation",
};

function formatBandHz(hz: number): string {
  return hz >= 1000 ? `${(hz / 1000).toFixed(hz % 1000 === 0 ? 0 : 1)}k` : `${hz}`;
}

function formatGainDb(gain: number): string {
  return gain > 0 ? `+${gain} dB` : `${gain} dB`;
}

function gainLevelLabel(level: number): string {
  if (level === 0) return "低";
  if (level === 1) return "中";
  if (level === 2) return "高";
  return `${level} 档`;
}

function promptVolumeLabel(level: number): string {
  return level === 0 ? "关闭" : `${level} 档`;
}

function hearingProtectionLabel(level: number): string {
  if (level === 0) return "关闭";
  const db = OpenRoseSettings.hearingProtectionThresholdDb(level);
  return db !== null ? `${db} dB` : `${level}`;
}

export function touchSideLabel(side: string): string {
  if (side === "left") return "左耳";
  if (side === "right") return "右耳";
  return side;
}

export function touchGestureLabel(gesture: string): string {
  if (gesture === "single") return "单击";
  if (gesture === "double") return "双击";
  if (gesture === "triple") return "三击";
  if (gesture === "long") return "长按";
  return gesture;
}

export function touchSlotLabel(
  slot: TouchSlot,
  touch: TouchControlCapabilities,
  includeSide: boolean,
): string {
  const entry = touch.slot_labels.find(
    (item) => item.slot.side === slot.side && item.slot.gesture === slot.gesture,
  );
  if (entry) return `${entry.label}${touchGestureLabel(slot.gesture)}`;
  if (includeSide) return `${touchSideLabel(slot.side)}${touchGestureLabel(slot.gesture)}`;
  return touchGestureLabel(slot.gesture);
}

export function touchSideTitle(side: string, touch: TouchControlCapabilities): string {
  const entry = touch.slot_labels.find((item) => item.slot.side === side);
  return entry ? entry.label : touchSideLabel(side);
}

function readConfirmedBassBoost(
  fields: Record<string, FieldReading>,
  levels: number[],
): number | null {
  const eq = fields.Eq;
  const slot = fields.EqSlot;
  if (
    !eq ||
    typeof eq !== "object" ||
    !("Known" in eq) ||
    !slot ||
    typeof slot !== "object" ||
    !("Known" in slot)
  ) {
    return null;
  }
  if (!("Text" in eq.Known.value) || eq.Known.value.Text !== "custom_slot") return null;
  if (eq.Known.raw_hex !== slot.Known.raw_hex) return null;
  if (!("Number" in slot.Known.value)) return null;
  return levels.includes(slot.Known.value.Number) ? slot.Known.value.Number : null;
}

function writeGroupOf(setting: string): string {
  return WRITE_GROUP[setting] ?? setting;
}

function sortSlots(slots: TouchSlot[]): TouchSlot[] {
  const sideOrder = (side: string) => (side === "left" ? 0 : side === "right" ? 1 : 2);
  const gestureOrder = (gesture: string) => {
    if (gesture === "single") return 0;
    if (gesture === "double") return 1;
    if (gesture === "triple") return 2;
    if (gesture === "long") return 3;
    return 4;
  };
  return [...slots].sort(
    (a, b) =>
      sideOrder(a.side) - sideOrder(b.side) ||
      gestureOrder(a.gesture) - gestureOrder(b.gesture) ||
      a.gesture.localeCompare(b.gesture),
  );
}

function sortPresets(presets: string[]): string[] {
  return [...presets].sort((a, b) => {
    const ia = PRESET_ORDER.indexOf(a as (typeof PRESET_ORDER)[number]);
    const ib = PRESET_ORDER.indexOf(b as (typeof PRESET_ORDER)[number]);
    const diff = (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
    return diff !== 0 ? diff : a.localeCompare(b);
  });
}

function flatCurve(custom: CustomEqualizerCapabilities): number[] {
  const zero = Math.max(custom.min_gain, Math.min(custom.max_gain, 0));
  return custom.bands_hz.map(() => zero);
}

function curvesEqual(a: number[] | null, b: number[] | null): boolean {
  if (!a || !b || a.length !== b.length) return false;
  return a.every((v, i) => v === b[i]);
}

export function createHeadsetSettingsModel(controller: HeadsetController): HeadsetSettingsModel {
  const inFlight = signal<Record<string, boolean>>({});
  const localErrors = signal<Record<string, ControlError>>({});
  const eqDraftCurve = signal<number[] | null>(null);
  const eqPending = signal<EqChoice | null>(null);
  const selectedTouchSide = signal<string | null>(null);
  let queuedEq: EqChoice | null = null;
  let eqWorkerRunning = false;

  const profileLabelsCache = new Map<string, ProfileLabels>();

  const headset = computed(() => controller.snapshot().headset);
  const connected = computed(() => controller.connectionStatus().kind === "connected");
  const sessionId = computed(() => headset()?.session_id ?? null);

  effect(() => {
    sessionId();
    inFlight.set({});
    localErrors.set({});
    eqDraftCurve.set(null);
    eqPending.set(null);
    selectedTouchSide.set(null);
    queuedEq = null;
  });

  const profileLabels = computed<ProfileLabels>(() =>
    profileLabelsOf(headset()?.readings.profile ?? controller.currentModel()?.id ?? null),
  );

  const capabilities = computed(() => headset()?.readings.capabilities ?? null);
  const fields = computed(() => headset()?.readings.fields ?? {});
  const writableSet = computed(() => new Set(capabilities()?.writable_settings ?? []));

  const activeOperations = computed<SettingOperation[]>(() => {
    const snap: ControlSnapshot = controller.snapshot();
    const sid = sessionId();
    if (sid === null) return [];
    return snap.setting_operations.filter((op) => op.session_id === sid);
  });

  function isGroupBusy(setting: string): boolean {
    const group = writeGroupOf(setting);
    const pendingInSnapshot = activeOperations().some(
      (op) => isPendingStage(op.stage) && writeGroupOf(op.setting) === group,
    );
    if (pendingInSnapshot) return true;
    return Object.entries(inFlight()).some(
      ([name, busy]) => busy && writeGroupOf(name) === group,
    );
  }

  const prerequisites = computed(() => unmetPrerequisites(fields()));

  function prerequisiteText(setting: string): string | null {
    const category = prerequisites()[setting];
    return category ? prerequisiteLabel(category) : null;
  }

  function canWrite(setting: string): boolean {
    if (!connected() || !writableSet().has(setting)) return false;
    if (prerequisites()[setting]) return false;
    return !isGroupBusy(setting);
  }

  function feedbackFor(settings: string[]): SettingFeedback {
    const set = new Set(settings);
    const ops = activeOperations().filter((op) => set.has(op.setting));
    const latest = ops.length > 0 ? ops[ops.length - 1] : null;
    const localInFlight = settings.find((s) => inFlight()[s]);

    if (latest) {
      const label = settingLabel(latest.setting);
      if (isPendingStage(latest.stage)) {
        return { kind: "in_progress", setting: latest.setting, label, stage: latest.stage };
      }
      if (localInFlight) {
        return {
          kind: "in_progress",
          setting: localInFlight,
          label: settingLabel(localInFlight),
          stage: "Submitted",
        };
      }
      if (isFailedStage(latest.stage)) {
        return {
          kind: "failed",
          setting: latest.setting,
          label,
          code: latest.error?.code ?? latest.stage,
          reason: errorCategoryLabel(latest.error?.code ?? latest.stage),
          message: latest.error?.message ?? "",
        };
      }
      if (latest.stage === "Confirmed") {
        const localErrEntry = settings.map((s) => [s, localErrors()[s]] as const).find(([, e]) => Boolean(e));
        if (localErrEntry?.[1]) {
          return {
            kind: "failed",
            setting: localErrEntry[0],
            label: settingLabel(localErrEntry[0]),
            code: localErrEntry[1].code,
            reason: errorCategoryLabel(localErrEntry[1].code),
            message: localErrEntry[1].message,
          };
        }
        return { kind: "confirmed", setting: latest.setting, label };
      }
    }

    if (localInFlight) {
      return {
        kind: "in_progress",
        setting: localInFlight,
        label: settingLabel(localInFlight),
        stage: "Submitted",
      };
    }

    for (const s of settings) {
      const err = localErrors()[s];
      if (err) {
        return {
          kind: "failed",
          setting: s,
          label: settingLabel(s),
          code: err.code,
          reason: errorCategoryLabel(err.code),
          message: err.message,
        };
      }
    }

    return { kind: "idle" };
  }

  async function trackWrite(setting: string, write: () => Promise<void>) {
    inFlight.set({ ...inFlight(), [setting]: true });
    const nextErrors = { ...localErrors() };
    delete nextErrors[setting];
    localErrors.set(nextErrors);
    try {
      await write();
    } catch (error) {
      localErrors.set({ ...localErrors(), [setting]: controlErrorOf(error) });
    } finally {
      const updated = { ...inFlight() };
      delete updated[setting];
      inFlight.set(updated);
    }
  }

  function submitCommand(setting: string, commandPayload: Record<string, unknown>) {
    const expectedSession = sessionId();
    return trackWrite(setting, () =>
      controller.executeCommand(commandPayload, expectedSession),
    );
  }

  async function runEqQueue() {
    if (eqWorkerRunning) return;
    eqWorkerRunning = true;
    try {
      while (queuedEq) {
        const next = queuedEq;
        queuedEq = null;
        eqPending.set(next);
        if (next.kind === "preset") {
          await submitCommand("Eq", { EqualizerPreset: next.preset });
        } else {
          await submitCommand("CustomEq", { CustomEqualizer: next.gains });
        }
      }
    } finally {
      eqPending.set(null);
      eqWorkerRunning = false;
    }
  }

  function queueEqualizerChoice(choice: EqChoice) {
    if (!connected()) return;
    queuedEq = choice;
    eqPending.set(choice);
    eqDraftCurve.set(null);
    void runEqQueue();
  }

  const confirmedNoiseMode = computed(() => readKnownText(fields(), "NoiseMode"));

  const noiseSection = computed<NoiseSectionView | null>(() => {
    const caps = capabilities();
    const noise = caps?.noise_control ?? null;
    if (!caps || !noise) return null;
    const writable = writableSet();
    const currentMode = confirmedNoiseMode();
    const hasMode = writable.has("NoiseMode") && noise.modes.length > 0;
    const levels = visibleLevels(caps, currentMode);
    const ancLevel = levels.find((level) => level.setting === "AncLevel") ?? null;
    const transLevel = levels.find((level) => level.setting === "TransparencyLevel") ?? null;
    const showCycle = writable.has("NoiseCycle") && noise.cycle_modes.length > 0;
    if (!hasMode && !ancLevel && !transLevel && !showCycle) return null;

    const cycleFlags = readKnownNoiseCycle(fields());
    const cycleModes = sortNoiseModes(noise.cycle_modes);
    const cycleKnown =
      cycleFlags !== null && cycleModes.every((mode) => typeof cycleFlags[mode] === "boolean");
    const checkedCycle = cycleModes.filter((mode) => cycleFlags?.[mode] === true);
    const atMinimumCycle = checkedCycle.length <= noise.minimum_cycle_selections;

    return {
      hasMode,
      modes: sortNoiseModes(noise.modes).map((mode) => ({
        value: mode,
        label: noiseModeLabel(mode),
      })),
      confirmedMode: currentMode,
      ancLevel: ancLevel
        ? {
            levels: ancLevel.levels,
            confirmed: readKnownNumber(fields(), "AncLevel"),
          }
        : null,
      transparencyLevel: transLevel
        ? {
            levels: transLevel.levels,
            confirmed: readKnownNumber(fields(), "TransparencyLevel"),
          }
        : null,
      cycle: showCycle
        ? {
            known: cycleKnown,
            minimum: noise.minimum_cycle_selections,
            atMinimum: atMinimumCycle,
            options: cycleModes.map((mode) => {
              const checked = cycleFlags?.[mode] === true;
              return {
                mode,
                label: noiseModeLabel(mode),
                checked,
                disabled: !canWrite("NoiseCycle") || !cycleKnown || (checked && atMinimumCycle),
              };
            }),
            summary: !cycleKnown
              ? "尚未读取完整状态"
              : checkedCycle.length === 0
                ? "未选择轮换模式"
                : checkedCycle.map(noiseModeLabel).join("、"),
          }
        : null,
    };
  });

  const equalizerSection = computed<EqualizerSectionView | null>(() => {
    const caps = capabilities();
    const eq = caps?.equalizer ?? null;
    const writable = writableSet();
    const bassLevels = [...(caps?.bass_boost_levels ?? [])].sort((a, b) => a - b);
    const hasBassBoost = writable.has("BassBoost") && bassLevels.length > 0;
    const hasPresets = caps !== null && hasEqualizerPresets(caps);
    const customCap =
      writable.has("CustomEq") && eq?.custom && eq.custom.min_gain <= eq.custom.max_gain
        ? eq.custom
        : null;

    if (!caps || (!hasPresets && !customCap && !hasBassBoost)) return null;

    const rawPresets = sortPresets(
      (eq?.presets ?? []).filter((preset) => !(customCap && preset === "custom")),
    );
    const profileId = headset()?.readings.profile ?? null;
    const presetOptions = rawPresets.map((preset) => ({
      value: preset,
      label: equalizerPresetLabel(profileId, preset),
    }));
    if (customCap) {
      presetOptions.push({ value: "custom", label: "自定义" });
    }

    const confirmedMode = readKnownText(fields(), "Eq");
    const confirmedCurveRaw = customCap ? readKnownIntegerList(fields(), "CustomEq") : null;
    const confirmedCurve =
      customCap &&
      confirmedCurveRaw &&
      confirmedCurveRaw.length === customCap.bands_hz.length &&
      confirmedCurveRaw.every((g) => g >= customCap.min_gain && g <= customCap.max_gain)
        ? confirmedCurveRaw
        : null;

    const pendingChoice = eqPending();
    const pendingMode =
      pendingChoice?.kind === "preset"
        ? pendingChoice.preset
        : pendingChoice?.kind === "curve"
          ? "custom"
          : null;
    const pendingCurve = pendingChoice?.kind === "curve" ? pendingChoice.gains : null;
    const activeCurve = customCap
      ? eqDraftCurve() ?? pendingCurve ?? confirmedCurve ?? flatCurve(customCap)
      : null;

    const showCustomEditor =
      customCap !== null &&
      (rawPresets.length === 0 || confirmedMode === "custom" || pendingMode === "custom");

    return {
      hasPresets: presetOptions.length > 0 && (hasPresets || customCap !== null),
      presetOptions,
      confirmedMode,
      activeMode: pendingMode ?? confirmedMode,
      custom: showCustomEditor && customCap && activeCurve
        ? {
            capabilities: customCap,
            confirmedCurve,
            displayedCurve: activeCurve,
            isFlat: curvesEqual(activeCurve, flatCurve(customCap)),
            isDrafting: eqDraftCurve() !== null,
          }
        : null,
      bassBoost: hasBassBoost
        ? {
            levels: bassLevels,
            confirmed: readConfirmedBassBoost(fields(), bassLevels),
          }
        : null,
    };
  });

  const audioSection = computed<AudioSectionView | null>(() => {
    const caps = capabilities();
    if (!caps) return null;
    const writable = writableSet();
    const audioCap = caps.audio_control;
    const hasAudioMode = writable.has("AudioMode") && Boolean(audioCap && audioCap.modes.length > 0);
    const hasMultipoint = writable.has("Multipoint");
    const hasGameMode = writable.has("GameMode");
    const hasSpatialAudio = writable.has("SpatialAudio");
    const hasSpatialMode = writable.has("SpatialMode") && caps.spatial_modes.length > 0;
    const spatialModeValue = readKnownText(fields(), "SpatialMode");
    const hasSpatialContent = writable.has("SpatialContent") && caps.spatial_contents.length > 0;
    const hasEarAdaptive = writable.has("EarAdaptive");
    const hasMicActivation = writable.has("IndependentMicActivation");
    const hasMicMode = writable.has("IndependentMicMode");

    if (
      !hasAudioMode &&
      !hasMultipoint &&
      !hasGameMode &&
      !hasSpatialAudio &&
      !hasSpatialMode &&
      !hasSpatialContent &&
      !hasEarAdaptive &&
      !hasMicActivation &&
      !hasMicMode
    ) {
      return null;
    }

    const coupledFootnote =
      hasAudioMode && hasMultipoint && audioCap?.multipoint_coupled
        ? "该型号的 LDAC 与多点连接互斥；切换多点时设备会改用 AAC / SBC。"
        : null;

    return {
      audioMode: hasAudioMode
        ? {
            options: [...audioCap!.modes]
              .sort((a, b) => semanticLabel(a).localeCompare(semanticLabel(b)))
              .map((mode) => ({ value: mode, label: semanticLabel(mode) })),
            confirmed: readKnownText(fields(), "AudioMode"),
          }
        : null,
      coupledFootnote,
      multipoint: hasMultipoint ? readKnownToggle(fields(), "Multipoint") : undefined,
      gameMode: hasGameMode ? readKnownToggle(fields(), "GameMode") : undefined,
      spatialAudio: hasSpatialAudio ? readKnownToggle(fields(), "SpatialAudio") : undefined,
      spatialMode: hasSpatialMode
        ? {
            options: caps.spatial_modes.map((mode) => ({
              value: mode,
              label: semanticLabel(mode),
            })),
            confirmed: spatialModeValue,
          }
        : null,
      spatialContent: hasSpatialContent
        ? {
            options: caps.spatial_contents.map((content) => ({
              value: content,
              label: semanticLabel(content),
            })),
            confirmed: readKnownText(fields(), "SpatialContent"),
            blockedReason: prerequisiteText("SpatialContent"),
          }
        : null,
      earAdaptive: hasEarAdaptive ? readKnownToggle(fields(), "EarAdaptive") : undefined,
      independentMicActivation: hasMicActivation
        ? readKnownToggle(fields(), "IndependentMicActivation")
        : undefined,
      independentMicMode: hasMicMode
        ? {
            value: readKnownToggle(fields(), "IndependentMicMode"),
            blockedReason: prerequisiteText("IndependentMicMode"),
          }
        : null,
    };
  });

  const touchSection = computed<TouchSectionView | null>(() => {
    const caps = capabilities();
    if (!caps) return null;
    const writable = writableSet();
    const hasTouchEnabled = writable.has("TouchEnabled");
    const hasEarDetection = writable.has("EarDetection");
    const touchCap =
      writable.has("TouchBinding") &&
      caps.touch_control &&
      caps.touch_control.slots.length > 0 &&
      caps.touch_control.actions.length > 0
        ? caps.touch_control
        : null;
    const hasTapEnabled = writable.has("TapEnabled");
    const tapCap =
      writable.has("TapBinding") &&
      caps.tap_control &&
      caps.tap_control.slots.length > 0 &&
      caps.tap_control.actions.length > 0
        ? caps.tap_control
        : null;

    if (!hasTouchEnabled && !hasEarDetection && !touchCap && !hasTapEnabled && !tapCap) {
      return null;
    }

    const touchSlots = touchCap ? sortSlots(touchCap.slots) : [];
    const availableSides = Array.from(new Set(touchSlots.map((slot) => slot.side)));
    const activeSide =
      selectedTouchSide() && availableSides.includes(selectedTouchSide()!)
        ? selectedTouchSide()!
        : availableSides[0] ?? null;

    const touchBindings = readKnownTouchKeys(fields(), "TouchKeys") ?? [];
    const touchActions = touchCap
      ? [...touchCap.actions]
          .sort((a, b) => semanticLabel(a).localeCompare(semanticLabel(b)))
          .map((action) => ({ value: action, title: semanticLabel(action) }))
      : [];

    const tapSlots = tapCap ? sortSlots(tapCap.slots) : [];
    const tapBindings = readKnownTouchKeys(fields(), "TapKeys") ?? [];
    const tapActions = tapCap
      ? [...tapCap.actions]
          .sort((a, b) => semanticLabel(a).localeCompare(semanticLabel(b)))
          .map((action) => ({ value: action, title: semanticLabel(action) }))
      : [];

    return {
      touchEnabled: hasTouchEnabled ? readKnownToggle(fields(), "TouchEnabled") : undefined,
      earDetection: hasEarDetection ? readKnownToggle(fields(), "EarDetection") : undefined,
      touchBinding: touchCap && activeSide
        ? {
            title: touchCap.slot_labels.length === 0 ? "左右耳手势" : "按键手势",
            sides: availableSides.map((side) => ({
              value: side,
              label: touchSideTitle(side, touchCap),
            })),
            activeSide,
            actions: touchActions,
            slots: touchSlots
              .filter((slot) => slot.side === activeSide)
              .map((slot) => {
                const binding = touchBindings.find(
                  (b) => b.side === slot.side && b.gesture === slot.gesture,
                );
                return {
                  side: slot.side,
                  gesture: slot.gesture,
                  label: touchSlotLabel(slot, touchCap, false),
                  action: binding?.action ?? null,
                };
              }),
          }
        : null,
      tap: hasTapEnabled || tapCap
        ? {
            enabled: hasTapEnabled ? readKnownToggle(fields(), "TapEnabled") : undefined,
            actions: tapActions,
            slots: tapCap
              ? tapSlots.map((slot) => {
                  const binding = tapBindings.find(
                    (b) => b.side === slot.side && b.gesture === slot.gesture,
                  );
                  return {
                    side: slot.side,
                    gesture: slot.gesture,
                    label: touchSlotLabel(slot, tapCap, true),
                    action: binding?.action ?? null,
                  };
                })
              : [],
          }
        : null,
    };
  });

  const promptAndHearingSection = computed<PromptSectionView | null>(() => {
    const caps = capabilities();
    if (!caps) return null;
    const writable = writableSet();
    const volumeLevels = [...caps.prompt_volume_levels].sort((a, b) => a - b);
    const hasPromptVolume = writable.has("PromptVolume") && volumeLevels.length > 0;
    const languages = [...caps.prompt_languages].sort();
    const hasPromptLanguage = writable.has("PromptLanguage") && languages.length > 0;
    const gainLevels = [...caps.gain_levels].sort((a, b) => a - b);
    const hasGain = writable.has("Gain") && gainLevels.length > 0;
    const protectionLevels = [...caps.hearing_protection_levels].sort((a, b) => a - b);
    const hasProtection = writable.has("HearingProtection") && protectionLevels.length > 0;

    if (!hasPromptVolume && !hasPromptLanguage && !hasGain && !hasProtection) return null;

    return {
      promptLanguage: hasPromptLanguage
        ? {
            options: languages.map((lang) => ({ value: lang, label: semanticLabel(lang) })),
            confirmed: readKnownText(fields(), "PromptLanguage"),
          }
        : null,
      promptVolume: hasPromptVolume
        ? {
            levels: volumeLevels,
            confirmed: readKnownNumber(fields(), "PromptVolume"),
          }
        : null,
      gain: hasGain
        ? {
            levels: gainLevels,
            confirmed: readKnownNumber(fields(), "Gain"),
          }
        : null,
      hearingProtection: hasProtection
        ? {
            levels: protectionLevels,
            confirmed: readKnownNumber(fields(), "HearingProtection"),
          }
        : null,
    };
  });

  const hasAnySection = computed(() =>
    Boolean(
      noiseSection() ||
        equalizerSection() ||
        audioSection() ||
        touchSection() ||
        promptAndHearingSection(),
    ),
  );

  return {
    connected,
    profileLabels,
    formatBandHz,
    formatGainDb,
    promptVolumeLabel,
    gainLevelLabel,
    hearingProtectionLabel,
    noiseSection,
    equalizerSection,
    audioSection,
    touchSection,
    promptAndHearingSection,
    hasAnySection,
    canWrite,
    feedback: (...settings: string[]) => feedbackFor(settings),
    selectTouchSide: (side: string) => selectedTouchSide.set(side),
    setNoiseMode: (mode: string) =>
      void trackWrite("NoiseMode", () => controller.setNoiseMode(mode)),
    setAncLevel: (level: number) => void submitCommand("AncLevel", { AncLevel: level }),
    setTransparencyLevel: (level: number) =>
      void submitCommand("TransparencyLevel", { TransparencyLevel: level }),
    toggleNoiseCycleMode: (mode: string, nextChecked: boolean) => {
      const noise = capabilities()?.noise_control;
      if (!noise) return;
      const flags = readKnownNoiseCycle(fields()) ?? {};
      const payload: Record<string, boolean> = {};
      for (const m of noise.cycle_modes) {
        payload[m] = m === mode ? nextChecked : flags[m] === true;
      }
      void submitCommand("NoiseCycle", { NoiseCycle: payload });
    },
    selectEqualizerPreset: (preset: string) => {
      if (preset === "custom") {
        const custom = capabilities()?.equalizer?.custom;
        if (!custom) return;
        const rawCurve = readKnownIntegerList(fields(), "CustomEq");
        const confirmed =
          rawCurve &&
          rawCurve.length === custom.bands_hz.length &&
          rawCurve.every((g) => g >= custom.min_gain && g <= custom.max_gain)
            ? rawCurve
            : flatCurve(custom);
        queueEqualizerChoice({ kind: "curve", gains: confirmed });
      } else {
        queueEqualizerChoice({ kind: "preset", preset });
      }
    },
    previewCustomBand: (bandIndex: number, gain: number) => {
      const eq = equalizerSection()?.custom;
      if (!eq) return;
      const clamped = Math.max(eq.capabilities.min_gain, Math.min(eq.capabilities.max_gain, gain));
      const base = [...eq.displayedCurve];
      base[bandIndex] = clamped;
      eqDraftCurve.set(base);
    },
    commitCustomBand: (bandIndex: number, gain: number) => {
      const eq = equalizerSection()?.custom;
      if (!eq) return;
      const clamped = Math.max(eq.capabilities.min_gain, Math.min(eq.capabilities.max_gain, gain));
      const base = [...eq.displayedCurve];
      base[bandIndex] = clamped;
      queueEqualizerChoice({ kind: "curve", gains: base });
    },
    resetCustomCurve: () => {
      const custom = capabilities()?.equalizer?.custom;
      if (!custom) return;
      queueEqualizerChoice({ kind: "curve", gains: flatCurve(custom) });
    },
    setBassBoost: (level: number) => void submitCommand("BassBoost", { BassBoost: level }),
    setGameMode: (enabled: boolean) => void submitCommand("GameMode", { GameMode: enabled }),
    setAudioMode: (mode: string) => void submitCommand("AudioMode", { AudioMode: mode }),
    setMultipoint: (enabled: boolean) => void submitCommand("Multipoint", { Multipoint: enabled }),
    setSpatialAudio: (enabled: boolean) =>
      void submitCommand("SpatialAudio", { SpatialAudio: enabled }),
    setSpatialMode: (mode: string) => void submitCommand("SpatialMode", { SpatialMode: mode }),
    setSpatialContent: (content: string) =>
      void submitCommand("SpatialContent", { SpatialContent: content }),
    setEarAdaptive: (enabled: boolean) =>
      void submitCommand("EarAdaptive", { EarAdaptive: enabled }),
    setIndependentMicActivation: (enabled: boolean) =>
      void submitCommand("IndependentMicActivation", { IndependentMicActivation: enabled }),
    setIndependentMicMode: (enabled: boolean) =>
      void submitCommand("IndependentMicMode", { IndependentMicMode: enabled }),
    setTouchEnabled: (enabled: boolean) =>
      void submitCommand("TouchEnabled", { TouchEnabled: enabled }),
    setEarDetection: (enabled: boolean) =>
      void submitCommand("EarDetection", { EarDetection: enabled }),
    setTouchBinding: (side: string, gesture: string, action: string) =>
      void submitCommand("TouchBinding", { TouchBinding: { side, gesture, action } }),
    setTapEnabled: (enabled: boolean) => void submitCommand("TapEnabled", { TapEnabled: enabled }),
    setTapBinding: (side: string, gesture: string, action: string) =>
      void submitCommand("TapBinding", { TapBinding: { side, gesture, action } }),
    setPromptLanguage: (language: string) =>
      void submitCommand("PromptLanguage", { PromptLanguage: language }),
    setPromptVolume: (level: number) => void submitCommand("PromptVolume", { PromptVolume: level }),
    setGain: (level: number) => void submitCommand("Gain", { Gain: level }),
    setHearingProtection: (level: number) =>
      void submitCommand("HearingProtection", { HearingProtection: level }),
  };
}
