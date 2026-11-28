import {
  gainLevelLabel,
  hearingProtectionLabel,
  promptVolumeLabel,
} from "../../../model/settings";
import type { HeadsetSettingsModel } from "../../../model/settings";
import { Card, SectionTitle, SelectField } from "../../../ui";
import { ChoiceTabs, SettingGroup, levelOptions } from "./shared";

export function PromptSection(props: { model: HeadsetSettingsModel }) {
  const model = props.model;
  const prompt = () => model.promptAndHearingSection()!;

  return (
    <Card>
      <SectionTitle>提示音与保护</SectionTitle>

      {prompt().promptLanguage ? (
        <ChoiceTabs
          label="提示音语言"
          options={prompt().promptLanguage!.options.map((option) => ({
            value: option.value,
            label: option.label,
            testID: `setting-prompt-language-${option.value}`,
          }))}
          confirmed={prompt().promptLanguage!.confirmed}
          disabled={!model.canWrite("PromptLanguage")}
          feedback={model.feedback("PromptLanguage")}
          onSelect={model.setPromptLanguage}
        />
      ) : null}

      {prompt().promptVolume ? (
        <ChoiceTabs
          label="提示音音量"
          options={levelOptions("setting-prompt-volume", prompt().promptVolume!.levels, promptVolumeLabel)}
          confirmed={
            prompt().promptVolume!.confirmed !== null ? String(prompt().promptVolume!.confirmed) : null
          }
          disabled={!model.canWrite("PromptVolume")}
          feedback={model.feedback("PromptVolume")}
          onSelect={(value) => model.setPromptVolume(Number(value))}
        />
      ) : null}

      {prompt().gain ? (
        <ChoiceTabs
          label="增益档位"
          options={levelOptions("setting-gain", prompt().gain!.levels, gainLevelLabel)}
          confirmed={prompt().gain!.confirmed !== null ? String(prompt().gain!.confirmed) : null}
          disabled={!model.canWrite("Gain")}
          feedback={model.feedback("Gain")}
          onSelect={(value) => model.setGain(Number(value))}
        />
      ) : null}

      {prompt().hearingProtection ? (
        <SettingGroup label="听力保护阈值" feedback={model.feedback("HearingProtection")}>
          <SelectField
            testID="setting-hearing-protection"
            label="听力保护阈值"
            items={prompt().hearingProtection!.levels.map((level) => ({
              value: String(level),
              title: hearingProtectionLabel(level),
            }))}
            value={
              prompt().hearingProtection!.confirmed !== null
                ? String(prompt().hearingProtection!.confirmed)
                : null
            }
            placeholder="当前状态未知"
            disabled={!model.canWrite("HearingProtection")}
            onChange={(value) => {
              if (value !== null) model.setHearingProtection(Number(value));
            }}
          />
        </SettingGroup>
      ) : null}
    </Card>
  );
}
