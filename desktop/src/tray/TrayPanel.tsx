import type { PullFeedback } from "../model/circulation";
import type { TrayControlGroup, TrayField, TrayPanelModel } from "../model/trayPanel";
import {
  ActionButton,
  BodyText,
  Column,
  Divider,
  LabelText,
  MutedText,
  NoticeBanner,
  Row,
  ScrollArea,
  SegmentedTabs,
  SelectField,
  StatusBadge,
  ToggleSwitch,
} from "../ui";

export interface TrayPanelProps {
  model: TrayPanelModel;
  pulling: boolean;
  pullFeedback: PullFeedback | null;
  onPull: (address: string) => void;
  onOpenSoundSettings: () => void;
  onConnect: (headsetId: string) => void;
  onDisconnect: () => void;
  onSelectNoiseMode: (mode: string, sessionId: number) => void;
  onOpenMain: () => void;
  onOpenAddHeadset: () => void;
  onOpenControlSettings: () => void;
  onQuit: () => void;
  onPopupOpenChange: (open: boolean) => void;
  onPointerEnter: () => void;
}

function TrayFieldView(props: { field: TrayField; onPopupOpenChange: (open: boolean) => void }) {
  const field = props.field;
  if (field.kind === "segments") {
    return (
      <Column testID={field.testID} class="gap-1.5">
        <LabelText>{field.label}</LabelText>
        <SegmentedTabs
          accessibilityLabel={field.label}
          options={field.options}
          value={field.value}
          disabled={field.disabled}
          onChange={field.onChange}
        />
      </Column>
    );
  }
  if (field.kind === "toggle") {
    return (
      <Row
        testID={`${field.testID}-row`}
        class="items-center justify-between gap-2 rounded-(--radius) border border-border bg-card px-3 py-2"
      >
        <Column class="flex-1 gap-0.5">
          <BodyText class="text-xs font-medium">{field.label}</BodyText>
          {field.caption ? (
            <MutedText testID={`${field.testID}-caption`}>{field.caption}</MutedText>
          ) : null}
        </Column>
        <ToggleSwitch
          testID={field.testID}
          checked={field.checked}
          disabled={field.disabled}
          accessibilityLabel={field.label}
          onChange={field.onChange}
        />
      </Row>
    );
  }
  return (
    <SelectField
      testID={field.testID}
      label={field.label}
      items={field.items}
      value={field.value}
      placeholder="请选择"
      disabled={field.disabled}
      hint={field.hint ?? undefined}
      onOpenChange={props.onPopupOpenChange}
      onChange={(value) => {
        if (value) field.onChange(value);
      }}
    />
  );
}

export function TrayPanel(props: TrayPanelProps) {
  const summary = () => props.model.summary();

  return (
    <Column testID="tray-panel-root" onPointerEnter={props.onPointerEnter} class="flex-1 bg-background">
      <ScrollArea>
        <Column class="gap-3 p-3.5">
          <Row class="items-center justify-between gap-2">
            <Column class="flex-1 gap-0.5">
              <BodyText testID="tray-panel-title" class="text-sm font-semibold">
                {summary().headsetName ?? "OpenRose"}
              </BodyText>
              <MutedText testID="tray-panel-status">{summary().stateText}</MutedText>
            </Column>
            {summary().connected ? (
              <StatusBadge tone="success" label="已连接" testID="tray-status-badge" />
            ) : summary().connecting ? (
              <ActionButton
                testID="tray-btn-cancel-connect"
                label="取消连接"
                variant="secondary"
                onPress={props.onDisconnect}
                class="px-2.5 py-1"
              />
            ) : summary().reconnectTarget ? (
              <ActionButton
                testID="tray-btn-reconnect"
                label={summary().reconnectLabel}
                onPress={() => {
                  const target = summary().reconnectTarget;
                  if (target) props.onConnect(target);
                }}
                class="px-2.5 py-1"
              />
            ) : null}
          </Row>

          {summary().batteryText ? (
            <MutedText testID="tray-battery">{summary().batteryText!}</MutedText>
          ) : null}

          {summary().remote ? (
            <Column
              testID="tray-remote-card"
              class="gap-2 rounded-(--radius) border border-border bg-muted/40 p-2.5"
            >
              <Row class="items-center justify-between gap-2">
                <Column class="flex-1 gap-0.5">
                  <BodyText testID="tray-remote-title" class="text-xs font-medium">
                    {summary().remote!.title}
                  </BodyText>
                  {summary().remote!.batteryText ? (
                    <MutedText testID="tray-remote-battery">
                      {summary().remote!.batteryText!}
                    </MutedText>
                  ) : null}
                </Column>
                {summary().remote!.address ? (
                  <ActionButton
                    testID="tray-btn-pull"
                    label="接到这台电脑"
                    busy={props.pulling}
                    disabled={props.pulling}
                    onPress={() => {
                      const address = summary().remote?.address;
                      if (address) props.onPull(address);
                    }}
                    class="px-2.5 py-1"
                  />
                ) : null}
              </Row>
            </Column>
          ) : null}

          {props.pullFeedback ? (
            <NoticeBanner
              testID="tray-pull-feedback"
              tone={props.pullFeedback.tone}
              title={props.pullFeedback.title}
              detail={props.pullFeedback.detail}
            />
          ) : null}

          {summary().noiseModes.length > 0 ? (
            <Column class="gap-1.5">
              <LabelText>降噪模式</LabelText>
              <SegmentedTabs
                accessibilityLabel="托盘降噪模式"
                options={summary().noiseModes.map((choice) => ({
                  value: choice.mode,
                  label: choice.label,
                  disabled: !choice.enabled,
                  testID: `tray-noise-${choice.mode}`,
                }))}
                value={summary().noiseModes.find((choice) => choice.selected)?.mode ?? null}
                onChange={(mode) => {
                  const sessionId = summary().sessionId;
                  const choice = summary().noiseModes.find((item) => item.mode === mode);
                  if (sessionId === null || !choice || choice.selected) return;
                  props.onSelectNoiseMode(mode, sessionId);
                }}
              />
              {summary().noiseFeedbackText ? (
                <MutedText testID="tray-noise-feedback">{summary().noiseFeedbackText!}</MutedText>
              ) : null}
            </Column>
          ) : null}

          <Each of={props.model.controlGroups()} key={(group: TrayControlGroup) => group.id}>
            {(group) => (
              <Column testID={`tray-control-${group().id}`} class="gap-2">
                <Each of={group().fields} key={(field: TrayField) => field.testID}>
                  {(field) => (
                    <TrayFieldView field={field()} onPopupOpenChange={props.onPopupOpenChange} />
                  )}
                </Each>
              </Column>
            )}
          </Each>

          {props.model.commandError() ? (
            <NoticeBanner testID="tray-command-error" tone="danger" title={props.model.commandError()!} />
          ) : null}

          {summary().errorText ? (
            <NoticeBanner tone="danger" title={summary().errorText!} />
          ) : null}

          {summary().addHeadset ? (
            <ActionButton
              testID="tray-btn-add-headset"
              label="添加耳机"
              onPress={props.onOpenAddHeadset}
            />
          ) : null}

          {summary().currentOutput || summary().soundSettingsAvailable ? (
            <Row testID="tray-audio-row" class="items-center justify-between gap-2">
              {summary().currentOutput ? (
                <MutedText testID="tray-current-output" class="flex-1">
                  {`当前输出：${summary().currentOutput!}`}
                </MutedText>
              ) : null}
              {summary().soundSettingsAvailable ? (
                <ActionButton
                  testID="tray-btn-sound-settings"
                  label="打开声音设置"
                  variant="ghost"
                  onPress={props.onOpenSoundSettings}
                  class="px-2 py-1"
                />
              ) : null}
            </Row>
          ) : null}

          <Divider />

          <Row class="items-center justify-between gap-2">
            <ActionButton
              testID="tray-btn-open-main"
              label="打开 OpenRose"
              variant="ghost"
              onPress={props.onOpenMain}
              class="px-2 py-1"
            />
            <Row class="items-center gap-1">
              {summary().addHeadset ? null : (
                <ActionButton
                  testID="tray-btn-control-settings"
                  label="控制项设置"
                  variant="ghost"
                  onPress={props.onOpenControlSettings}
                  class="px-2 py-1"
                />
              )}
              <ActionButton
                testID="tray-btn-quit"
                label="退出"
                variant="ghost"
                onPress={props.onQuit}
                class="px-2 py-1"
              />
            </Row>
          </Row>
        </Column>
      </ScrollArea>
    </Column>
  );
}
