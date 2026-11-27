import {
  ActionButton,
  Card,
  Column,
  Divider,
  MonoText,
  MutedText,
  NoticeBanner,
  Row,
  SectionTitle,
  SegmentedTabs,
  SelectField,
  StatusBadge,
  TextField,
} from "../ui";
import type { SegmentOption, SelectFieldOption } from "../ui";

export interface DevModePageProps {
  modelOptions: SelectFieldOption[];
  selectedModelId: string;
  firmwareInput: string;
  defaultFirmwarePlaceholder: string;
  familyHint?: string;
  firmwareHint?: string;
  devError: string | null;
  devBusy: boolean;
  latencyOptions: SegmentOption[];
  latencyValue: string;
  activeSummary: string | null;
  onSelectModel: (value: string | null) => void;
  onChangeFirmware: (value: string) => void;
  onApplySimulator: () => void;
  onChangeLatency: (value: string) => void;
}

export function DevModePage(props: DevModePageProps) {
  return (
    <Column class="gap-4 sm:w-72">
      <Card animateEntrance={true} delay={80}>
        <Row class="items-center justify-between gap-2">
          <SectionTitle>开发模式 · 模拟耳机</SectionTitle>
          <StatusBadge testID="dev-mode-badge" tone="accent" label="Simulator" />
        </Row>

        <SelectField
          testID="dev-model-select"
          searchTestID="dev-model-search"
          label="耳机型号"
          items={props.modelOptions}
          value={props.selectedModelId}
          placeholder="选择模拟型号"
          searchable={true}
          searchPlaceholder="搜索 ROSE 型号…"
          onChange={props.onSelectModel}
          hint={props.familyHint}
        />

        <TextField
          testID="dev-firmware-input"
          label="模拟固件版本"
          value={props.firmwareInput}
          onChange={props.onChangeFirmware}
          onSubmit={props.onApplySimulator}
          placeholder={props.defaultFirmwarePlaceholder}
          hint={props.firmwareHint}
          error={props.devError}
        />

        <ActionButton
          testID="btn-apply-dev"
          label={props.devBusy ? "切换模拟耳机中…" : "应用并连接"}
          variant="secondary"
          disabled={props.devBusy}
          busy={props.devBusy}
          onPress={props.onApplySimulator}
        />

        <Divider />

        <Column class="gap-1.5">
          <MutedText>模拟回复延迟</MutedText>
          <SegmentedTabs
            accessibilityLabel="模拟回复延迟"
            options={props.latencyOptions}
            value={props.latencyValue}
            onChange={props.onChangeLatency}
          />
        </Column>

        {props.activeSummary ? (
          <Column class="gap-1 rounded-(--radius) border border-border bg-muted/60 p-2.5">
            <MutedText>当前实例</MutedText>
            <MonoText testID="dev-active-summary">{props.activeSummary}</MonoText>
          </Column>
        ) : (
          <NoticeBanner tone="neutral" title="未启用开发模式模拟器" />
        )}
      </Card>
    </Column>
  );
}
