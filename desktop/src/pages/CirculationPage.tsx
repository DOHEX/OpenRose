import type { CirculationController } from "../ui";
import {
  ActionButton,
  Card,
  Column,
  Divider,
  ListItem,
  MonoText,
  MutedText,
  NoticeBanner,
  QrCodeMatrix,
  Row,
  SectionTitle,
  SegmentedTabs,
  StatusBadge,
  TextField,
} from "../ui";

function offerValidText(validForMillis: number): string {
  const minutes = Math.max(1, Math.round(validForMillis / 60000));
  return `配对码约 ${minutes} 分钟内有效`;
}

export function CirculationPage(props: { controller: CirculationController }) {
  const controller = props.controller;
  const unpairedNearby = () =>
    controller.snapshot().nearbyPeers.filter((peer) => !peer.paired);
  const offer = () => controller.snapshot().pairingOffer;
  const offlinePullAddress = () => controller.offlinePullAddress();
  return (
    <Column class="flex-1 gap-4">
      <Card testID="circulation-card" animateEntrance={true}>
        <Row class="items-center justify-between gap-2">
          <Column class="gap-0.5">
            <SectionTitle>耳机流转</SectionTitle>
            <MutedText>在同一局域网内查看并控制其他音源设备上正在使用的耳机</MutedText>
          </Column>
          <StatusBadge
            testID="circ-local-badge"
            tone={controller.snapshot().localPeer.started ? "success" : "warning"}
            label={controller.snapshot().localPeer.name}
          />
        </Row>
        {controller.errorText() ? (
          <Column class="gap-2">
            <NoticeBanner testID="circ-error" tone="danger" title={controller.errorText()!} />
            <ActionButton
              testID="btn-circ-clear-error"
              label="知道了"
              variant="ghost"
              onPress={controller.clearError}
            />
          </Column>
        ) : null}
        {controller.snapshot().localPeer.startError ? (
          <NoticeBanner
            testID="circ-start-error"
            tone="warning"
            title="本机暂时无法被其他对端发现"
            detail={controller.snapshot().localPeer.startError}
          />
        ) : null}
      </Card>

      <Card testID="circ-peers-card" animateEntrance={true} delay={40}>
        <SectionTitle>已流转配对的对端</SectionTitle>
        {controller.pullFeedback() ? (
          <NoticeBanner
            testID="circ-pull-feedback"
            tone={controller.pullFeedback()!.tone}
            title={controller.pullFeedback()!.title}
            detail={controller.pullFeedback()!.detail}
          />
        ) : null}
        {controller.remoteHeadsets().length === 0 ? (
          <NoticeBanner
            testID="circ-peers-empty"
            title="还没有流转配对的对端"
            detail="在另一台音源设备上开始流转配对，再在下方输入配对码；或在本机开始流转配对。"
          />
        ) : (
          <Column class="gap-3">
            <Each of={controller.remoteHeadsets()} key={(item) => item.peerId}>
              {(peer, index) => (
                <ListItem
                  testID={`circ-peer-${index()}`}
                  class="gap-2 p-3"
                >
                  <Row class="items-center justify-between gap-3">
                    <Column class="flex-1 gap-0.5">
                      <SectionTitle>{peer().peerName}</SectionTitle>
                      <MutedText testID={`circ-remote-name-${index()}`}>
                        {peer().headsetName ?? "当前没有接入耳机"}
                      </MutedText>
                    </Column>
                    <StatusBadge
                      testID={`circ-peer-status-${index()}`}
                      tone={peer().available ? "success" : "warning"}
                      label={peer().available ? "在线" : "不在线"}
                    />
                  </Row>
                  {peer().available && peer().batteryText ? (
                    <MonoText testID={`circ-remote-battery-${index()}`}>
                      {peer().batteryText!}
                    </MonoText>
                  ) : null}
                  {peer().available && peer().modes.length > 0 ? (
                    <Column class="gap-1.5">
                      <MutedText>{peer().confirmedModeLabel}</MutedText>
                      <SegmentedTabs
                        accessibilityLabel={`${peer().peerName} 的降噪模式`}
                        options={peer().modeOptions.map((option) => ({
                          ...option,
                          testID: `circ-noise-${index()}-${option.value}`,
                        }))}
                        value={peer().pendingMode ?? peer().confirmedMode}
                        disabled={!peer().canSwitch}
                        onChange={(mode) =>
                          void controller.setRemoteNoiseMode(peer().peerId, mode)
                        }
                      />
                      {peer().pendingText ? (
                        <NoticeBanner
                          testID={`circ-noise-status-${index()}`}
                          tone="accent"
                          title={peer().pendingText!}
                        />
                      ) : null}
                    </Column>
                  ) : null}
                  {!peer().available ? (
                    <MutedText testID={`circ-peer-offline-${index()}`}>
                      对端回到局域网后会自动重新读取
                    </MutedText>
                  ) : null}
                  <Row class="flex-wrap gap-2">
                    {peer().holds && peer().headsetId ? (
                      <ActionButton
                        testID={`btn-circ-pull-${index()}`}
                        label="接到这台电脑"
                        variant="primary"
                        busy={controller.pullingAddress() === peer().headsetId}
                        disabled={controller.pullingAddress() !== null}
                        onPress={() => void controller.pull(peer().headsetId!)}
                      />
                    ) : null}
                    <ActionButton
                      testID={`btn-circ-refresh-${index()}`}
                      label="刷新"
                      variant="secondary"
                      disabled={!peer().available}
                      onPress={() => void controller.refreshPeer(peer().peerId)}
                    />
                    <ActionButton
                      testID={`btn-circ-forget-${index()}`}
                      label="取消流转配对"
                      variant="destructive"
                      onPress={() => void controller.forget(peer().peerId)}
                    />
                  </Row>
                </ListItem>
              )}
            </Each>
          </Column>
        )}
        {offlinePullAddress() ? (
          <ActionButton
            testID="btn-circ-pull-offline"
            label="接到这台电脑"
            variant="primary"
            busy={controller.pullingAddress() !== null}
            disabled={controller.pullingAddress() !== null}
            onPress={() => void controller.pull(offlinePullAddress()!)}
          />
        ) : null}
      </Card>

      <Card testID="circ-pair-card" animateEntrance={true} delay={80}>
        <SectionTitle>流转配对新对端</SectionTitle>
        <Column class="gap-2">
          <MutedText>在本机开始流转配对，让另一台音源设备输入配对码或扫描二维码</MutedText>
          <ActionButton
            testID="btn-circ-offer"
            label="开始流转配对"
            variant="secondary"
            disabled={controller.busy()}
            onPress={() => void controller.offerPairing()}
          />
          {offer() ? (
            <Column testID="circ-offer" class="gap-2">
              <MonoText testID="circ-offer-code">{offer()!.code}</MonoText>
              <MutedText>{offerValidText(offer()!.validForMillis)}</MutedText>
              <QrCodeMatrix
                testID="circ-offer-qr"
                accessibilityLabel="配对二维码"
                size={offer()!.qrSize}
                modules={offer()!.qrModules}
              />
            </Column>
          ) : null}
        </Column>
        <Divider />
        <Column class="gap-2">
          <MutedText>在另一台音源设备上开始流转配对后，选择它并输入其配对码</MutedText>
          {unpairedNearby().length === 0 ? (
            <NoticeBanner testID="circ-nearby-empty" title="局域网内暂未发现可流转配对的对端" />
          ) : (
            <Each of={unpairedNearby()} key={(item) => item.id}>
              {(nearby, index) => (
                <Row class="items-center justify-between gap-3">
                  <Column testID={`circ-nearby-${index()}`} class="flex-1 gap-0.5">
                    <SectionTitle>{nearby().name}</SectionTitle>
                    <MutedText>{nearby().addresses[0] ?? nearby().id}</MutedText>
                  </Column>
                  <ActionButton
                    testID={`btn-circ-select-${index()}`}
                    label={controller.selectedPeerId() === nearby().id ? "已选择" : "选择"}
                    variant={controller.selectedPeerId() === nearby().id ? "primary" : "secondary"}
                    onPress={() => controller.selectNearbyPeer(nearby().id)}
                  />
                </Row>
              )}
            </Each>
          )}
          <TextField
            testID="circ-code-input"
            label="对端配对码"
            value={controller.pairingCode()}
            onChange={controller.setPairingCode}
            onSubmit={() => void controller.pairSelected()}
            placeholder="6 位数字"
          />
          <ActionButton
            testID="btn-circ-pair"
            label="流转配对"
            disabled={
              controller.busy() ||
              controller.selectedPeerId() === null ||
              controller.pairingCode().trim().length === 0
            }
            busy={controller.busy()}
            onPress={() => void controller.pairSelected()}
          />
        </Column>
      </Card>

      {controller.development ? (
        <Card testID="circ-dev-card" animateEntrance={true} delay={120}>
          <Row class="items-center justify-between gap-2">
            <SectionTitle>开发模式 · 模拟对端</SectionTitle>
            <StatusBadge tone="accent" label="Simulator" />
          </Row>
          <Row class="flex-wrap gap-2">
            <ActionButton
              testID="btn-circ-dev-add"
              label="添加模拟对端"
              variant="secondary"
              disabled={controller.busy()}
              onPress={() => void controller.devAddPeer()}
            />
            <ActionButton
              testID="btn-circ-dev-open"
              label="模拟对端开始流转配对"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => void controller.devOpenPeerPairing()}
            />
            <ActionButton
              testID="btn-circ-dev-offline"
              label="模拟对端离线"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => void controller.devSetPeerOnline(false)}
            />
            <ActionButton
              testID="btn-circ-dev-slow"
              label="模拟对端响应变慢"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => controller.devSetPeerLatencyMs(400)}
            />
            <ActionButton
              testID="btn-circ-dev-online"
              label="模拟对端上线"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => void controller.devSetPeerOnline(true)}
            />
            <ActionButton
              testID="btn-circ-dev-in-call"
              label="模拟对端通话中"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => controller.devSetPeerYieldRefusal("in_call")}
            />
            <ActionButton
              testID="btn-circ-dev-mic-in-use"
              label="模拟对端麦克风占用"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => controller.devSetPeerYieldRefusal("mic_in_use")}
            />
            <ActionButton
              testID="btn-circ-dev-no-answer"
              label="模拟对端无响应"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => controller.devSetPeerNoAnswer(true)}
            />
            <ActionButton
              testID="btn-circ-dev-acquire-in-call"
              label="模拟本机接入被拒（通话中）"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => controller.devSetLocalAcquireRefusal("in_call")}
            />
            <ActionButton
              testID="btn-circ-dev-allow-yield"
              label="恢复对端可让出"
              variant="secondary"
              disabled={controller.devPeer() === null}
              onPress={() => {
                controller.devSetPeerYieldRefusal(null);
                controller.devSetPeerNoAnswer(false);
                controller.devSetLocalAcquireRefusal(null);
              }}
            />
          </Row>
          {controller.devPeer() ? (
            <MutedText testID="circ-dev-peer">
              {`模拟对端：${controller.devPeer()!.name}（${controller.devPeer()!.online ? "运行中" : "已停止"}）`}
            </MutedText>
          ) : null}
          {controller.devPairing() ? (
            <MonoText testID="circ-dev-code">{controller.devPairing()!.code}</MonoText>
          ) : null}
        </Card>
      ) : null}
    </Column>
  );
}
