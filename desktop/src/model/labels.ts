import type { ConnectionPhase } from "./headset";

const NOISE_MODE_ORDER = ["anc", "wind", "transparency", "normal", "auto", "dynamic", "deep"];

const NOISE_MODE_LABELS: Record<string, string> = {
  anc: "主动降噪",
  normal: "关闭",
  wind: "抗风噪",
  transparency: "通透",
  auto: "自适应",
  deep: "深度降噪",
  dynamic: "动态降噪",
};

const SEMANTIC_LABELS: Record<string, string> = {
  ...NOISE_MODE_LABELS,
  aac_sbc: "AAC / SBC",
  ldac: "LDAC",
  ldac_lhdc: "LDAC + LHDC",
  lhdc: "LHDC",
  lc3: "LC3",
  chinese: "中文",
  english: "英文",
  pop: "流行",
  hifi: "Hi-Fi",
  rock: "摇滚",
  light: "轻音乐",
  eap: "EAP",
  custom: "自定义",
  off: "关闭",
  head_tracking: "头部跟踪",
  fixed: "固定",
  tv: "电视",
  music: "音乐",
  movie: "电影",
  game: "游戏",
  volume_up: "增加音量",
  volume_down: "减小音量",
  previous_track: "上一曲",
  next_track: "下一曲",
  voice_assistant: "语音助手",
  play_pause: "播放 / 暂停",
  none: "无操作",
  noise_cycle: "降噪轮换",
  game_mode: "游戏模式",
};

const SETTING_LABELS: Record<string, string> = {
  NoiseMode: "降噪模式",
  AncLevel: "降噪档位",
  TransparencyLevel: "通透档位",
  NoiseCycle: "降噪轮换",
  Eq: "预设均衡器",
  CustomEq: "自定义均衡器",
  BassBoost: "低音增强",
  GameMode: "游戏模式",
  AudioMode: "音频模式",
  Multipoint: "多点连接",
  SpatialAudio: "空间音频",
  SpatialMode: "空间模式",
  SpatialContent: "空间音频内容",
  EarAdaptive: "耳道自适应",
  IndependentMicActivation: "激活独立麦克风",
  IndependentMicMode: "独立麦克风模式",
  TouchEnabled: "触控总开关",
  EarDetection: "佩戴检测功能",
  TouchBinding: "触控手势",
  TapEnabled: "轻拍开关",
  TapBinding: "轻拍绑定",
  PromptLanguage: "提示音语言",
  PromptVolume: "提示音音量",
  Gain: "增益档位",
  HearingProtection: "听力保护阈值",
};

const FIELD_LABELS: Record<string, string> = {
  LeftBattery: "左耳电量",
  RightBattery: "右耳电量",
  CaseBattery: "充电盒电量",
  LeftCharging: "左耳充电状态",
  RightCharging: "右耳充电状态",
  LeftInCase: "左耳在盒状态",
  RightInCase: "右耳在盒状态",
  CaseFlag: "充电盒状态",
  Firmware: "固件版本",
  EqSlot: "均衡器槽位",
  TouchKeys: "触控按键映射",
  TapKeys: "敲击按键映射",
  FindState: "寻机状态",
  HideSearch: "隐藏蓝牙搜索",
};

const ERROR_CATEGORY_LABELS: Record<string, string> = {
  PermissionRequired: "缺少蓝牙权限",
  TransportUnavailable: "蓝牙传输不可用",
  NotReady: "耳机尚未就绪",
  Unsupported: "当前固件或型号不支持",
  InvalidArgument: "参数无效",
  NotApplied: "耳机未应用该设置",
  Timeout: "等待耳机确认超时",
  TimedOut: "等待耳机确认超时",
  Disconnected: "连接已断开",
  Failed: "操作失败",
};

const TRAY_UNAVAILABLE_LABELS: Record<string, string> = {
  NoSessionBus: "未连接到 D-Bus 用户总线",
  QueryFailed: "无法查询 D-Bus 上的托盘服务",
  MissingGdbus: "未找到 D-Bus 查询工具 gdbus",
  NoWatcher: "D-Bus 上没有 StatusNotifierWatcher",
  NoHost: "没有已注册的系统托盘宿主",
  HostStateUnreadable: "无法读取系统托盘宿主状态",
};

const LOGIN_LAUNCH_REASON_LABELS: Record<string, string> = {
  DevelopmentBuild: "仅安装版可设置登录时启动",
  UnsupportedSystem: "当前系统暂不支持登录时启动",
  Sandboxed: "当前沙盒环境暂不支持登录时启动",
  ConfigDirUnknown: "无法定位用户配置目录（XDG_CONFIG_HOME / HOME）",
  ConfigReadFailed: "无法读取自启动配置",
  ConfigWriteFailed: "无法写入自启动配置",
  InvalidExecutable: "可执行文件路径无效",
};

const PREREQUISITE_LABELS: Record<string, string> = {
  SpatialModeRequired: "请先开启空间模式",
  IndependentMicActivationRequired: "请先激活独立麦克风",
};

const PHASE_LABELS: Record<ConnectionPhase, string> = {
  Connecting: "正在建立蓝牙连接…",
  Discovering: "正在发现耳机服务…",
  Subscribing: "正在订阅状态通知…",
  Initializing: "正在初始化并读取状态…",
  Ready: "已连接",
};

export function sortNoiseModes(modes: string[]): string[] {
  const rank = (mode: string) => {
    const index = NOISE_MODE_ORDER.indexOf(mode);
    return index === -1 ? NOISE_MODE_ORDER.length : index;
  };
  return [...modes].sort((a, b) => rank(a) - rank(b));
}

export function noiseModeLabel(mode: string): string {
  return NOISE_MODE_LABELS[mode] ?? mode;
}

export function semanticLabel(value: string): string {
  return SEMANTIC_LABELS[value] ?? value;
}

export function settingLabel(setting: string): string {
  return SETTING_LABELS[setting] ?? setting;
}

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? SETTING_LABELS[field] ?? field;
}

export function errorCategoryLabel(code: string): string {
  return ERROR_CATEGORY_LABELS[code] ?? code;
}

export function connectionPhaseLabel(phase: ConnectionPhase): string {
  return PHASE_LABELS[phase];
}

export function prerequisiteLabel(category: string): string {
  return PREREQUISITE_LABELS[category] ?? category;
}

export function trayUnavailableLabel(reason: string | null | undefined): string {
  return (reason && TRAY_UNAVAILABLE_LABELS[reason]) || "系统托盘不可用";
}

export function loginLaunchReasonLabel(reason: string | null | undefined): string {
  return (reason && LOGIN_LAUNCH_REASON_LABELS[reason]) || "当前系统暂不支持登录时启动";
}

export function controlErrorTitle(code: string): string {
  return `${errorCategoryLabel(code)} (${code})`;
}

export function controlErrorText(error: { code: string; message: string }): string {
  const title = controlErrorTitle(error.code);
  return error.message ? `${title}：${error.message}` : title;
}
