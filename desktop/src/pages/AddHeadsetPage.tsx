import {
  ActionButton,
  Card,
  Column,
  Divider,
  InfoRow,
  MutedText,
  NoticeBanner,
  Row,
  SectionTitle,
  StatusBadge,
} from "../ui";
import type { ToneVariant } from "../ui";

export interface AddHeadsetCandidateView {
  id: string;
  displayName: string;
  configCaption: string | null;
  rssiText: string | null;
  connectingPhaseLabel: string | null;
  isControlConnected: boolean;
  errorMessage: string | null;
}

export interface UnsupportedCandidateView {
  id: string;
  name: string;
  rssiText: string | null;
}

export interface AddHeadsetPageProps {
  scanning: boolean;
  scanError: string | null;
  blockReason: { title: string; detail: string | null } | null;
  busy: boolean;
  addedControlSuccess: boolean;
  supportedCandidates: AddHeadsetCandidateView[];
  unsupportedCandidates: UnsupportedCandidateView[];
  onStartScan: () => void;
  onStopScan: () => void;
  onConnectCandidate: (headsetId: string) => void;
  onCancelConnection: () => void;
  onDone: () => void;
  onOpenDiagnostics: () => void;
}

export function AddHeadsetPage(props: AddHeadsetPageProps) {
  const scanBadge = (): { label: string; tone: ToneVariant } => {
    if (props.blockReason) return { label: "无法搜索", tone: "danger" };
    if (props.scanning) return { label: "正在搜索…", tone: "warning" };
    if (props.scanError) return { label: "搜索失败", tone: "danger" };
    if (props.addedControlSuccess) return { label: "控制已就绪", tone: "success" };
    return { label: "准备就绪", tone: "neutral" };
  };

  return (
    <Column class="flex-1 gap-4">
      <Card testID="add-headset-card" animateEntrance={true}>
        <Row class="items-start justify-between gap-3">
          <Column class="flex-1 gap-1">
            <Row class="items-center gap-2">
              <SectionTitle>添加耳机</SectionTitle>
              <StatusBadge
                testID="add-scan-badge"
                label={scanBadge().label}
                tone={scanBadge().tone}
              />
            </Row>
            <MutedText>
              扫描附近受支持的 ROSE 耳机并建立控制连接。添加成功只表示控制已就绪，不代表系统音频已配对或已连接。
            </MutedText>
          </Column>
          <ActionButton
            testID="btn-scan-toggle"
            label={props.scanning ? "停止搜索" : "开始搜索"}
            accessibilityLabel={props.scanning ? "停止搜索附近耳机" : "开始搜索附近耳机"}
            variant={props.scanning ? "secondary" : "primary"}
            disabled={Boolean(props.blockReason) || props.busy}
            onPress={props.scanning ? props.onStopScan : props.onStartScan}
          />
        </Row>

        {props.blockReason ? (
          <Column class="gap-2">
            <NoticeBanner
              testID="add-block-banner"
              tone="danger"
              title={props.blockReason.title}
              detail={props.blockReason.detail}
            />
            <ActionButton
              testID="btn-add-diagnostics"
              label="查看诊断与记录"
              accessibilityLabel="查看诊断与记录"
              variant="secondary"
              onPress={props.onOpenDiagnostics}
            />
          </Column>
        ) : props.scanError ? (
          <NoticeBanner
            testID="add-scan-error"
            tone="danger"
            title="搜索失败"
            detail={props.scanError}
          />
        ) : null}

        {props.addedControlSuccess ? (
          <Column class="gap-2">
            <NoticeBanner
              testID="add-control-ready"
              tone="success"
              title="控制已就绪"
              detail="已建立与耳机的控制连接；这不代表系统音频已配对或已连接。"
            />
            <ActionButton
              testID="btn-add-done"
              label="返回我的耳机"
              accessibilityLabel="返回我的耳机"
              variant="primary"
              onPress={props.onDone}
            />
          </Column>
        ) : null}
      </Card>

      <Card testID="add-supported-card" animateEntrance={true} delay={40}>
        <Row class="items-center justify-between gap-2">
          <SectionTitle>受支持的耳机</SectionTitle>
          <MutedText>{`已发现 ${props.supportedCandidates.length} 副`}</MutedText>
        </Row>
        <Divider />
        {props.supportedCandidates.length > 0 ? (
          <Column class="gap-3">
            <Each
              of={props.supportedCandidates}
              key={(item: AddHeadsetCandidateView) => item.id}
            >
              {(candidate, index) => (
                <Column
                  testID={`add-candidate-${candidate().id}`}
                  class="gap-2 rounded-(--radius) border border-border bg-muted/40 p-3"
                >
                  <Row class="items-center justify-between gap-3">
                    <Column class="flex-1 gap-0.5">
                      <Row class="items-center gap-2">
                        <SectionTitle>{candidate().displayName}</SectionTitle>
                        {candidate().rssiText ? (
                          <MutedText>{candidate().rssiText!}</MutedText>
                        ) : null}
                      </Row>
                      <MutedText>
                        {[candidate().configCaption, candidate().id].filter(Boolean).join(" · ")}
                      </MutedText>
                    </Column>
                    {candidate().connectingPhaseLabel ? (
                      <ActionButton
                        testID={`btn-add-cancel-${index()}`}
                        label="取消连接"
                        accessibilityLabel={`取消连接 ${candidate().displayName}`}
                        variant="secondary"
                        onPress={props.onCancelConnection}
                      />
                    ) : candidate().isControlConnected ? (
                      <StatusBadge
                        testID={`badge-add-ready-${index()}`}
                        tone="success"
                        label="控制已就绪"
                      />
                    ) : (
                      <ActionButton
                        testID={`btn-add-connect-${index()}`}
                        label={candidate().errorMessage ? "重试" : "建立控制连接"}
                        accessibilityLabel={`建立与 ${candidate().displayName} 的控制连接`}
                        variant="primary"
                        disabled={Boolean(props.blockReason) || props.busy}
                        onPress={() => props.onConnectCandidate(candidate().id)}
                      />
                    )}
                  </Row>
                  {candidate().connectingPhaseLabel ? (
                    <NoticeBanner
                      tone="warning"
                      title={candidate().connectingPhaseLabel!}
                    />
                  ) : null}
                  {candidate().errorMessage ? (
                    <NoticeBanner
                      tone="danger"
                      title="控制连接失败"
                      detail={candidate().errorMessage}
                    />
                  ) : null}
                </Column>
              )}
            </Each>
          </Column>
        ) : (
          <NoticeBanner
            testID="add-supported-empty"
            tone="neutral"
            title={
              props.scanning
                ? "正在搜索附近受支持的 ROSE 耳机…"
                : "点击“开始搜索”以发现附近受支持的 ROSE 耳机"
            }
          />
        )}
      </Card>

      {props.unsupportedCandidates.length > 0 ? (
        <Card testID="add-unsupported-card" animateEntrance={true} delay={80}>
          <SectionTitle>其他蓝牙设备</SectionTitle>
          <Divider />
          <Column class="gap-1">
            <Each
              of={props.unsupportedCandidates}
              key={(item: UnsupportedCandidateView) => item.id}
            >
              {(item) => (
                <InfoRow
                  label={item().name}
                  value="暂不支持"
                  detail={item().rssiText ? `${item().id} · ${item().rssiText}` : item().id}
                />
              )}
            </Each>
          </Column>
        </Card>
      ) : null}
    </Column>
  );
}
