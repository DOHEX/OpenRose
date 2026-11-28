import type { JSX } from "nsh";
import type { SettingFeedback } from "../../../ui";
import { Column, MutedText, Row, SegmentedTabs, StatusBadge, SwitchRow } from "../../../ui";
import type { SegmentOption } from "../../../ui";

export function SettingStatus(props: { feedback: SettingFeedback }) {
  return (
    <Column class="gap-1">
      {props.feedback.kind === "in_progress" ? (
        <StatusBadge
          testID={`status-${props.feedback.setting}`}
          tone="warning"
          label={`${props.feedback.label}写入中…`}
        />
      ) : null}
      {props.feedback.kind === "confirmed" ? (
        <StatusBadge
          testID={`status-${props.feedback.setting}`}
          tone="success"
          label={`${props.feedback.label}已确认`}
        />
      ) : null}
      {props.feedback.kind === "failed" ? (
        <Column class="gap-0.5">
          <StatusBadge
            testID={`status-${props.feedback.setting}`}
            tone="danger"
            label={`${props.feedback.label}失败：${props.feedback.reason}`}
          />
          {props.feedback.message ? <MutedText>{props.feedback.message}</MutedText> : null}
        </Column>
      ) : null}
    </Column>
  );
}

export function SettingGroup(props: {
  label: string;
  value?: string | null;
  feedback: SettingFeedback;
  children?: JSX.Element;
}) {
  return (
    <Column class="gap-1.5">
      <Row class="items-center justify-between gap-2">
        <MutedText>{props.label}</MutedText>
        {props.value ? <MutedText>{props.value}</MutedText> : null}
      </Row>
      {props.children}
      <SettingStatus feedback={props.feedback} />
    </Column>
  );
}

export function ChoiceTabs(props: {
  label: string;
  value?: string | null;
  options: SegmentOption[];
  confirmed: string | null;
  disabled: boolean;
  feedback: SettingFeedback;
  onSelect: (value: string) => void;
}) {
  return (
    <SettingGroup label={props.label} value={props.value} feedback={props.feedback}>
      <SegmentedTabs
        accessibilityLabel={props.label}
        options={props.options}
        value={props.confirmed}
        disabled={props.disabled}
        onChange={props.onSelect}
      />
    </SettingGroup>
  );
}

export function levelOptions(
  idPrefix: string,
  levels: number[],
  format: (level: number) => string,
): SegmentOption[] {
  return levels.map((level) => ({
    value: String(level),
    label: format(level),
    testID: `${idPrefix}-${level}`,
  }));
}

export function ToggleSetting(props: {
  testID: string;
  label: string;
  checked: boolean | null;
  blockedReason?: string | null;
  disabled: boolean;
  feedback: SettingFeedback;
  onChange: (checked: boolean) => void;
}) {
  return (
    <Column class="gap-1">
      <SwitchRow
        testID={props.testID}
        label={props.label}
        summary={props.blockedReason ?? (props.checked === null ? "当前状态未知" : null)}
        checked={props.checked ?? false}
        disabled={props.disabled || props.checked === null}
        onChange={props.onChange}
      />
      <SettingStatus feedback={props.feedback} />
    </Column>
  );
}
