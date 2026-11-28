import {
  TRAY_PANEL_CONTROL_META,
  isDefaultTrayPanelSettings,
  normalizeTrayPanelSettings,
  trayPanelSettingsSummary,
} from "../../model/trayPanelSettings";
import type {
  DesktopSettingsStore,
  TrayAvailability,
  TrayPanelControl,
} from "../../model/trayPanelSettings";
import {
  ActionButton,
  BodyText,
  Card,
  Column,
  Divider,
  MutedText,
  NoticeBanner,
  Row,
  SectionTitle,
  StatusBadge,
  ToggleSwitch,
} from "../../ui";

export interface TraySettingsPageProps {
  settings: DesktopSettingsStore;
  availability: TrayAvailability;
  panelOpen: boolean;
  onTogglePanel: () => void;
}

export function TraySettingsPage(props: TraySettingsPageProps) {
  const panel = () => normalizeTrayPanelSettings(props.settings.trayPanel());
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
        <Row class="items-center justify-between gap-3">
          <Column class="flex-1 gap-0.5">
            <BodyText class="font-medium">后台控制</BodyText>
            <MutedText testID="background-control-summary">
              关闭窗口后保留在托盘，耳机控制不中断；从托盘退出才结束控制连接
            </MutedText>
          </Column>
          <ToggleSwitch
            testID="toggle-background-control"
            checked={props.settings.backgroundControl()}
            accessibilityLabel="后台控制"
            onChange={props.settings.setBackgroundControl}
          />
        </Row>
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
              {trayPanelSettingsSummary(panel())}
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
        <Row testID="tray-setting-row-noise_level" class="items-center justify-between gap-3 py-1">
          <Column class="flex-1 gap-0.5">
            <BodyText class="font-medium">降噪／通透档位</BodyText>
            <MutedText>固定紧跟降噪模式下方，按当前模式条件显示</MutedText>
          </Column>
          <ToggleSwitch
            testID="tray-setting-toggle-noise_level"
            checked={panel().showNoiseLevels}
            accessibilityLabel="降噪／通透档位"
            onChange={props.settings.setShowNoiseLevels}
          />
        </Row>
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
            disabled={isDefaultTrayPanelSettings(panel())}
            onPress={props.settings.resetTrayPanel}
          />
        </Row>
        <Column class="gap-2">
          <Each of={panel().controlOrder} key={(id: TrayPanelControl) => id}>
            {(id, index) => (
              <Row
                testID={`tray-setting-row-${id()}`}
                class="items-center justify-between gap-2 rounded-(--radius) border border-border bg-muted/40 px-3 py-2.5"
              >
                <Column class="flex-1 gap-0.5">
                  <BodyText class="font-medium">{TRAY_PANEL_CONTROL_META[id()].label}</BodyText>
                  <MutedText>{TRAY_PANEL_CONTROL_META[id()].summary}</MutedText>
                </Column>
                <Row class="items-center gap-1.5">
                  <ActionButton
                    testID={`tray-setting-up-${id()}`}
                    label="上移"
                    variant="secondary"
                    disabled={index() === 0}
                    accessibilityLabel={`上移 ${TRAY_PANEL_CONTROL_META[id()].label}`}
                    onPress={() => props.settings.moveControl(id(), -1)}
                    class="px-2 py-1"
                  />
                  <ActionButton
                    testID={`tray-setting-down-${id()}`}
                    label="下移"
                    variant="secondary"
                    disabled={index() === panel().controlOrder.length - 1}
                    accessibilityLabel={`下移 ${TRAY_PANEL_CONTROL_META[id()].label}`}
                    onPress={() => props.settings.moveControl(id(), 1)}
                    class="px-2 py-1"
                  />
                  <ToggleSwitch
                    testID={`tray-setting-toggle-${id()}`}
                    checked={panel().enabledControls.includes(id())}
                    accessibilityLabel={TRAY_PANEL_CONTROL_META[id()].label}
                    onChange={(enabled) => props.settings.setControlEnabled(id(), enabled)}
                  />
                </Row>
              </Row>
            )}
          </Each>
        </Column>
      </Card>
    </Column>
  );
}
