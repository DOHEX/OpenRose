import type { HeadsetSettingsModel } from "../../../ui";
import { Card, NoticeBanner, SectionTitle } from "../../../ui";
import { ChoiceTabs, ToggleSetting } from "./shared";

export function AudioSection(props: { model: HeadsetSettingsModel }) {
  const model = props.model;
  const audio = () => model.audioSection()!;

  return (
    <Card>
      <SectionTitle>音频与空间</SectionTitle>

      {audio().audioMode ? (
        <ChoiceTabs
          label="音频模式"
          options={audio().audioMode!.options.map((option) => ({
            value: option.value,
            label: option.label,
            testID: `setting-audio-mode-${option.value}`,
          }))}
          confirmed={audio().audioMode!.confirmed}
          disabled={!model.canWrite("AudioMode")}
          feedback={model.feedback("AudioMode")}
          onSelect={model.setAudioMode}
        />
      ) : null}

      {audio().multipoint !== undefined ? (
        <ToggleSetting
          testID="setting-multipoint"
          label="多点连接"
          checked={audio().multipoint ?? null}
          disabled={!model.canWrite("Multipoint")}
          feedback={model.feedback("Multipoint")}
          onChange={model.setMultipoint}
        />
      ) : null}

      {audio().coupledFootnote ? (
        <NoticeBanner
          testID="setting-multipoint-coupled-note"
          tone="neutral"
          title={audio().coupledFootnote!}
        />
      ) : null}

      {audio().gameMode !== undefined ? (
        <ToggleSetting
          testID="setting-game-mode"
          label="游戏模式"
          checked={audio().gameMode ?? null}
          disabled={!model.canWrite("GameMode")}
          feedback={model.feedback("GameMode")}
          onChange={model.setGameMode}
        />
      ) : null}

      {audio().spatialAudio !== undefined ? (
        <ToggleSetting
          testID="setting-spatial-audio"
          label="空间音频"
          checked={audio().spatialAudio ?? null}
          disabled={!model.canWrite("SpatialAudio")}
          feedback={model.feedback("SpatialAudio")}
          onChange={model.setSpatialAudio}
        />
      ) : null}

      {audio().spatialMode ? (
        <ChoiceTabs
          label="空间模式"
          options={audio().spatialMode!.options.map((option) => ({
            value: option.value,
            label: option.label,
            testID: `setting-spatial-mode-${option.value}`,
          }))}
          confirmed={audio().spatialMode!.confirmed}
          disabled={!model.canWrite("SpatialMode")}
          feedback={model.feedback("SpatialMode")}
          onSelect={model.setSpatialMode}
        />
      ) : null}

      {audio().spatialContent ? (
        <ChoiceTabs
          label="空间音频内容"
          value={audio().spatialContent!.blockedReason}
          options={audio().spatialContent!.options.map((option) => ({
            value: option.value,
            label: option.label,
            testID: `setting-spatial-content-${option.value}`,
          }))}
          confirmed={audio().spatialContent!.confirmed}
          disabled={!model.canWrite("SpatialContent")}
          feedback={model.feedback("SpatialContent")}
          onSelect={model.setSpatialContent}
        />
      ) : null}

      {audio().earAdaptive !== undefined ? (
        <ToggleSetting
          testID="setting-ear-adaptive"
          label="耳道自适应"
          checked={audio().earAdaptive ?? null}
          disabled={!model.canWrite("EarAdaptive")}
          feedback={model.feedback("EarAdaptive")}
          onChange={model.setEarAdaptive}
        />
      ) : null}

      {audio().independentMicActivation !== undefined ? (
        <ToggleSetting
          testID="setting-independent-mic-activation"
          label="激活独立麦克风"
          checked={audio().independentMicActivation ?? null}
          disabled={!model.canWrite("IndependentMicActivation")}
          feedback={model.feedback("IndependentMicActivation")}
          onChange={model.setIndependentMicActivation}
        />
      ) : null}

      {audio().independentMicMode ? (
        <ToggleSetting
          testID="setting-independent-mic-mode"
          label="独立麦克风模式"
          checked={audio().independentMicMode!.value}
          blockedReason={audio().independentMicMode!.blockedReason}
          disabled={!model.canWrite("IndependentMicMode")}
          feedback={model.feedback("IndependentMicMode")}
          onChange={model.setIndependentMicMode}
        />
      ) : null}
    </Card>
  );
}
