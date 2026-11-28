import type { HeadsetSettingsModel } from "../../../model/settings";
import { Card, Column, Divider, MutedText, SectionTitle, SegmentedTabs, SelectField } from "../../../ui";
import { SettingGroup, ToggleSetting } from "./shared";

export function TouchSection(props: { model: HeadsetSettingsModel }) {
  const model = props.model;
  const touch = () => model.touchSection()!;

  return (
    <Card>
      <SectionTitle>触控与佩戴</SectionTitle>

      {touch().touchEnabled !== undefined ? (
        <ToggleSetting
          testID="setting-touch-enabled"
          label="触控总开关"
          checked={touch().touchEnabled ?? null}
          disabled={!model.canWrite("TouchEnabled")}
          feedback={model.feedback("TouchEnabled")}
          onChange={model.setTouchEnabled}
        />
      ) : null}

      {touch().earDetection !== undefined ? (
        <ToggleSetting
          testID="setting-ear-detection"
          label="佩戴检测功能"
          checked={touch().earDetection ?? null}
          disabled={!model.canWrite("EarDetection")}
          feedback={model.feedback("EarDetection")}
          onChange={model.setEarDetection}
        />
      ) : null}

      {touch().touchBinding ? (
        <Column class="gap-2">
          <Divider />
          {touch().touchBinding!.sides.length > 1 ? (
            <SegmentedTabs
              accessibilityLabel={touch().touchBinding!.title}
              options={touch().touchBinding!.sides.map((side) => ({
                value: side.value,
                label: side.label,
                testID: `setting-touch-side-${side.value}`,
              }))}
              value={touch().touchBinding!.activeSide}
              onChange={model.selectTouchSide}
            />
          ) : null}
          <SettingGroup label={touch().touchBinding!.title} feedback={model.feedback("TouchBinding")}>
            <Column class="gap-2">
              <Each of={touch().touchBinding!.slots} key={(slot) => `${slot.side}:${slot.gesture}`}>
                {(slot) => (
                  <SelectField
                    testID={`setting-touch-slot-${slot().side}-${slot().gesture}`}
                    label={slot().label}
                    items={touch().touchBinding!.actions}
                    value={slot().action}
                    placeholder="当前绑定未知"
                    disabled={!model.canWrite("TouchBinding")}
                    onChange={(action) => {
                      if (action) model.setTouchBinding(slot().side, slot().gesture, action);
                    }}
                  />
                )}
              </Each>
            </Column>
          </SettingGroup>
        </Column>
      ) : null}

      {touch().tap ? (
        <Column class="gap-2">
          <Divider />
          <MutedText>轻拍控制</MutedText>
          {touch().tap!.enabled !== undefined ? (
            <ToggleSetting
              testID="setting-tap-enabled"
              label="轻拍开关"
              checked={touch().tap!.enabled ?? null}
              disabled={!model.canWrite("TapEnabled")}
              feedback={model.feedback("TapEnabled")}
              onChange={model.setTapEnabled}
            />
          ) : null}
          <SettingGroup label="轻拍绑定" feedback={model.feedback("TapBinding")}>
            <Column class="gap-2">
              <Each of={touch().tap!.slots} key={(slot) => `${slot.side}:${slot.gesture}`}>
                {(slot) => (
                  <SelectField
                    testID={`setting-tap-slot-${slot().side}-${slot().gesture}`}
                    label={slot().label}
                    items={touch().tap!.actions}
                    value={slot().action}
                    placeholder="当前绑定未知"
                    disabled={!model.canWrite("TapBinding")}
                    onChange={(action) => {
                      if (action) model.setTapBinding(slot().side, slot().gesture, action);
                    }}
                  />
                )}
              </Each>
            </Column>
          </SettingGroup>
        </Column>
      ) : null}
    </Card>
  );
}
