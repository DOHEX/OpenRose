import {
  ActionButton,
  Card,
  Column,
  Divider,
  MetricGrid,
  MutedText,
  NoticeBanner,
  Row,
  SectionTitle,
  SegmentedTabs,
  StatusBadge,
} from "../ui";
import type { MetricItem, SegmentOption, ToneVariant } from "../ui";

export interface MainPageProps {
  headsetTitle: string;
  connectionKind: "disconnected" | "connecting" | "connected";
  connectionLabel: string;
  connectionSubtitle: string;
  connectionBusy: boolean;
  connectionErrorTitle: string | null;
  connectionErrorDetail: string | null;
  familyLabel: string;
  batteryItems: MetricItem[];
  noiseOptions: SegmentOption[];
  confirmedNoiseLabel: string | null;
  activeNoiseValue: string | null;
  noiseDisabled: boolean;
  noiseStatus: {
    tone: ToneVariant;
    title: string;
    detail?: string | null;
  } | null;
  onConnect: () => void;
  onDisconnect: () => void;
  onSelectNoiseMode: (mode: string) => void;
}

export function MainPage(props: MainPageProps) {
  const badgeTone = (): ToneVariant => {
    if (props.connectionKind === "connected") return "success";
    if (props.connectionKind === "connecting") return "warning";
    return "neutral";
  };

  return (
    <Column class="flex-1 gap-4">
      <Card animateEntrance={true}>
        <Row class="items-start justify-between gap-3">
          <Column class="flex-1 gap-1">
            <Row class="items-center gap-2">
              <SectionTitle>{props.headsetTitle}</SectionTitle>
              <StatusBadge
                testID="connection-badge"
                label={props.connectionLabel}
                tone={badgeTone()}
              />
            </Row>
            <MutedText testID="connection-phase">{props.connectionSubtitle}</MutedText>
          </Column>
          {props.connectionKind === "connected" ? (
            <ActionButton
              testID="btn-disconnect"
              label={props.connectionBusy ? "断开中…" : "断开"}
              variant="secondary"
              disabled={props.connectionBusy}
              busy={props.connectionBusy}
              onPress={props.onDisconnect}
            />
          ) : (
            <ActionButton
              testID="btn-connect"
              label={
                props.connectionKind === "connecting" || props.connectionBusy
                  ? "连接中…"
                  : "连接耳机"
              }
              variant="primary"
              disabled={props.connectionKind === "connecting" || props.connectionBusy}
              busy={props.connectionKind === "connecting" || props.connectionBusy}
              onPress={props.onConnect}
            />
          )}
        </Row>

        {props.connectionErrorTitle ? (
          <NoticeBanner
            testID="connection-error"
            tone="danger"
            title={props.connectionErrorTitle}
            detail={props.connectionErrorDetail}
          />
        ) : null}

        <Divider />

        <Column class="gap-2">
          <Row class="items-center justify-between">
            <SectionTitle>电量状态</SectionTitle>
            <MutedText>{props.familyLabel}</MutedText>
          </Row>
          {props.batteryItems.length > 0 ? (
            <MetricGrid items={props.batteryItems} />
          ) : (
            <NoticeBanner
              testID="battery-empty"
              tone="neutral"
              title="连接耳机后将自动读取耳机与充电盒电量"
            />
          )}
        </Column>
      </Card>

      <Card animateEntrance={true} delay={40}>
        <Row class="items-center justify-between gap-2">
          <SectionTitle>降噪模式</SectionTitle>
          {props.confirmedNoiseLabel ? (
            <StatusBadge
              testID="confirmed-noise-badge"
              tone="accent"
              label={`当前：${props.confirmedNoiseLabel}`}
            />
          ) : null}
        </Row>

        {props.noiseOptions.length > 0 ? (
          <SegmentedTabs
            accessibilityLabel="降噪模式"
            options={props.noiseOptions}
            value={props.activeNoiseValue}
            disabled={props.noiseDisabled}
            onChange={props.onSelectNoiseMode}
          />
        ) : (
          <NoticeBanner
            testID="noise-empty"
            tone="neutral"
            title={
              props.connectionKind === "connected"
                ? "当前型号不支持降噪模式切换"
                : "连接耳机后可控制降噪模式"
            }
          />
        )}

        {props.noiseStatus ? (
          <NoticeBanner
            testID="noise-status"
            tone={props.noiseStatus.tone}
            title={props.noiseStatus.title}
            detail={props.noiseStatus.detail}
          />
        ) : null}
      </Card>
    </Column>
  );
}
