import type { HeadsetSettingsModel } from "../../../model/settings";
import { Card, CheckboxRow, Column, Row, SectionTitle, StatusBadge } from "../../../ui";
import { ChoiceTabs, SettingGroup, levelOptions } from "./shared";

export function NoiseSection(props: { model: HeadsetSettingsModel }) {
  const model = props.model;
  const noise = () => model.noiseSection()!;
  const confirmedLabel = () =>
    noise().modes.find((mode) => mode.value === noise().confirmedMode)?.label ??
    noise().confirmedMode;

  return (
    <Card>
      <Row class="items-center justify-between gap-2">
        <SectionTitle>降噪与通透</SectionTitle>
        {confirmedLabel() ? (
          <StatusBadge testID="settings-noise-badge" tone="accent" label={`当前：${confirmedLabel()}`} />
        ) : null}
      </Row>

      {noise().hasMode ? (
        <ChoiceTabs
          label="降噪模式"
          options={noise().modes.map((mode) => ({
            value: mode.value,
            label: mode.label,
            testID: `setting-noise-mode-${mode.value}`,
          }))}
          confirmed={noise().confirmedMode}
          disabled={!model.canWrite("NoiseMode")}
          feedback={model.feedback("NoiseMode")}
          onSelect={model.setNoiseMode}
        />
      ) : null}

      {noise().ancLevel ? (
        <ChoiceTabs
          label="降噪档位"
          value={noise().ancLevel!.confirmed !== null ? `${noise().ancLevel!.confirmed} 档` : null}
          options={levelOptions("setting-anc-level", noise().ancLevel!.levels, (level) => `${level} 档`)}
          confirmed={noise().ancLevel!.confirmed !== null ? String(noise().ancLevel!.confirmed) : null}
          disabled={!model.canWrite("AncLevel")}
          feedback={model.feedback("AncLevel")}
          onSelect={(value) => model.setAncLevel(Number(value))}
        />
      ) : null}

      {noise().transparencyLevel ? (
        <ChoiceTabs
          label="通透档位"
          value={
            noise().transparencyLevel!.confirmed !== null
              ? `${noise().transparencyLevel!.confirmed} 档`
              : null
          }
          options={levelOptions(
            "setting-transparency-level",
            noise().transparencyLevel!.levels,
            (level) => `${level} 档`,
          )}
          confirmed={
            noise().transparencyLevel!.confirmed !== null
              ? String(noise().transparencyLevel!.confirmed)
              : null
          }
          disabled={!model.canWrite("TransparencyLevel")}
          feedback={model.feedback("TransparencyLevel")}
          onSelect={(value) => model.setTransparencyLevel(Number(value))}
        />
      ) : null}

      {noise().cycle ? (
        <SettingGroup
          label="降噪轮换"
          value={noise().cycle!.summary}
          feedback={model.feedback("NoiseCycle")}
        >
          <Column class="gap-1.5">
            <Each of={noise().cycle!.options} key={(item) => item.mode}>
              {(item) => (
                <CheckboxRow
                  testID={`setting-noise-cycle-${item().mode}`}
                  label={item().label}
                  caption={
                    item().checked && noise().cycle!.atMinimum
                      ? `至少保留 ${noise().cycle!.minimum} 项`
                      : null
                  }
                  checked={item().checked}
                  disabled={item().disabled}
                  onChange={(checked) => model.toggleNoiseCycleMode(item().mode, checked)}
                />
              )}
            </Each>
          </Column>
        </SettingGroup>
      ) : null}
    </Card>
  );
}
