import {
  Card,
  CollapsibleSection,
  Column,
  Divider,
  InfoRow,
  ListItem,
  MonoText,
  MutedText,
  NoticeBanner,
  Row,
  SectionTitle,
} from "../ui";
import type {
  DiagnosticsOperationItem,
  DiagnosticsPlatformRow,
  DiagnosticsReadingItem,
  ToneVariant,
} from "../ui";

export interface DiagnosticsPageProps {
  platformRows: DiagnosticsPlatformRow[];
  connectionSummary: string;
  connectionPhaseText: string;
  sessionIdText: string;
  controlTransportText: string;
  controlTransportDetail: string | null;
  recoveryText: string | null;
  lastErrorText: string | null;
  operations: DiagnosticsOperationItem[];
  readings: DiagnosticsReadingItem[];
  rawSessionHeader: string | null;
  fieldErrors: Array<{ label: string; detail: string }>;
  warnings: string[];
  rawNotifications: string[];
  unknownNotifications: string[];
  rawDataOpen: boolean;
  onToggleRawData: () => void;
}

export function DiagnosticsPage(props: DiagnosticsPageProps) {
  return (
    <Column class="flex-1 gap-4">
      <Card testID="diag-platform-card" animateEntrance={true}>
        <SectionTitle>平台与系统状态</SectionTitle>
        <Divider />
        <Column class="gap-1">
          <Each
            of={props.platformRows}
            key={(row: DiagnosticsPlatformRow) => row.label}
          >
            {(row, index) => (
              <InfoRow
                testID={`diag-platform-${index()}`}
                label={row().label}
                value={row().value}
                detail={row().detail}
                tone={row().tone}
              />
            )}
          </Each>
        </Column>
      </Card>

      <Card testID="diag-connection-card" animateEntrance={true} delay={40}>
        <SectionTitle>控制连接阶段与恢复原因</SectionTitle>
        <Divider />
        <Column class="gap-1">
          <InfoRow testID="diag-conn-summary" label="当前耳机" value={props.connectionSummary} />
          <InfoRow testID="diag-conn-phase" label="连接阶段" value={props.connectionPhaseText} />
          <InfoRow
            testID="diag-conn-session"
            label="控制会话"
            value={props.sessionIdText}
            mono={true}
          />
          <InfoRow
            testID="diag-conn-transport"
            label="控制传输"
            value={props.controlTransportText}
            detail={props.controlTransportDetail}
          />
          <InfoRow
            testID="diag-conn-recovery"
            label="连接恢复"
            value={props.recoveryText ?? "无恢复记录"}
            tone={props.recoveryText ? "warning" : "neutral"}
          />
          {props.lastErrorText ? (
            <InfoRow
              testID="diag-conn-error"
              label="最近错误"
              value={props.lastErrorText}
              tone="danger"
            />
          ) : null}
        </Column>
      </Card>

      <Card testID="diag-operations-card" animateEntrance={true} delay={80}>
        <Row class="items-center justify-between gap-2">
          <SectionTitle>设置操作记录</SectionTitle>
          <MutedText>{`${props.operations.length} 条记录`}</MutedText>
        </Row>
        <Divider />
        {props.operations.length > 0 ? (
          <Column class="gap-2">
            <Each
              of={props.operations}
              key={(item: DiagnosticsOperationItem) => String(item.id)}
            >
              {(op) => (
                <InfoRow
                  testID={`diag-op-${op().id}`}
                  label={op().title}
                  value={op().stageLabel}
                  detail={op().detail}
                  tone={op().tone}
                />
              )}
            </Each>
          </Column>
        ) : (
          <NoticeBanner
            testID="diag-operations-empty"
            tone="neutral"
            title="暂无设置操作记录"
          />
        )}
      </Card>

      <Card testID="diag-readings-card" animateEntrance={true} delay={120}>
        <Row class="items-center justify-between gap-2">
          <SectionTitle>完整耳机读数与原始数据</SectionTitle>
          <MutedText>{`${props.readings.length} 项读数`}</MutedText>
        </Row>
        <Divider />
        {props.readings.length > 0 ? (
          <Column class="gap-3">
            <Column class="gap-1">
              <Each
                of={props.readings}
                key={(item: DiagnosticsReadingItem) => item.field}
              >
                {(item) => (
                  <InfoRow
                    testID={`diag-reading-${item().field}`}
                    label={item().label}
                    value={item().valueText}
                  />
                )}
              </Each>
            </Column>

            <CollapsibleSection
              testID="btn-toggle-raw-data"
              title="原始数据与底层通知"
              accessibilityLabel="展开或收起原始数据"
              summary={
                props.rawDataOpen
                  ? "包含字段原始十六进制与通知帧"
                  : "查看字段原始十六进制、错误、警告与通知帧"
              }
              open={props.rawDataOpen}
              onToggle={props.onToggleRawData}
            >
              <ListItem
                testID="diag-raw-data-panel"
                class="gap-2 p-3"
              >
                {props.rawSessionHeader ? (
                  <MonoText testID="diag-raw-session">{props.rawSessionHeader}</MonoText>
                ) : null}

                {props.readings.some((item) => item.rawHex.length > 0) ? (
                  <Column class="gap-1">
                    <MutedText>字段原始十六进制</MutedText>
                    <Each
                      of={props.readings.filter((item) => item.rawHex.length > 0)}
                      key={(item: DiagnosticsReadingItem) => item.field}
                    >
                      {(item) => (
                        <MonoText>{`${item().label} (${item().field}): ${item().rawHex}`}</MonoText>
                      )}
                    </Each>
                  </Column>
                ) : null}

                {props.fieldErrors.length > 0 ? (
                  <Column class="gap-1">
                    <MutedText>字段读取错误</MutedText>
                    <Each
                      of={props.fieldErrors}
                      key={(item: { label: string; detail: string }) => item.label}
                    >
                      {(item) => (
                        <MonoText>{`${item().label}: ${item().detail}`}</MonoText>
                      )}
                    </Each>
                  </Column>
                ) : null}

                {props.warnings.length > 0 ? (
                  <Column class="gap-1">
                    <MutedText>诊断警告</MutedText>
                    <Each of={props.warnings} key={(item: string) => item}>
                      {(item) => <MonoText>{item()}</MonoText>}
                    </Each>
                  </Column>
                ) : null}

                {props.rawNotifications.length > 0 ? (
                  <Column class="gap-1">
                    <MutedText>最近收到的原始通知</MutedText>
                    <Each
                      of={props.rawNotifications}
                      key={(item: string, idx: number) => `${idx}-${item}`}
                    >
                      {(item) => <MonoText>{item()}</MonoText>}
                    </Each>
                  </Column>
                ) : null}

                {props.unknownNotifications.length > 0 ? (
                  <Column class="gap-1">
                    <MutedText>未解析的通知</MutedText>
                    <Each
                      of={props.unknownNotifications}
                      key={(item: string, idx: number) => `${idx}-${item}`}
                    >
                      {(item) => <MonoText>{item()}</MonoText>}
                    </Each>
                  </Column>
                ) : null}
              </ListItem>
            </CollapsibleSection>
          </Column>
        ) : (
          <NoticeBanner
            testID="diag-readings-empty"
            tone="neutral"
            title="连接耳机后将在此显示完整读数与原始数据"
          />
        )}
      </Card>
    </Column>
  );
}
