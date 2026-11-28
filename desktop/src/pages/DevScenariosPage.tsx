import {
  ActionButton,
  Card,
  Column,
  MutedText,
  NoticeBanner,
  Row,
  SectionTitle,
  SegmentedTabs,
  StatusBadge,
} from "../ui";
import type { SegmentOption } from "../ui";

export interface DevScenariosPageProps {
  bondedOptions: SegmentOption[];
  bondedValue: string;
  platformOptions: SegmentOption[];
  platformValue: string;
  error: string | null;
  onChangeBonded: (value: string) => void;
  onChangePlatform: (value: string) => void;
  onAdvertiseSupported: () => void;
  onAdvertiseUnsupported: () => void;
}

export function DevScenariosPage(props: DevScenariosPageProps) {
  return (
    <Card testID="dev-scenarios-card" animateEntrance={true} delay={120}>
      <Row class="items-center justify-between gap-2">
        <SectionTitle>开发模式 · 场景</SectionTitle>
        <StatusBadge tone="accent" label="Simulator" />
      </Row>
      <Column class="gap-1.5">
        <MutedText>已配对耳机</MutedText>
        <SegmentedTabs
          accessibilityLabel="模拟已配对耳机数量"
          options={props.bondedOptions}
          value={props.bondedValue}
          onChange={props.onChangeBonded}
        />
      </Column>
      <Column class="gap-1.5">
        <MutedText>平台状态</MutedText>
        <SegmentedTabs
          accessibilityLabel="模拟平台状态"
          options={props.platformOptions}
          value={props.platformValue}
          onChange={props.onChangePlatform}
        />
      </Column>
      <Column class="gap-1.5">
        <MutedText>扫描广播</MutedText>
        <Row class="flex-wrap gap-2">
          <ActionButton
            testID="btn-dev-advertise-supported"
            label="广播 HeadFeel"
            accessibilityLabel="模拟广播受支持的 HeadFeel 耳机"
            variant="secondary"
            onPress={props.onAdvertiseSupported}
          />
          <ActionButton
            testID="btn-dev-advertise-unsupported"
            label="广播其他设备"
            accessibilityLabel="模拟广播不受支持的蓝牙设备"
            variant="secondary"
            onPress={props.onAdvertiseUnsupported}
          />
        </Row>
      </Column>
      {props.error ? <NoticeBanner testID="dev-scenario-error" tone="danger" title={props.error} /> : null}
    </Card>
  );
}
