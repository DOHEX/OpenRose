import type {
  DesktopSettingsStore,
  LoginLaunchStatus,
  LoginLaunchStore,
  TrayAvailability,
  TrayPanelControl,
} from "../../ui";
import {
  ActionButton,
  BodyText,
  Card,
  Column,
  Divider,
  ListItem,
  MutedText,
  NoticeBanner,
  Row,
  SectionTitle,
  StatusBadge,
  SwitchRow,
  ToggleSwitch,
} from "../../ui";

export interface TraySettingsPageProps {
  settings: DesktopSettingsStore;
  loginLaunch: LoginLaunchStore;
  availability: TrayAvailability;
  panelOpen: boolean;
  onTogglePanel: () => void;
}

export function TraySettingsPage(props: TraySettingsPageProps) {
  const panel = () => props.settings.trayPanel();
  props.loginLaunch.refresh();
  const loginLaunchStatus = () => props.loginLaunch.status();
  const loginLaunchSummary = (status: LoginLaunchStatus) =>
    status.kind === "unsupported"
      ? status.reason
      : "开启后台控制且托盘可用时，登录后仅驻留托盘不打开主窗口；否则打开主窗口";
  const loginLaunchError = () => props.loginLaunch.error();
  const unavailableReason = () =>
    props.availability.status === "unavailable" ? props.availability.reason : null;

  return (
    <Column testID="tray-settings-page" class="flex-1 gap-4">
      <Card testID="background-control-section">
        <Row class="items-center justify-between gap-2">
          <SectionTitle>托盘与后台控制</SectionTitle>
          <StatusBadge
            testID="tray-availability-badge"
            tone={props.availability.status === "available" ? "success" : "warning"}
            label={props.availability.status === "available" ? "托盘可用" : "托盘不可用"}
          />
        </Row>
        <SwitchRow
          plain
          testID="toggle-background-control"
          summaryTestID="background-control-summary"
          label="后台控制"
          summary="关闭窗口后保留在托盘，耳机控制不中断；从托盘退出才结束控制连接"
          checked={props.settings.backgroundControl()}
          onChange={props.settings.setBackgroundControl}
        />
        {loginLaunchStatus().kind !== "hidden" ? (
          <>
            <Divider />
            <SwitchRow
              plain
              rowTestID="login-launch-row"
              testID="toggle-launch-at-login"
              summaryTestID="login-launch-summary"
              label="登录时启动"
              summary={loginLaunchSummary(loginLaunchStatus())}
              checked={(() => {
                const current = loginLaunchStatus();
                return current.kind === "ready" && current.enabled;
              })()}
              disabled={loginLaunchStatus().kind !== "ready" || props.loginLaunch.busy()}
              onChange={(enabled) => void props.loginLaunch.setEnabled(enabled)}
            />
          </>
        ) : null}
        {loginLaunchError() ? (
          <NoticeBanner
            testID="login-launch-error"
            tone="danger"
            title={loginLaunchError()!}
          />
        ) : null}
        {unavailableReason() ? (
          <NoticeBanner
            testID="tray-unavailable-reason"
            tone="warning"
            title={`托盘不可用，关闭窗口将直接退出：${unavailableReason()}`}
          />
        ) : null}
      </Card>

      <Card testID="tray-controls-section">
        <Row class="items-center justify-between gap-2">
          <Column class="flex-1 gap-0.5">
            <SectionTitle>托盘面板控制项</SectionTitle>
            <MutedText testID="tray-settings-summary">
              {props.settings.trayPanelSummary()}
            </MutedText>
          </Column>
          <ActionButton
            testID="btn-toggle-tray-panel"
            label={props.panelOpen ? "关闭托盘面板" : "预览托盘面板"}
            variant="secondary"
            onPress={props.onTogglePanel}
          />
        </Row>
        <Divider />
        <SwitchRow
          plain
          rowTestID="tray-setting-row-noise_level"
          testID="tray-setting-toggle-noise_level"
          class="py-1"
          label="降噪／通透档位"
          summary="固定紧跟降噪模式下方，按当前模式条件显示"
          checked={panel().showNoiseLevels}
          onChange={props.settings.setShowNoiseLevels}
        />
        <Divider />
        <Row class="items-center justify-between gap-2">
          <Column class="gap-0.5">
            <BodyText class="font-medium">扩展控制项</BodyText>
            <MutedText>按顺序显示，并与当前耳机型号能力取交集</MutedText>
          </Column>
          <ActionButton
            testID="tray-setting-reset"
            label="恢复默认"
            variant="ghost"
            disabled={props.settings.trayPanelIsDefault()}
            onPress={props.settings.resetTrayPanel}
          />
        </Row>
        <Column class="gap-2">
          <Each of={panel().controlOrder} key={(id: TrayPanelControl) => id}>
            {(id, index) => (
              <ListItem
                testID={`tray-setting-row-${id()}`}
                direction="row"
                class="items-center justify-between gap-2 px-3 py-2.5"
              >
                <Column class="flex-1 gap-0.5">
                  <BodyText class="font-medium">{props.settings.controlMeta(id()).label}</BodyText>
                  <MutedText>{props.settings.controlMeta(id()).summary}</MutedText>
                </Column>
                <Row class="items-center gap-1.5">
                  <ActionButton
                    testID={`tray-setting-up-${id()}`}
                    label="上移"
                    variant="secondary"
                    disabled={index() === 0}
                    accessibilityLabel={`上移 ${props.settings.controlMeta(id()).label}`}
                    onPress={() => props.settings.moveControl(id(), -1)}
                    class="px-2 py-1"
                  />
                  <ActionButton
                    testID={`tray-setting-down-${id()}`}
                    label="下移"
                    variant="secondary"
                    disabled={index() === panel().controlOrder.length - 1}
                    accessibilityLabel={`下移 ${props.settings.controlMeta(id()).label}`}
                    onPress={() => props.settings.moveControl(id(), 1)}
                    class="px-2 py-1"
                  />
                  <ToggleSwitch
                    testID={`tray-setting-toggle-${id()}`}
                    checked={panel().enabledControls.includes(id())}
                    accessibilityLabel={props.settings.controlMeta(id()).label}
                    onChange={(enabled) => props.settings.setControlEnabled(id(), enabled)}
                  />
                </Row>
              </ListItem>
            )}
          </Each>
        </Column>
      </Card>
    </Column>
  );
}
