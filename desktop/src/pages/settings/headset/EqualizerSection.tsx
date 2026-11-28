import { formatBandHz, formatGainDb } from "../../../model/settings";
import type { HeadsetSettingsModel } from "../../../model/settings";
import {
  ActionButton,
  Card,
  Column,
  Divider,
  MutedText,
  RadioGroupField,
  Row,
  SectionTitle,
  SliderField,
  StatusBadge,
} from "../../../ui";
import { ChoiceTabs, SettingGroup, levelOptions } from "./shared";

export function EqualizerSection(props: { model: HeadsetSettingsModel }) {
  const model = props.model;
  const eq = () => model.equalizerSection()!;
  const confirmedLabel = () =>
    eq().presetOptions.find((preset) => preset.value === eq().confirmedMode)?.label ??
    eq().confirmedMode;

  return (
    <Card>
      <Row class="items-center justify-between gap-2">
        <SectionTitle>均衡器与音效</SectionTitle>
        {confirmedLabel() ? (
          <StatusBadge testID="settings-eq-badge" tone="accent" label={confirmedLabel()!} />
        ) : null}
      </Row>

      {eq().hasPresets ? (
        <SettingGroup label="均衡器模式" feedback={model.feedback("Eq", "CustomEq")}>
          <RadioGroupField
            accessibilityLabel="均衡器模式"
            options={eq().presetOptions.map((preset) => ({
              value: preset.value,
              label: preset.label,
              testID: `setting-eq-preset-${preset.value}`,
            }))}
            value={eq().activeMode}
            disabled={!model.connected()}
            onChange={model.selectEqualizerPreset}
          />
        </SettingGroup>
      ) : null}

      {eq().custom ? (
        <Column class="gap-2.5">
          <Divider />
          <Row class="items-center justify-between gap-2">
            <Column class="gap-0.5">
              <MutedText>自定义曲线（松手即写入，以耳机确认为准）</MutedText>
              {eq().custom!.confirmedCurve === null ? (
                <MutedText>尚未读取耳机上的曲线，以下为平直曲线</MutedText>
              ) : null}
            </Column>
            <ActionButton
              testID="btn-reset-custom-eq"
              label="重置"
              variant="secondary"
              disabled={!model.connected() || eq().custom!.isFlat}
              onPress={model.resetCustomCurve}
            />
          </Row>
          <Column class="gap-2">
            <Each
              of={eq().custom!.capabilities.bands_hz.map((hz, index) => ({ hz, index }))}
              key={(band) => band.index}
            >
              {(band) => (
                <SliderField
                  testID={`setting-eq-band-${band().index}`}
                  label={`${formatBandHz(band().hz)} Hz`}
                  valueText={formatGainDb(eq().custom!.displayedCurve[band().index] ?? 0)}
                  value={eq().custom!.displayedCurve[band().index] ?? 0}
                  min={eq().custom!.capabilities.min_gain}
                  max={eq().custom!.capabilities.max_gain}
                  step={1}
                  disabled={!model.connected()}
                  onChange={(value) => model.previewCustomBand(band().index, value)}
                  onChangeEnd={(value) => model.commitCustomBand(band().index, value)}
                />
              )}
            </Each>
          </Column>
        </Column>
      ) : null}

      {eq().bassBoost ? (
        <ChoiceTabs
          label="低音增强"
          value={eq().bassBoost!.confirmed !== null ? `${eq().bassBoost!.confirmed} 档` : null}
          options={levelOptions("setting-bass-boost", eq().bassBoost!.levels, (level) => `${level} 档`)}
          confirmed={eq().bassBoost!.confirmed !== null ? String(eq().bassBoost!.confirmed) : null}
          disabled={!model.canWrite("BassBoost")}
          feedback={model.feedback("BassBoost")}
          onSelect={(value) => model.setBassBoost(Number(value))}
        />
      ) : null}
    </Card>
  );
}
