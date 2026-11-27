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

export interface BondedHeadsetItemView {
  id: string;
  displayName: string;
  configCaption: string | null;
  isCurrent: boolean;
}

export interface MainPageProps {
  hasCurrentHeadset: boolean;
  bondedHeadsets: BondedHeadsetItemView[];
  firstConnectionHint: string;
  headsetTitle: string;
  modelConfigCaption: string | null;
  connectionKind: "disconnected" | "connecting" | "connected";
  connectionLabel: string;
  connectionSubtitle: string;
  connectionBusy: boolean;
  blockReason: { title: string; detail: string | null } | null;
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
  onSelectCurrentHeadset: (headsetId: string) => void;
  onSelectNoiseMode: (mode: string) => void;
  onOpenAddHeadset: () => void;
  onOpenHeadsetInfo: () => void;
  onOpenDiagnostics: () => void;
}

export function MainPage(props: MainPageProps) {
  const badgeTone = (): ToneVariant => {
    if (props.connectionKind === "connected") return "success";
    if (props.connectionKind === "connecting") return "warning";
    return "neutral";
  };

  const showOnboardingOnly = () =>
    props.connectionKind === "disconnected" &&
    !props.hasCurrentHeadset &&
    props.bondedHeadsets.length === 0;

  return (
    <Column class="flex-1 gap-4">
      {showOnboardingOnly() ? (
        <Card testID="empty-bonded-onboarding" animateEntrance={true}>
          <Column class="gap-2">
            <Row class="items-center justify-between gap-2">
              <SectionTitle>添加你的 ROSE 耳机</SectionTitle>
              <StatusBadge testID="connection-badge" label="未连接" tone="neutral" />
            </Row>
            <MutedText testID="empty-bonded-hint">{props.firstConnectionHint}</MutedText>
            {props.blockReason ? (
              <NoticeBanner
                testID="connection-block-banner"
                tone="danger"
                title={props.blockReason.title}
                detail={props.blockReason.detail}
              />
            ) : null}
            <Row class="flex-wrap gap-2 pt-1">
              <ActionButton
                testID="btn-go-add-headset"
                label="搜索并添加耳机"
                accessibilityLabel="搜索并添加耳机"
                variant="primary"
                onPress={props.onOpenAddHeadset}
              />
              <ActionButton
                testID="btn-open-diagnostics"
                label="诊断与记录"
                accessibilityLabel="打开诊断与记录"
                variant="secondary"
                onPress={props.onOpenDiagnostics}
              />
            </Row>
          </Column>
        </Card>
      ) : (
        <Column class="gap-4">
          <Card testID="headset-status-card" animateEntrance={true}>
            <Row class="items-start justify-between gap-3">
              <Column class="flex-1 gap-1">
                <Row class="flex-wrap items-center gap-2">
                  <SectionTitle>{props.headsetTitle}</SectionTitle>
                  <StatusBadge
                    testID="connection-badge"
                    label={props.connectionLabel}
                    tone={badgeTone()}
                  />
                </Row>
                {props.modelConfigCaption ? (
                  <MutedText testID="model-config-caption">{props.modelConfigCaption}</MutedText>
                ) : null}
                <MutedText testID="connection-phase">{props.connectionSubtitle}</MutedText>
              </Column>
              {props.connectionKind === "connected" ? (
                <ActionButton
                  testID="btn-disconnect"
                  label={props.connectionBusy ? "断开中…" : "断开"}
                  accessibilityLabel="断开控制连接"
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
                  accessibilityLabel="连接当前耳机"
                  variant="primary"
                  disabled={
                    Boolean(props.blockReason) ||
                    props.connectionKind === "connecting" ||
                    props.connectionBusy
                  }
                  busy={props.connectionKind === "connecting" || props.connectionBusy}
                  onPress={props.onConnect}
                />
              )}
            </Row>

            {props.blockReason ? (
              <Column class="gap-2">
                <NoticeBanner
                  testID="connection-block-banner"
                  tone="danger"
                  title={props.blockReason.title}
                  detail={props.blockReason.detail}
                />
                <ActionButton
                  testID="btn-block-diagnostics"
                  label="查看诊断与记录"
                  accessibilityLabel="查看诊断与记录"
                  variant="secondary"
                  onPress={props.onOpenDiagnostics}
                />
              </Column>
            ) : props.connectionErrorTitle ? (
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

            {props.connectionKind === "connected" ? (
              <Row class="flex-wrap gap-2 pt-1">
                <ActionButton
                  testID="btn-open-headset-info"
                  label="耳机信息"
                  accessibilityLabel="打开耳机信息页"
                  variant="secondary"
                  onPress={props.onOpenHeadsetInfo}
                />
                <ActionButton
                  testID="btn-open-diagnostics"
                  label="诊断与记录"
                  accessibilityLabel="打开诊断与记录页"
                  variant="ghost"
                  onPress={props.onOpenDiagnostics}
                />
              </Row>
            ) : null}
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

          {props.connectionKind === "disconnected" && props.bondedHeadsets.length > 0 ? (
            <Card testID="bonded-headsets-card" animateEntrance={true} delay={80}>
              <Row class="items-center justify-between gap-2">
                <SectionTitle>{`已配对耳机（${props.bondedHeadsets.length}）`}</SectionTitle>
                <ActionButton
                  testID="btn-add-another-headset"
                  label="添加耳机"
                  accessibilityLabel="进入添加耳机流程"
                  variant="ghost"
                  onPress={props.onOpenAddHeadset}
                />
              </Row>
              <Divider />
              <Column class="gap-2">
                <Each
                  of={props.bondedHeadsets}
                  key={(item: BondedHeadsetItemView) => item.id}
                >
                  {(item, index) => (
                    <Row
                      testID={`bonded-headset-row-${index()}`}
                      class="items-center justify-between gap-3 rounded-(--radius) border border-border bg-muted/40 px-3 py-2"
                    >
                      <Column class="flex-1 gap-0.5">
                        <SectionTitle>{item().displayName}</SectionTitle>
                        <MutedText>
                          {[item().configCaption, item().id].filter(Boolean).join(" · ")}
                        </MutedText>
                      </Column>
                      {item().isCurrent ? (
                        <StatusBadge
                          testID={`current-headset-badge-${index()}`}
                          tone="accent"
                          label="当前耳机"
                        />
                      ) : (
                        <ActionButton
                          testID={`btn-switch-headset-${index()}`}
                          label="切换"
                          accessibilityLabel={`切换到 ${item().displayName}`}
                          variant="secondary"
                          onPress={() => props.onSelectCurrentHeadset(item().id)}
                        />
                      )}
                    </Row>
                  )}
                </Each>
              </Column>
            </Card>
          ) : null}
        </Column>
      )}
    </Column>
  );
}
