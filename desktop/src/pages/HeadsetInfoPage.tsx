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

export interface HeadsetInfoPageProps {
  hasHeadset: boolean;
  isConnected: boolean;
  isConnecting: boolean;
  modelConfigLabel: string;
  protocolFamilyLabel: string;
  firmwareText: string;
  controlTransportLabel: string;
  controlTransportDetail: string | null;
  headsetId: string | null;
  classicAddress: string | null;
  onDisconnect: () => void;
  onOpenDiagnostics: () => void;
  onOpenAddHeadset: () => void;
}

export function HeadsetInfoPage(props: HeadsetInfoPageProps) {
  return (
    <Column class="flex-1 gap-4">
      <Card testID="headset-info-card" animateEntrance={true}>
        <Row class="items-center justify-between gap-2">
          <Column class="gap-0.5">
            <SectionTitle>耳机信息</SectionTitle>
            <MutedText>当前耳机的型号配置、固件与控制传输事实</MutedText>
          </Column>
          <StatusBadge
            testID="info-status-badge"
            tone={props.isConnected ? "success" : props.isConnecting ? "warning" : "neutral"}
            label={props.isConnected ? "已连接" : props.isConnecting ? "连接中" : "未连接"}
          />
        </Row>

        <Divider />

        {props.hasHeadset ? (
          <Column class="gap-1">
            <InfoRow
              testID="info-model-profile"
              label="型号配置"
              value={props.modelConfigLabel}
            />
            <InfoRow
              testID="info-protocol-family"
              label="协议族"
              value={props.protocolFamilyLabel}
            />
            <InfoRow
              testID="info-firmware"
              label="固件"
              value={props.firmwareText}
            />
            <InfoRow
              testID="info-control-transport"
              label="控制传输"
              value={props.controlTransportLabel}
              detail={props.controlTransportDetail}
            />
            {props.headsetId ? (
              <InfoRow
                testID="info-headset-id"
                label="耳机标识"
                value={props.headsetId}
                mono={true}
              />
            ) : null}
            {props.classicAddress ? (
              <InfoRow
                testID="info-classic-address"
                label="经典蓝牙地址"
                value={props.classicAddress}
                mono={true}
              />
            ) : null}
          </Column>
        ) : (
          <NoticeBanner
            testID="info-empty"
            tone="neutral"
            title="当前没有已选定或已连接的耳机"
            detail="可前往添加耳机流程建立控制连接，或查看诊断与记录。"
          />
        )}

        <Divider />

        <Row class="flex-wrap gap-2">
          <ActionButton
            testID="btn-info-diagnostics"
            label="诊断与记录"
            accessibilityLabel="打开诊断与记录"
            variant="secondary"
            onPress={props.onOpenDiagnostics}
          />
          {props.isConnected || props.isConnecting ? (
            <ActionButton
              testID="btn-info-disconnect"
              label={props.isConnecting ? "取消连接" : "断开控制连接"}
              accessibilityLabel={props.isConnecting ? "取消控制连接" : "断开控制连接"}
              variant="destructive"
              onPress={props.onDisconnect}
            />
          ) : !props.hasHeadset ? (
            <ActionButton
              testID="btn-info-add-headset"
              label="添加耳机"
              accessibilityLabel="前往添加耳机"
              variant="primary"
              onPress={props.onOpenAddHeadset}
            />
          ) : null}
        </Row>
      </Card>
    </Column>
  );
}
