import type { Bounds, Point, Size } from "nsh";
import {
  cleanup,
  colorScheme,
  computed,
  effect,
  mount,
  pixelRatio,
  platform,
  Scroll,
  signal,
  Text,
  View,
} from "nsh";
import { Button } from "nsh/base";
import {
  Application,
  currentWindow,
  displays,
  Tray,
  Window,
} from "nsh/desktop";
import { MockHeadset, ProtoHost } from "openrose-prototype-native";
import { computeTrayPanelPosition } from "./src/panelPosition";
import trayIcon from "./tray.svg";

const ANC_MODES = ["降噪", "通透", "关闭"] as const;
const TRAY_PANEL_TITLE = "OpenRose 托盘面板";
const TRAY_PANEL_SIZE: Size = { width: 320, height: 320 };

const headset = new MockHeadset();
const pid = ProtoHost.pid();
const runtimeEnv = ProtoHost.runtimeEnv();

const connected = signal(headset.connected);
const ancMode = signal(headset.ancMode);
const leftBattery = signal(headset.leftBattery);
const rightBattery = signal(headset.rightBattery);
const caseBattery = signal(headset.caseBattery);
const revision = signal(headset.revision);
const summary = signal(headset.summary);

const trayPanelOpen = signal(false);
const trayPanelPosition = signal<Point | "center">("center");
const lastTrayBounds = signal<Bounds | null>(null);
const eventLog = signal<string[]>([`#1 应用启动 (PID ${pid})`]);

const BLUR_TOGGLE_SUPPRESS_MS = 250;

let eventCounter = 1;
let lastBlurClosedAt = 0;
let trayPanelPointerEntered = false;

function recordEvent(message: string) {
  eventCounter += 1;
  eventLog.update((entries) => [`#${eventCounter} ${message}`, ...entries].slice(0, 14));
}

function syncHeadsetState() {
  connected.set(headset.connected);
  ancMode.set(headset.ancMode);
  leftBattery.set(headset.leftBattery);
  rightBattery.set(headset.rightBattery);
  caseBattery.set(headset.caseBattery);
  revision.set(headset.revision);
  summary.set(headset.summary);
}

function setAncMode(mode: string, source: string) {
  const next = headset.setAncMode(mode);
  syncHeadsetState();
  recordEvent(`${source}: 切换模式 → ${next}`);
}

function toggleConnected(source: string) {
  const next = headset.toggleConnected();
  syncHeadsetState();
  recordEvent(`${source}: ${next ? "模拟已连接" : "模拟已断开"}`);
}

function drainBattery() {
  headset.drainBatteryStep();
  syncHeadsetState();
  recordEvent(`电量变化: L${leftBattery()}% R${rightBattery()}%`);
}

function showMainWindow(source: string) {
  const mainWindow = Window.get("main");
  if (mainWindow) {
    mainWindow.show();
    mainWindow.focus();
    recordEvent(`${source}: 唤回主窗口`);
  }
}

function hideMainWindow(source: string) {
  const mainWindow = Window.get("main");
  if (mainWindow) {
    mainWindow.hide();
    recordEvent(`${source}: 主窗口隐藏，驻留托盘`);
  }
}

function openTrayPanel(bounds: Bounds | null, source: string) {
  if (bounds) {
    lastTrayBounds.set(bounds);
  }
  const anchor = bounds ?? lastTrayBounds();
  if (platform.os !== "linux" && anchor) {
    trayPanelPosition.set(computeTrayPanelPosition(anchor, TRAY_PANEL_SIZE, displays()));
  } else {
    trayPanelPosition.set("center");
  }
  trayPanelPointerEntered = false;
  trayPanelOpen.set(true);
  Window.get("tray-panel")?.focus();
  recordEvent(`${source}: 打开托盘面板`);
}

function toggleTrayPanel(bounds: Bounds | null, source: string) {
  if (trayPanelOpen()) {
    trayPanelOpen.set(false);
    recordEvent(`${source}: 关闭托盘面板`);
    return;
  }
  if (Date.now() - lastBlurClosedAt < BLUR_TOGGLE_SUPPRESS_MS) {
    lastBlurClosedAt = 0;
    return;
  }
  openTrayPanel(bounds, source);
}

function AncModeStrip(props: { source: string }) {
  return (
    <View class="flex-row gap-2">
      <Each of={ANC_MODES}>
        {(mode) => (
          <Button
            testID={`anc-${props.source}-${mode()}`}
            onPress={() => setAncMode(mode(), props.source)}
            class="flex-1 items-center rounded-lg border border-border py-2 active:opacity-80"
            classList={{
              "border-primary bg-primary": ancMode() === mode(),
              "bg-background": ancMode() !== mode(),
            }}
          >
            <Text
              class="text-sm font-medium"
              classList={{
                "text-primary-foreground": ancMode() === mode(),
                "text-foreground": ancMode() !== mode(),
              }}
            >
              {mode()}
            </Text>
          </Button>
        )}
      </Each>
    </View>
  );
}

function MainWindowContent() {
  const win = currentWindow();
  win.on("closePress", ({ reason }) => {
    hideMainWindow(`关窗(${reason})`);
  });

  const displaySummary = computed(() => {
    const list = displays();
    if (list.length === 0) return `pixelRatio=${pixelRatio()}`;
    return list
      .map(
        (d) =>
          `${d.name || d.id} ${Math.round(d.bounds.size.width)}×${Math.round(d.bounds.size.height)} @${d.scaleFactor}x`,
      )
      .join(" | ");
  });

  return (
    <Scroll class="flex-1 bg-background">
      <View class="gap-4 p-5">
        <View class="gap-1">
          <Text class="text-xl font-semibold text-foreground">OpenRose NSH 原型</Text>
          <Text class="text-xs text-muted-foreground">
            {`PID ${pid} · ${platform.os} · ${colorScheme()} · pixelRatio ${pixelRatio()} · rev ${revision()}`}
          </Text>
          <Text class="text-xs text-muted-foreground">{runtimeEnv}</Text>
          <Text class="text-xs text-muted-foreground">{`显示器: ${displaySummary()}`}</Text>
        </View>

        <View class="gap-3 rounded-xl border border-border bg-card p-4">
          <View class="flex-row items-center justify-between">
            <Text class="text-base font-semibold text-foreground">{headset.modelName}</Text>
            <Text
              testID="main-connection-status"
              class="text-xs font-medium text-muted-foreground"
            >
              {connected() ? "模拟已连接" : "模拟未连接"}
            </Text>
          </View>
          <Text testID="main-summary" class="text-sm text-foreground">
            {summary()}
          </Text>
          <View class="flex-row gap-2">
            <View class="flex-1 items-center rounded-lg bg-muted p-2">
              <Text class="text-xs text-muted-foreground">左耳</Text>
              <Text class="text-base font-semibold text-foreground">{`${leftBattery()}%`}</Text>
            </View>
            <View class="flex-1 items-center rounded-lg bg-muted p-2">
              <Text class="text-xs text-muted-foreground">右耳</Text>
              <Text class="text-base font-semibold text-foreground">{`${rightBattery()}%`}</Text>
            </View>
            <View class="flex-1 items-center rounded-lg bg-muted p-2">
              <Text class="text-xs text-muted-foreground">充电盒</Text>
              <Text class="text-base font-semibold text-foreground">{`${caseBattery()}%`}</Text>
            </View>
          </View>
          <AncModeStrip source="主窗口" />
          <View class="flex-row gap-2">
            <Button
              testID="btn-toggle-connected"
              onPress={() => toggleConnected("主窗口")}
              class="flex-1 items-center rounded-lg border border-border py-2 active:bg-muted"
            >
              <Text class="text-xs font-medium text-foreground">
                {connected() ? "模拟断开" : "模拟重连"}
              </Text>
            </Button>
            <Button
              testID="btn-drain-battery"
              onPress={drainBattery}
              class="flex-1 items-center rounded-lg border border-border py-2 active:bg-muted"
            >
              <Text class="text-xs font-medium text-foreground">电量 -5%</Text>
            </Button>
          </View>
        </View>

        <View class="gap-2 rounded-xl border border-border bg-card p-4">
          <Text class="text-sm font-semibold text-foreground">窗口与托盘验证</Text>
          <View class="flex-row gap-2">
            <Button
              testID="btn-toggle-tray-panel"
              onPress={() => toggleTrayPanel(null, "主窗口按钮")}
              class="flex-1 items-center rounded-lg bg-primary py-2 active:opacity-80"
            >
              <Text class="text-xs font-medium text-primary-foreground">
                {trayPanelOpen() ? "关闭托盘面板" : "打开托盘面板"}
              </Text>
            </Button>
            <Button
              testID="btn-hide-main"
              onPress={() => hideMainWindow("主窗口按钮")}
              class="flex-1 items-center rounded-lg border border-border py-2 active:bg-muted"
            >
              <Text class="text-xs font-medium text-foreground">隐藏到托盘</Text>
            </Button>
            <Button
              testID="btn-quit"
              onPress={() => Application.quit()}
              class="items-center rounded-lg border border-border px-4 py-2 active:bg-muted"
            >
              <Text class="text-xs font-medium text-foreground">退出</Text>
            </Button>
          </View>
        </View>

        <View class="gap-1.5 rounded-xl border border-border bg-card p-4">
          <Text class="text-sm font-semibold text-foreground">事件记录</Text>
          <Each of={eventLog()}>
            {(entry) => (
              <Text class="text-xs text-muted-foreground">{entry()}</Text>
            )}
          </Each>
        </View>
      </View>
    </Scroll>
  );
}

function TrayPanelContent() {
  return (
    <View
      class="flex-1 justify-between bg-background p-4"
      onMouseEnter={() => {
        trayPanelPointerEntered = true;
      }}
    >
      <View class="gap-3">
        <View class="flex-row items-center justify-between">
          <Text class="text-base font-semibold text-foreground">{TRAY_PANEL_TITLE}</Text>
          <Text class="text-xs text-muted-foreground">
            {connected() ? "已连接" : "未连接"}
          </Text>
        </View>
        <Text testID="panel-summary" class="text-xs text-muted-foreground">
          {summary()}
        </Text>
        <View class="flex-row gap-2">
          <View class="flex-1 items-center rounded-lg bg-muted p-2">
            <Text class="text-xs text-muted-foreground">左</Text>
            <Text class="text-sm font-semibold text-foreground">{`${leftBattery()}%`}</Text>
          </View>
          <View class="flex-1 items-center rounded-lg bg-muted p-2">
            <Text class="text-xs text-muted-foreground">右</Text>
            <Text class="text-sm font-semibold text-foreground">{`${rightBattery()}%`}</Text>
          </View>
          <View class="flex-1 items-center rounded-lg bg-muted p-2">
            <Text class="text-xs text-muted-foreground">盒</Text>
            <Text class="text-sm font-semibold text-foreground">{`${caseBattery()}%`}</Text>
          </View>
        </View>
        <AncModeStrip source="托盘面板" />
      </View>

      <View class="gap-2">
        <View class="flex-row gap-2">
          <Button
            testID="btn-panel-open-main"
            onPress={() => {
              trayPanelOpen.set(false);
              showMainWindow("托盘面板");
            }}
            class="flex-1 items-center rounded-lg bg-primary py-2 active:opacity-80"
          >
            <Text class="text-xs font-medium text-primary-foreground">打开主窗口</Text>
          </Button>
          <Button
            testID="btn-panel-quit"
            onPress={() => Application.quit()}
            class="items-center rounded-lg border border-border px-4 py-2 active:bg-muted"
          >
            <Text class="text-xs font-medium text-foreground">退出</Text>
          </Button>
        </View>
        <Text class="text-center text-xs text-muted-foreground">
          按 Escape 或切走焦点时自动收起
        </Text>
      </View>
    </View>
  );
}

function PrototypeRoot() {
  Application.setQuitMode("explicit");

  const stopReopen = Application.on("reopen", () => {
    showMainWindow("reopen");
  });
  const stopSecondInstance = Application.on("secondInstance", ({ args }) => {
    const suffix = args.length > 0 ? ` [${args.join(" ")}]` : "";
    showMainWindow(`secondInstance${suffix}`);
  });
  cleanup(() => {
    stopReopen();
    stopSecondInstance();
    Tray.set(null);
  });

  const tooltip = computed(() => `OpenRose · ${summary()}`);
  effect(() => {
    Tray.set({
      icon: trayIcon,
      tooltip: tooltip(),
      onClick: ({ bounds }) => {
        toggleTrayPanel(bounds, "托盘图标点击");
      },
      menu: [
        {
          label: trayPanelOpen() ? "关闭托盘面板" : "打开托盘面板",
          onSelect: () => toggleTrayPanel(null, "托盘菜单"),
        },
        {
          label: "打开主窗口",
          onSelect: () => showMainWindow("托盘菜单"),
        },
        { type: "separator" },
        {
          label: "降噪模式",
          submenu: ANC_MODES.map((mode) => ({
            label: mode,
            checked: ancMode() === mode,
            onSelect: () => setAncMode(mode, "托盘菜单"),
          })),
        },
        { type: "separator" },
        {
          label: "退出",
          onSelect: () => Application.quit(),
        },
      ],
    });
  });

  return (
    <View>
      <Window
        id="main"
        title="OpenRose 原型"
        defaultSize={{ width: 560, height: 560 }}
        minSize={{ width: 420, height: 420 }}
        closeOnPress={false}
      >
        <MainWindowContent />
      </Window>
      <Window
        id="tray-panel"
        open={trayPanelOpen()}
        kind="popup"
        title={TRAY_PANEL_TITLE}
        defaultSize={TRAY_PANEL_SIZE}
        defaultPosition={trayPanelPosition()}
        resizable={false}
        maximizable={false}
        minimizable={false}
        closeOnEscape={true}
        closeOnBlur={platform.os !== "linux"}
        onFocusChange={({ focused }) => {
          if (platform.os === "linux" && !focused && trayPanelOpen() && trayPanelPointerEntered) {
            trayPanelPointerEntered = false;
            lastBlurClosedAt = Date.now();
            trayPanelOpen.set(false);
            recordEvent("托盘面板收起 (blur)");
          }
        }}
        onOpenChange={({ open, reason }) => {
          trayPanelOpen.set(open);
          if (!open) {
            trayPanelPointerEntered = false;
            if (reason === "blur") {
              lastBlurClosedAt = Date.now();
            }
            recordEvent(`托盘面板收起 (${reason})`);
          }
        }}
      >
        <TrayPanelContent />
      </Window>
    </View>
  );
}

mount(() => <PrototypeRoot />);
