import type { HeadsetSettingsModel } from "../../../model/settings";
import { Column, NoticeBanner, StatusBadge } from "../../../ui";
import { AudioSection } from "./AudioSection";
import { EqualizerSection } from "./EqualizerSection";
import { NoiseSection } from "./NoiseSection";
import { PromptSection } from "./PromptSection";
import { TouchSection } from "./TouchSection";

export function HeadsetSettingsPage(props: { model: HeadsetSettingsModel }) {
  const model = props.model;

  return (
    <Column class="flex-1 gap-4">
      {!model.connected() ? (
        <NoticeBanner
          testID="settings-disconnected"
          tone="neutral"
          title="连接耳机后将按型号能力显示可配置设置"
        />
      ) : null}
      {model.connected() && !model.hasAnySection() ? (
        <NoticeBanner testID="settings-empty" tone="neutral" title="当前型号暂无可写设置项" />
      ) : null}
      {model.connected() && model.profileLabels().versionName ? (
        <StatusBadge
          testID="settings-version-name"
          tone="neutral"
          label={`版本：${model.profileLabels().versionName}`}
        />
      ) : null}
      {model.noiseSection() ? <NoiseSection model={model} /> : null}
      {model.equalizerSection() ? <EqualizerSection model={model} /> : null}
      {model.audioSection() ? <AudioSection model={model} /> : null}
      {model.touchSection() ? <TouchSection model={model} /> : null}
      {model.promptAndHearingSection() ? <PromptSection model={model} /> : null}
    </Column>
  );
}
